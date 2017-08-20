package com.serfshack.jobwrangler.photoservice;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MockPhotoAppUi {
    public Map<String, PhotoServiceModel.PhotoAlbum> photoAlbums = new ConcurrentHashMap<>();

    static MockPhotoAppUi instance = new MockPhotoAppUi();

    public static MockPhotoAppUi getInstance() {
        return instance;
    }

    public synchronized void clear() {
        photoAlbums.clear();
    }

    public synchronized PhotoServiceModel.PhotoAlbum putAlbum(URI remoteUri, String albumName) {
        PhotoServiceModel.PhotoAlbum ret = getAlbum(albumName);

        if (ret == null) {
            PhotoServiceModel.PhotoAlbum newAlbum = new PhotoServiceModel.PhotoAlbum(remoteUri, albumName);
            photoAlbums.put(albumName, newAlbum);
            return newAlbum;
        }

        if (remoteUri != null) {
            ret.setPhotoAlbumUri(remoteUri);
        }

        return ret;
    }

    public synchronized PhotoServiceModel.PhotoAlbum putAlbum(String albumName) {
        return putAlbum(null, albumName);
    }

    public synchronized PhotoServiceModel.PhotoAlbum removeAlbum(String albumName) {
        return photoAlbums.remove(albumName);
    }

    public synchronized PhotoServiceModel.PhotoAlbum getAlbum(String name) {
        return photoAlbums.get(name);
    }

    public synchronized int getAlbumCount() {
        return photoAlbums.size();
    }

    public synchronized int getPhotoCount() {
        int ret = 0;
        for (PhotoServiceModel.PhotoAlbum album : photoAlbums.values()) {
            ret += album.getPhotos().size();
        }
        return ret;
    }
}
