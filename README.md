# JobWrangler

[JavaDoc](https://tsombrero.github.io/jobwrangler/apidocs/).

JobWrangler is a job execution library along the lines of the [iOS OperationQueue](https://developer.apple.com/documentation/foundation/operationqueue) and the excellent [Android Priority JobQueue](https://github.com/yigit/android-priority-jobqueue). JobWrangler builds upon the concept of the Job-as-a-state-machine with dynamic inter-job dependency chains and other handy orchestration features. Jobs get the following for free, or with minimal configuration:
- Limited retries and exponential backoff (see RunPolicy)
- Defer work until gating conditions are met (e.g. network availability)
- Strong or weak dependency enforcement between jobs (see DependencyFailureStrategy)
- Dynamic dependency between jobs
- Singleton jobs (see ConcurrencyPolicy)
- Throttled jobs that accumulate batches of work
- Persisted jobs that survive application restart

With all those hairy details taken care of you can focus on the bits that must be explicitly implemented:

1. Update the local model as needed to reflect the action taking place (e.g. update the UI as if the Job has already succeeded)
1. Perform the actual work
1. Success/Failure handling

## Example: A Basic Job

Let's start with a basic job to upload a photo. Jobs are generics of their result type. The result of this job will be a URI to the uploaded photo, so this will be a `Job<URI>`.

### Upload Photo Job:

```java
public static class UploadPhotoJob extends Job<URI> {
    private final String photo;

    public UploadPhotoJob(String photo) {
        this.photo = photo;
    }

    @Override
    public State onAdded() {
        System.out.println("Uploading the photo");
        return State.READY;
    }

    @Override
    public State doWork() {
        // Potentially long-running network operation, adding the photo to storage
        URI photoUri = myRemoteService.uploadPhoto(photo);

        if (photoUri != null) {
            // Success! Set the job's result and return.
            setResult(photoUri);
            return State.SUCCEEDED;
        }

        // We did not succeed this time but willing to give it another go.
        return State.WAIT;
    }
}
```

To execute the job, you would do something like this:

```java
// In real life the JobManager should be a singleton
JobManager jobManager = new JobManager();
jobManager.submit(new UploadPhotoJob(thePhoto));
```

The first callback is `onAdded()` which happens once the Job has been submitted and accepted by the JobManager. This callback happens only once regardless of retries, so it's a good place to update the UI to reflect what's going on (e.g. show a progress bar, or show the added photo in a list).

The second callback is `doWork()` which is where the long-running work of uploading the photo happens. This callback represents an attempt and may be retried if there's a problem.

Jobs are state machines-- `onAdded()` and `doWork()` are state transition callbacks used by the JobManager to handle a particular state and transition to the next one. They optimistically return the next state for the Job. The returned state will be validated against other factors (retry limits etc) by the JobManager and applied. If a state transition callback returns a terminal state like `SUCCEEDED` or `FAULTED`, the job will move to that terminal state and be done.

For more on Job state flow, see the Diagram below and the javadoc for the [Job class](https://tsombrero.github.io/jobwrangler/apidocs/com/serfshack/jobwrangler/core/Job.html) and [State enum](https://tsombrero.github.io/jobwrangler/apidocs/com/serfshack/jobwrangler/core/State.html)  
<img width="600" src="https://github.com/tsombrero/jobwrangler/blob/master/docs/res/stateflow.png" align="center">

## JobObserver
The [JobObserver](https://tsombrero.github.io/jobwrangler/apidocs/com/serfshack/jobwrangler/core/JobObserver.html) is how you keep track of ongoing jobs and get results when they're finished. `JobObserver` is a generic class of the same type as the `Job` it is observing. In this example `UploadPhotoJob extends Job<URI>` so the JobObserver is a `JobObserver<URI>`. Each `Job` has exactly one `JobObserver` and each `JobObserver` observes exactly one `Job`.

A Job's result can be fetched either with blocking calls or through a subscription model.

##### Getting the Job Result, blocking method:
```java
JobObserver<URI> uploadPhotoObserver = jobManager.submit(new UploadPhotoJob(thePhoto));
URI photo = uploadPhotoObserver.getResultBlocking(30, TimeUnit.SECONDS);
System.out.println("The result is: " + photo)
```
The call to get the result will block until the Job terminates or until the specified timeout. 

##### Getting the Job Result, subscription method
```java
JobObserver<URI> uploadPhotoObserver = jobManager.submit(new UploadPhotoJob(thePhoto));
uploadPhotoObserver.subscribeOnComplete(job1 -> System.out.println("The result is: " + job1.getResult()));
```
(Use of Lambdas not required, but makes for cleaner demo code.)

## Run Policy

Every Job has a [RunPolicy](https://tsombrero.github.io/jobwrangler/apidocs/com/serfshack/jobwrangler/core/RunPolicy.html) to manage its lifecycle. The default RunPolicy allows up to 5 attempts before the job fails. An attempt is defined as a call to [doWork()](https://tsombrero.github.io/jobwrangler/apidocs/com/serfshack/jobwrangler/core/Job.html#doWork--). If `doWork()` returns `READY` or `WAIT`, an attempt is used and the job will try again after the configured retry delay (static or backoff). A Job's maximum age as well as the max age of individual attempts are configurable.

The default RunPolicy is sane but likely not ideal for every situation. In general, jobs should implement `configureRunPolicy()` and return one designed for the Job. 

For example:

```java
@Override
protected RunPolicy configureRunPolicy() {
    return RunPolicy.newLimitAttemptsPolicy(20)
            .withAttemptTimeout(30, TimeUnit.SECONDS)
            .withJobTimeout(3, TimeUnit.MINUTES)
            .withExponentialBackoff(1, 10, TimeUnit.SECONDS)
            .withConcurrencyPolicy(new FIFOPolicy("uploadphoto"))
            .withGatingCondition(new NetworkConnectivityCondition())
            .build();
}
```

RunPolicy objects are created using a Builder pattern. In this case we start with a canned [RunPolicy.Builder](https://tsombrero.github.io/jobwrangler/apidocs/com/serfshack/jobwrangler/core/RunPolicy.Builder.html) via [newLimitAttemptsPolicy](https://tsombrero.github.io/jobwrangler/apidocs/com/serfshack/jobwrangler/core/RunPolicy.html#newLimitAttemptsPolicy-int-) and build upon it. 

You may have noticed these guys:
```java
            .withConcurrencyPolicy(new FIFOPolicy("uploadphoto"))
            .withGatingCondition(new NetworkConnectivityCondition())
```
A Job can have at most one `ConcurrencyPolicy` and zero or more `GatingCondition` objects.

### Gating Conditions

A [GatingCondition](https://tsombrero.github.io/jobwrangler/apidocs/com/serfshack/jobwrangler/core/GatingCondition.html) is a
way of keeping a Job in the WAIT state. No work will be attempted until the conditions are satisfied. The most obvious use case for this is to avoid churn while waiting for network connectivity. Creating a GatingCondition is easy:

```java
static class NetworkConnectivityCondition implements GatingCondition {
    @Override
    public boolean isSatisfied() {
        return YourNetworkManager.isNetworkAvailable();
    }

    @Override
    public String getMessage() {
        return isSatisfied() ? "Network is available" : "Cannot continue, no network";
    }
}
```
Just implement the interface and add your `GatingCondition` class via `RunPolicy.Builder.withGatingCondition()`. The Job will remain in the `WAIT` state without burning any retry attempts until the condition is satisfied or until the Job times out. The condition is checked on a backoff schedule maxing out at once per 10 seconds.

### Concurrency Policy

A RunPolicy may have a [ConcurrencyPolicy](https://tsombrero.github.io/jobwrangler/apidocs/com/serfshack/jobwrangler/core/concurrencypolicy/AbstractConcurrencyPolicy.html) that governs how it coexists with other jobs with the same policy. This is how Singleton Jobs are enforced for example.

When a Job is submitted, and before it is added, its ConcurrencyPolicy is compared with that of other running Jobs. If a running Job has a matching ConcurrencyPolicy the jobs are said to "collide" and the running job's ConcurrencyPolicy resolves any conflict in its [onCollision()](https://tsombrero.github.io/jobwrangler/apidocs/com/serfshack/jobwrangler/core/concurrencypolicy/AbstractConcurrencyPolicy.html#onCollision-com.serfshack.jobwrangler.core.Job-com.serfshack.jobwrangler.core.Job-) implementation. This may include canceling either job, setting up a dependency relationship, consolidating work into a single job, or something else.

There are three built-in `ConcurrencyPolicy` implementations:
##### SingletonPolicyKeepExisting
Only one job per matching policy may be running at a time. The running job has an opportunity to absorb work from the duplicate job through its `assimilate()` method and the new job moves immediately to a terminal state without being added.
##### SingletonPolicyReplaceExisting
Similar to the previous policy but the roles are reversed. In this case the incoming Job survives and the existing one is terminated.
##### FIFOPolicy
Jobs with matching `FIFOPolicy`s run in order. Every earlier matching job must reach a terminal state (success or failure) before the next one can proceed.

In the above example, this line:
```
            .withConcurrencyPolicy(new FIFOPolicy("uploadphoto"))
```
ensures that photo uploads are handled sequentially and not in parallel. The string parameter `"uploadphoto"` is an arbitrary key value used to match against other `FIFOPolicy` instances.

## Job Dependencies

A job may depend on any number of other jobs, which allows fairly complex dependency graphs to be created. A dependency relationship is created by calling [addDepended()](https://tsombrero.github.io/jobwrangler/apidocs/com/serfshack/jobwrangler/core/Job.html#addDepended-com.serfshack.jobwrangler.core.Dependable-com.serfshack.jobwrangler.core.Dependable.DependencyFailureStrategy-). You can specify a strong or weak dependency by passing [CASCADE_FAILURE or IGNORE_FAILURE](https://tsombrero.github.io/jobwrangler/apidocs/com/serfshack/jobwrangler/core/Dependable.DependencyFailureStrategy.html#CASCADE_FAILURE) to `addDepended()`. If a depended job moves to a `FAULTED` or `CANCELED` state, any strongly-depending jobs will immediately move to `FAULTED`.

A submitted job's dependencies must likewise be submitted to the JobManager; `addDepended()` will throw an `IllegalStateException` if the JobManager is not aware of the depended job.
