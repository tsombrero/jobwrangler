package com.serfshack.jobwrangler.photoservice;


import com.serfshack.jobwrangler.core.*;
import com.serfshack.jobwrangler.core.concurrencypolicy.FIFOPolicy;
import com.serfshack.jobwrangler.core.concurrencypolicy.SingletonPolicyKeepExisting;
import com.serfshack.jobwrangler.util.Log;

import java.security.SecureRandom;

public class PhotoServiceClientJobs {

    public static MockPhotoService mockService = MockPhotoService.getInstance();
    public static float FLAKE_FACTOR = 0f;
    private static TokenProvider tokenProvider;

    private static SecureRandom rnd = new SecureRandom();

    static void flake(Dependable dependable) {
        if (FLAKE_FACTOR > 0f && rnd.nextFloat() < FLAKE_FACTOR) {
            throw new RuntimeException("Job Flaked! " + dependable);
        }
    }

    private static PhotoServiceModel.UserToken getToken(Job job) {
        if (tokenProvider == null)
            tokenProvider = new TokenProvider(job.getJobManager());

        return tokenProvider.getToken();
    }

    public static class CreatePhotoAlbumJob extends Job<PhotoServiceModel.PhotoAlbum> {

        public String albumname;

        public CreatePhotoAlbumJob(String albumname) {
            this.albumname = albumname;
        }

        @Override
        protected RunPolicy configureRunPolicy() {
            return RunPolicy.newLimitAttemptsPolicy(20)
                    .withConcurrencyPolicy(new FIFOPolicy("CreatePhotoAlbumJob", albumname))
                    .build();
        }

        @Override
        public State onAdded() {
            flake(this);

            // If the album already has a URI there's no work to do
            if (MockPhotoAppUi.getInstance().putAlbum(albumname).getPhotoAlbumUri() != null)
                return State.SUCCEEDED;
            return State.READY;
        }

        @Override
        public State onPrepare() {
            if (getToken(this) == null) {
                Log.d(this + " paused pending token");
                return State.WAIT;
            }
            return super.onPrepare();
        }

        @Override
        public State doWork() {
            flake(this);

            try {
                PhotoServiceModel.PhotoAlbum remoteAlbum = mockService.putAlbum(getToken(this), albumname);
                if (remoteAlbum != null) {
                    PhotoServiceModel.PhotoAlbum localAlbum = MockPhotoAppUi.getInstance().putAlbum(albumname);
                    localAlbum.setPhotoAlbumUri(remoteAlbum.getPhotoAlbumUri());
                    setResult(localAlbum);
                    return State.SUCCEEDED;
                }
            } catch (MockPhotoService.UserTokenException e) {
                Log.e("UserToken is invalid, will retry " + this);
            }
            return State.READY;
        }

        @Override
        protected void rollback() {
            Log.i("Rolling back " + this);
            if (MockPhotoAppUi.getInstance().removeAlbum(albumname) == null)
                throw new RuntimeException("Failed removing album from ui on rollback " + albumname);
        }

        @Override
        public String toString() {
            return super.toString() + " " + albumname;
        }
    }

    public static class UploadPhotoJob extends Job<PhotoServiceModel.Photo> {

        private final String photo;

        public UploadPhotoJob(String photo) {
            this.photo = photo;
        }

        @Override
        protected RunPolicy configureRunPolicy() {
            return RunPolicy.newLimitAttemptsPolicy().build();
        }

        @Override
        public State onPrepare() {
            if (getToken(this) == null) {
                Log.d(this + " paused pending token");
                return State.WAIT;
            }
            return super.onPrepare();
        }

        @Override
        public State doWork() {
            flake(this);

            try {
                PhotoServiceModel.Photo photo = mockService.uploadPhoto(getToken(this), this.photo);
                setResult(photo);
                return State.SUCCEEDED;
            } catch (MockPhotoService.UserTokenException e) {
                Log.e("UserToken is invalid, will retry " + this);
                return State.READY;
            } catch (MockPhotoService.AlbumNotFoundException e) {
                e.printStackTrace();
                return State.FAULTED;
            }
        }

        @Override
        public String toString() {
            return super.toString() + " " + photo;
        }
    }

    abstract static class PhotoAlbumDependingJob extends SimpleJob {
        private String albumName;
        private PhotoServiceModel.PhotoAlbum photoAlbum;

        PhotoAlbumDependingJob(String albumName) {
            this.albumName = albumName;
        }

        @Override
        public State onAdded() {
            flake(this);

            photoAlbum = MockPhotoAppUi.getInstance().putAlbum(albumName);
            return State.READY;
        }

        @Override
        public State onPrepare() {
            flake(this);

            if (photoAlbum.getPhotoAlbumUri() == null) {
                Log.d(" pending photoalbum create");
                addDepended(getJobManager().submit(new CreatePhotoAlbumJob(albumName)).getJob());
                return State.WAIT;
            }

            if (getToken(this) == null) {
                Log.d(" pending token");
                addDepended(tokenProvider.getTokenjobObserver.getJob());
                return State.WAIT;
            }

            return State.READY;
        }

        String getAlbumName() {
            return albumName;
        }

        PhotoServiceModel.PhotoAlbum getPhotoAlbum() {
            return photoAlbum;
        }
    }

    public static class AddPhotoToAlbumJob extends PhotoAlbumDependingJob {

        JobObserver uploadPhotoJobObserver;
        PhotoServiceModel.Photo addedPhoto;

        public AddPhotoToAlbumJob(String photo, String albumName) {
            super(albumName);
            addedPhoto = new PhotoServiceModel.Photo(null, photo);
        }

        @Override
        protected RunPolicy configureRunPolicy() {
            return RunPolicy.newLimitAttemptsPolicy().build();
        }

        @Override
        public State onAdded() {
            super.onAdded();
            if (getPhotoAlbum() == null)
                return State.FAULTED;

            getPhotoAlbum().add(addedPhoto);
            uploadPhotoJobObserver = getJobManager().submit(new UploadPhotoJob(addedPhoto.name));
            addDepended(uploadPhotoJobObserver.getJob());
            return State.READY;
        }

        @Override
        public State doWork() {
            try {
                PhotoServiceModel.Photo uploadedPhoto = (PhotoServiceModel.Photo) uploadPhotoJobObserver.getResult();
                mockService.addPhotoToAlbum(getToken(this), uploadedPhoto.uri, getAlbumName());
                addedPhoto.uri = uploadedPhoto.uri;
                return State.SUCCEEDED;
            } catch (MockPhotoService.AlbumNotFoundException | MockPhotoService.PhotoNotFoundException e) {
                e.printStackTrace();
            } catch (MockPhotoService.UserTokenException e) {
                Log.e("UserToken is invalid, will retry " + this);
            }
            return State.READY;
        }

        @Override
        protected void rollback() {
            Log.i("Rolling back " + this);
            if (getPhotoAlbum() != null)
                getPhotoAlbum().remove(addedPhoto);
        }

        @Override
        public String toString() {
            return super.toString() + " " + getAlbumName() + "/" + (uploadPhotoJobObserver == null ? "null" : uploadPhotoJobObserver.getResult());
        }
    }

    public static class TokenProvider {
        PhotoServiceModel.UserToken token;
        private JobManager jobManager;
        private JobObserver<PhotoServiceModel.UserToken> getTokenjobObserver;

        TokenProvider(JobManager jobManager) {
            this.jobManager = jobManager;
        }

        public synchronized PhotoServiceModel.UserToken getToken() {
            JobObserver<PhotoServiceModel.UserToken> observer = getTokenjobObserver;
            if (observer != null && observer.getResult() != null) {
                token = observer.getResult();
                getTokenjobObserver = null;
            }

            if (token == null && getTokenjobObserver == null) {
                submitJob();
            }

            if (token != null && token.expiresSoon() && getTokenjobObserver == null) {
                submitJob();
            }

            return token;
        }

        @SuppressWarnings(value="unchecked")
        private void submitJob() {
            getTokenjobObserver = jobManager.submit(new GetTokenJob());
        }
    }

    public static class GetTokenJob extends Job<PhotoServiceModel.UserToken> {

        public GetTokenJob() {
            super();
        }

        @Override
        protected RunPolicy configureRunPolicy() {
            return RunPolicy.newLimitAttemptsPolicy(20)
                    .withConcurrencyPolicy(new SingletonPolicyKeepExisting("GetTokenJob"))
                    .build();
        }

        @Override
        public State doWork() {
            flake(this);
            PhotoServiceModel.UserToken token = mockService.newSessionToken();
            setResult(token);
            return State.SUCCEEDED;
        }
    }
}
