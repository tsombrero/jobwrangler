# JobWrangler

JobWrangler is a job execution library along the lines of the [iOS OperationQueue](https://developer.apple.com/documentation/foundation/operationqueue) and the excellent [Android Priority JobQueue](https://github.com/yigit/android-priority-jobqueue). JobWrangler builds upon the concept of the Job-as-a-state-machine with dynamic inter-job dependency chains and other handy orchestration features. For example, Jobs get the following for free, or with minimal configuration:
- Delayed start until gating conditions are met (e.g. network availability)
- Limited retries and exponential backoff (see RunPolicy)
- Dependency enforcement, strong or weak (see DependencyFailureStrategy)
- Singleton jobs (see ConcurrencyPolicy)
- Throttled jobs that accumulate work
- Persisted jobs that survive application restart

That lets you focus on the bits that must be explicitly implemented:
- Update the local model as needed to reflect the action taking place (e.g. update the UI as if the Job has already succeeded)
- Perform the actual work
- Success/Failure handling

## Example: A Photo Sharing Service

Let's say you're implementing a client for photo sharing service. It's a very simple service consisting of photos organized into albums. You might start by defining three jobs:
- Upload a photo to storage
- Create a photo album
- Add a stored photo to a photo album

Let's start with a basic job to upload a photo:

### Upload Photo Job:

```java
    public static class Photo {
        Uri uri;
    }

    public static class UploadPhotoJob extends Job<Photo> {
        private final String photo;

        public UploadPhotoJob(String photo) {
            this.photo = photo;
        }
        
        @Override
        public State onAdded() {
            // Update the UI to indicate we're uploading
        }

        @Override
        public State doWork() {
            // Potentially long-running network operation, adding the photo to storage
            Photo photo = serviceApi.uploadPhoto(this.photo);

            if (photo != null) {
                // Success!
                setResult(photo);
                return State.SUCCEEDED;
            }
            
            // We did not succeed this time but willing to give it another go.
            return State.READY;
        }
    }
```



### Create Album Job:
```java    
    public static class PhotoAlbum {
        Uri photoAlbumUri;
        final String photoAlbumName;
        final List<Photo> photoList = new ArrayList<>();
    }


```

## JobObserver

## Job Dependencies

## Run Policy

## Gating Conditions

## Concurrency Policy

## Persistence

## State Flow
![State Flow Image](https://github.com/tsombrero/jobwrangler/blob/master/docs/res/Screenshot%202017-08-26%2013.01.33.png)

Documentation is forthcoming but check out the [JavaDoc](https://tsombrero.github.io/jobwrangler/apidocs/).
