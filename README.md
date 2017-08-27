# JobWrangler

JobWrangler is a job execution library along the lines of the [iOS OperationQueue](https://developer.apple.com/documentation/foundation/operationqueue) and the excellent [Android Priority JobQueue](https://github.com/yigit/android-priority-jobqueue). JobWrangler builds upon the concept of the Job-as-a-state-machine with dynamic inter-job dependency chains and other handy orchestration features. For example, Jobs get the following for free, or with minimal configuration:
- Limited retries and exponential backoff (see RunPolicy)
- Defer work until gating conditions are met (e.g. network availability)
- Dependency enforcement, strong or weak (see DependencyFailureStrategy)
- Singleton jobs (see ConcurrencyPolicy)
- Throttled jobs that accumulate batches of work
- Persisted jobs that survive application restart

With all those hairy details taken care of you can focus on the bits that must be explicitly implemented:

1. Update the local model as needed to reflect the action taking place (e.g. update the UI as if the Job has already succeeded)
1. Perform the actual work
1. Success/Failure handling

## Example: A Photo Sharing Service

Let's say you're implementing a client for photo sharing service. It's a very simple service consisting of photos organized into albums. You'll have a UI, some local storage, a way of communicating with the remote service. JobWrangler can help with the business logic to tie it all together. 

Let's start with a basic job to upload a photo. Jobs are generics that take the result type. The result of this job will be a URI to the photo, so this will be a `Job<URI>`.

### Upload Photo Job:

```java
    public static class UploadPhotoJob extends Job<URI> {
        private final String photo;

        public UploadPhotoJob(String photo) {
            this.photo = photo;
        }
        
        @Override
        public State onAdded() {
            // This callback happens immediately after the job has been successfully submitted
            // It's a good place to update the UI
            System.out.println("Uploading the photo");
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

That's about the bare minimum you need to create a job-- some implementation in `onAdded()` to update the application's state and `doWork()` to handle the heavy lifting. 

To execute the job, you would do something like this:

```java
// Note, in real life the JobManager should be a singleton
JobManager jobManager = new JobManager();
jobManager.submit(new UploadPhotoJob(thePhoto));
```
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

When a Job is initialized it always calls `configureRunPolicy()` once so it knows how to behave.
Here's an example of a customized RunPolicy:

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


a minute, what are `withConcurrencyPolicy(new FIFOPolicy("uploadphoto"))` and `withGatingCondition(new NetworkConnectivityCondition())` exactly?

### Concurrency Policy


## Gating Conditions


A RunPolicy can have one or more GatingConditions

## Job Dependencies

## Persistence

## State Flow
![State Flow Image](https://github.com/tsombrero/jobwrangler/blob/master/docs/res/Screenshot%202017-08-26%2013.01.33.png)

Documentation is forthcoming but check out the [JavaDoc](https://tsombrero.github.io/jobwrangler/apidocs/).
