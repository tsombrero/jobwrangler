package com.serfshack.jobwrangler.photoservice;

import com.serfshack.jobwrangler.core.TestUtil;
import com.serfshack.jobwrangler.photoservice.PhotoServiceModel.Photo;
import com.serfshack.jobwrangler.photoservice.PhotoServiceModel.PhotoAlbum;
import com.serfshack.jobwrangler.util.Log;

import java.net.URI;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


public class MockPhotoService {
    public static int SLOWDOWN_FACTOR = 0;

    private ConcurrentHashMap<URI, Photo> photoMap = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, PhotoAlbum> photoAlbumMap = new ConcurrentHashMap<>();

    private static MockPhotoService instance = new MockPhotoService();

    public static MockPhotoService getInstance() {
        return instance;
    }

    public void clear() {
        photoMap.clear();
        photoAlbumMap.clear();
    }

    public PhotoServiceModel.UserToken newSessionToken() {
        startApiCall("newSessionToken", 2);
        return new PhotoServiceModel.UserToken();
    }

    public PhotoAlbum putAlbum(PhotoServiceModel.UserToken token, String name) throws UserTokenException {
        startApiCall("putAlbum " + name, 1, token);
        synchronized (this) {
            PhotoAlbum ret = photoAlbumMap.get(name);
            if (ret == null) {
                ret = new PhotoAlbum(URI.create("https://example.com/albums/" + UUID.randomUUID()), name);
                photoAlbumMap.put(ret.name(), ret);
            }
            return ret;
        }
    }

    public Photo uploadPhoto(PhotoServiceModel.UserToken token, String name) throws UserTokenException, AlbumNotFoundException {
        startApiCall("API: uploadPhoto " + name, 3, token);
        synchronized (this) {
            Photo photo = new Photo(URI.create("https://example.com/photos/" + UUID.randomUUID()), name);
            photoMap.put(photo.uri, photo);
            return photo;
        }
    }

    public void addPhotoToAlbum(PhotoServiceModel.UserToken token, URI photoUri, String albumName) throws AlbumNotFoundException, PhotoNotFoundException, UserTokenException {
        startApiCall("addPhotoToAlbum " + photoUri + "," + albumName, 1, token);
        synchronized(this) {
            PhotoAlbum album = lookupPhotoAlbum(albumName);
            Photo photo = lookupPhoto(photoUri);
            album.add(photo);
        }
    }

    public Photo addCommentToPhoto(PhotoServiceModel.UserToken token, URI photoUri, String comment) throws UserTokenException, PhotoNotFoundException {
        startApiCall("addCommentToPhoto " + photoUri + " " + comment, 3, token);
        synchronized (this) {
            Photo photo = lookupPhoto(photoUri);
            photo.comments.add(comment);
            return photo;
        }
    }

    public Photo downloadPhoto(PhotoServiceModel.UserToken token, URI photoUri) throws UserTokenException, PhotoNotFoundException {
        startApiCall("downloadPhoto " + photoUri, 3, token);

        return lookupPhoto(photoUri);
    }

    public void writeAuditLog(PhotoServiceModel.UserToken token, String auditlog) throws UserTokenException {
        synchronized (this) {
            startApiCall("writeAuditLog ", 3, token);
            Log.d("audit log: " + auditlog.split("\r").length + " lines");
        }
    }

    private void startApiCall(String logStr, int slowdown) {
        Log.d("API: " + logStr);
        TestUtil.sleep(slowdown * SLOWDOWN_FACTOR);
    }

    private void startApiCall(String logStr, int slowdown, PhotoServiceModel.UserToken token) throws UserTokenException {
        startApiCall(logStr, slowdown);
        if (token == null)
            throw new UserTokenException();
        token.validate();
    }

    private PhotoAlbum lookupPhotoAlbum(String albumName) throws AlbumNotFoundException {
        PhotoAlbum album = photoAlbumMap.get(albumName);
        if (album == null)
            throw new AlbumNotFoundException();
        return album;
    }

    private Photo lookupPhoto(URI photoUri) throws PhotoNotFoundException {
        Photo ret = photoMap.get(photoUri);
        if (ret == null)
            throw new PhotoNotFoundException(photoUri);
        return ret;
    }

    public int getAlbumCount() {
        return photoAlbumMap.size();
    }

    public Collection<PhotoAlbum> getAlbums() {
        return photoAlbumMap.values();
    }

    public static class UserTokenException extends Exception {
    }

    public static class PhotoNotFoundException extends Exception {
        public PhotoNotFoundException(URI photoUri) {
            super(photoUri + " not found");
        }
    }

    public static class AlbumNotFoundException extends Exception {
    }
}
