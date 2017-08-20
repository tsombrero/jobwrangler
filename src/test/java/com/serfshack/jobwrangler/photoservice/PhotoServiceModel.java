package com.serfshack.jobwrangler.photoservice;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class PhotoServiceModel {
    public static class PhotoAlbum {
        private URI photoAlbumUri;
        private final String name;
        private final List<Photo> photoList = new ArrayList<>();

        public PhotoAlbum(URI uri, String name) {
            this.name = name;
            this.photoAlbumUri = uri;
        }

        @Override
        public String toString() {
            return name;
        }

        public void setPhotoAlbumUri(URI uri) {
            photoAlbumUri = uri;
        }

        public URI getPhotoAlbumUri() {
            return photoAlbumUri;
        }

        public int getPhotoCount() {
            return photoList.size();
        }

        public String name() {
            return name;
        }

        public List<Photo> getPhotos() {
            return photoList;
        }

        public void remove(Photo addedPhoto) {
            photoList.remove(addedPhoto);
        }

        public void add(Photo addedPhoto) {
            photoList.add(addedPhoto);
        }
    }

    public static class Photo {
        public URI uri;
        public String name;
        List<String> comments = new ArrayList<>();

        Photo(URI uri, String name) {
            this.uri = uri;
            this.name = name;
        }

        @Override
        public String toString() {
            return name + ";" + uri;
        }

        @Override
        public int hashCode() {
            return toString().hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return (obj instanceof Photo)
                    && name.equals(((Photo)obj).name);
        }
    }

    public static long TOKEN_USES = 10;

    public static class UserToken {
        public long usesLeft = TOKEN_USES;
        private boolean r;

        public void validate() throws MockPhotoService.UserTokenException {
            if (--usesLeft <= 0)
                throw new MockPhotoService.UserTokenException();
        }

        public boolean expiresSoon() {
            return usesLeft < (TOKEN_USES / 5);
        }

        public boolean isExpired() {
            return usesLeft <= 0;
        }
    }
}
