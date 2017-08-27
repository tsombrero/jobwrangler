# JobWrangler

JobWrangler is a job execution library along the lines of the [iOS OperationQueue](https://developer.apple.com/documentation/foundation/operationqueue) and the excellent [Android Priority JobQueue](https://github.com/yigit/android-priority-jobqueue). JobWrangler builds upon the concept of the Job-as-a-state-machine with dynamic inter-job dependency chains and other handy orchestration features. For example, Jobs get the following for free, or with minimal configuration:
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
            return State.READY;
        }
    }
```

To execute the job, you would do something like this:

```java
    // Note, in real life the JobManager should be a singleton
    JobManager jobManager = new JobManager();
    jobManager.submit(new UploadPhotoJob(thePhoto));
```

The first callback is `onAdded()` which means the Job has been submitted and accepted by the JobManager. This callback happens only once regardless of retries, so it's a good place to update the UI to reflect what's going on (e.g. show a progress bar, or show the added photo in a list).

The second callback is `doWork()` which is where the long-running work of uploading the photo happens. This callback represents an attempt and may be retried if there's a problem.

Jobs are state machines-- `onAdded()` and `doWork()` are state transition callbacks used by the JobManager to handle a particular state and transition to the next one. They optimistically return the next state for the Job. The returned state will be validated against other factors (retry limits etc) by the JobManager and applied.

For more on Job state flow, see the Diagram below and the javadoc for the [Job class](https://tsombrero.github.io/jobwrangler/apidocs/com/serfshack/jobwrangler/core/Job.html) and [State enum](https://tsombrero.github.io/jobwrangler/apidocs/com/serfshack/jobwrangler/core/State.html)  
<img width="300" src="https://github.com/tsombrero/jobwrangler/blob/master/docs/res/Screenshot%202017-08-26%2013.01.33.png" align="center">

Now let's take a look at how to get the job's result.

## JobObserver
The JobObserver is how you keep track of ongoing jobs and get results when they're finished. JobObserver is a generic of the same type as the Job it is observing. In this example `UploadPhotoJob extends Job<URI>` so the JobObserver is a `JobObserver<URI>`.

A Job's result can be fetched either with blocking calls or through a subscription model.

The blocking method:
```java
    JobObserver<URI> uploadPhotoObserver = jobManager.submit(new UploadPhotoJob(thePhoto));
    Photo photo = uploadPhotoObserver.getResultBlocking(30, TimeUnit.SECONDS);
```
The call to get the result will block until the Job terminates or until the specified timeout. 

The subscription method:
```java
JobObserver<URI> uploadPhotoObserver = jobManager.submit(new UploadPhotoJob(thePhoto));
uploadPhotoObserver.subscribeOnComplete(job1 -> System.out.println("The result is: " + job1.getResult()));
```
(Use of Lambdas not required, but makes for cleaner demo code.)

## Run Policy

The default RunPolicy allows up to 5 attempts before the job fails. An attempt is defined as a call to `doWork()`. If any *non-terminal* state (that is, any state other than `FAULTED`, `SUCCEEDED`, and `CANCELED`) is returned from `doWork()`, an attempt is used and the job will try again after the configured retry delay (static or backoff). A Job's maximum age as well as the max age of individual attempts are configurable.

When a Job is initialized it always calls `configureRunPolicy()` once so it knows how to behave. The method simply returns a new RunPolicy for the Job to use.

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

RunPolicy objects are created using a Builder pattern. See the [JavaDoc](https://tsombrero.github.io/jobwrangler/apidocs/com/serfshack/jobwrangler/core/RunPolicy.Builder.html) for details.

You may have noticed these guys:
```java
                    .withConcurrencyPolicy(new FIFOPolicy("uploadphoto"))
                    .withGatingCondition(new NetworkConnectivityCondition())
```
A Job can have at most one `ConcurrencyPolicy` and zero or more `GatingCondition`s.

### Gating Conditions

A [GatingCondition](https://tsombrero.github.io/jobwrangler/apidocs/com/serfshack/jobwrangler/core/GatingCondition.html) is a
way of temporarily benching a Job. The most obvious use case for this is network connectivity. Creating a GatingCondition is easy:

```java
    static class SimpleGatingCondition implements GatingCondition {
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
Just implement the interface and add your class to the `RunPolicy`.

### Concurrency Policy

A RunPolicy may have a [ConcurrencyPolicy](https://tsombrero.github.io/jobwrangler/apidocs/com/serfshack/jobwrangler/core/concurrencypolicy/AbstractConcurrencyPolicy.html) that governs how it coexists with other jobs with the same policy. This is how Singleton Jobs are enforced for example.

ConcurrencyPolicy objects are identified by keys. A key is a list of one or more Strings. When a Job is submitted, its ConcurrencyPolicy is compared with that of other running Jobs. If another Job has a ConcurrencyPolicy with the same key the jobs are said to collide and there's an opportunity to resolve the conflict.

There are three built-in `ConcurrencyPolicy`s:
##### SingletonPolicyKeepExisting
Only one job per matching policy may be running at a time. The running job has an opportunity to absorb any unique work from the duplicate job through its `assimilate()` method, and the duplicate job moves to a terminal `ASSIMILATED` state.
##### SingletonPolicyReplaceExisting
Similar to the previous policy but the roles are reversed. In this case the incoming Job survives and the existing one is terminated.
##### FIFOPolicy
Jobs with matching `FIFOPolicy`s run in order. Every earlier matching job must reach a terminal state (success or failure) before the next one can proceed.

## Job Dependencies

## Persistence

## State Flow
![State Flow Image](https://github.com/tsombrero/jobwrangler/blob/master/docs/res/Screenshot%202017-08-26%2013.01.33.png)

Documentation is forthcoming but check out the [JavaDoc](https://tsombrero.github.io/jobwrangler/apidocs/).
