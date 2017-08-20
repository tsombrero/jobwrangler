package com.serfshack.jobwrangler.core;

import com.serfshack.jobwrangler.photoservice.MockPhotoService;
import com.serfshack.jobwrangler.photoservice.MockPhotoAppUi;
import com.serfshack.jobwrangler.photoservice.PhotoServiceModel;
import com.serfshack.jobwrangler.util.Log;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.serfshack.jobwrangler.photoservice.PhotoServiceClientJobs.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PhotoServiceTest {

    private JobManager jobManager;
    private MockPhotoAppUi mockPhotoAppUi = MockPhotoAppUi.getInstance();
    private MockPhotoService mockService = MockPhotoService.getInstance();

    @Before
    public void init() {
        jobManager = new JobManager(null);
        mockPhotoAppUi.clear();
        mockService.clear();
        FLAKE_FACTOR = 0f;
        ServiceJobTask.DEFAULT_POLL_INTERVAL = 200;
        MockPhotoService.SLOWDOWN_FACTOR = 0;
    }

    @Test
    public void testCreateNewAlbum() {
        ServiceJobTask.DEFAULT_POLL_INTERVAL = 20;
        JobObserver<PhotoServiceModel.PhotoAlbum> job =
                jobManager.submit(new CreatePhotoAlbumJob("foo"));
        job.waitForTerminalState(100, TimeUnit.SECONDS);

        assertEquals(job.getResult().name(), "foo");
        assertNotNull(mockPhotoAppUi.getAlbum("foo"));
        URI albumUri = mockPhotoAppUi.getAlbum("foo").getPhotoAlbumUri();
        assertNotNull(albumUri);

        JobObserver job2 =
                jobManager.submit(new CreatePhotoAlbumJob("foo"));
        job2.waitForTerminalState(100, TimeUnit.MILLISECONDS);
        assertEquals(albumUri, mockPhotoAppUi.getAlbum("foo").getPhotoAlbumUri());
        assertServiceAndUiCompare();
    }

    @Test
    public void testUploadPhoto() {
        JobObserver<PhotoServiceModel.Photo> jobObserver = jobManager.submit(new UploadPhotoJob("foto"));
        PhotoServiceModel.Photo photo = jobObserver.getResultBlocking(TestUtil.IS_DEBUGGING ? 999999 : 5, TimeUnit.SECONDS);
        Assert.assertEquals(State.SUCCEEDED, jobObserver.getState());
        assertNotNull(photo.uri);
        assertEquals(photo.name, "foto");
        assertServiceAndUiCompare();
    }

    @Test
    public void testUploadPhotoToNewAlbum() {
        JobObserver job =
                jobManager.submit(new UploadPhotoToAlbumJob("foto", "foo"));

        Assert.assertEquals(State.SUCCEEDED, job.waitForTerminalState(5000, TimeUnit.MILLISECONDS));
        assertServiceAndUiCompare();
    }

    @Test
    public void testUploadManyPhotosToManyAlbums1() {
        uploadManyPhotosToManyAlbums(1, 1);
    }

    @Test
    public void testUploadManyPhotosToManyAlbums50slow() {
        MockPhotoService.SLOWDOWN_FACTOR = 1000;
        PhotoServiceModel.TOKEN_USES = 20;
        uploadManyPhotosToManyAlbums(5, 10);
    }

    @Test
    public void testUploadManyPhotosToManyAlbums50fast() {
        MockPhotoService.SLOWDOWN_FACTOR = 0;
        PhotoServiceModel.TOKEN_USES = 100;
        uploadManyPhotosToManyAlbums(5, 10);
    }

    @Test
    public void testUploadManyPhotosToManyAlbums500() {
        MockPhotoService.SLOWDOWN_FACTOR = 100;
        PhotoServiceModel.TOKEN_USES = 100;
        uploadManyPhotosToManyAlbums(50, 10);
    }

    @Test
    public void testUploadManyPhotosToManyAlbums10000() {
        MockPhotoService.SLOWDOWN_FACTOR = 0;
        PhotoServiceModel.TOKEN_USES = 10000;
        uploadManyPhotosToManyAlbums(100, 100);
    }


    public void uploadManyPhotosToManyAlbums(int numAlbums, int photosPerAlbum) {
        int photoCount = numAlbums * photosPerAlbum;

        List<JobObserver> jobList = new ArrayList<>();

        for (int i = 0; i < photoCount; i++) {
            jobList.add(jobManager.submit(new UploadPhotoToAlbumJob("pic:" + i, "album:" + (i % numAlbums))));
        }

        for (JobObserver job : jobList) {
            assertEquals("Job didn't succeed: " + job, State.SUCCEEDED, job.waitForTerminalState(Math.max(500, numAlbums * photosPerAlbum * 10) + (TestUtil.IS_DEBUGGING ? 999999 : 0), TimeUnit.MILLISECONDS));
        }

        // Make sure the mock service and the mock ui have the same data
        Assert.assertEquals(numAlbums, mockService.getAlbumCount());
        assertServiceAndUiCompare();
    }

    @Test
    public void testFlakyJobs() {
        int photoCount = 100;
        int numAlbums = 50;

        FLAKE_FACTOR = 0.01f;

        List<JobObserver> jobList = new ArrayList<>();

        for (int i = 0; i < photoCount; i++) {
            jobList.add(jobManager.submit(new UploadPhotoToAlbumJob("pic:" + i, "album:" + (i % numAlbums))));
        }

        int succeeded = 0;
        for (JobObserver job : jobList) {
            if (State.SUCCEEDED == job.waitForTerminalState(500 + (TestUtil.IS_DEBUGGING ? 999999 : 0), TimeUnit.MILLISECONDS)) {
                succeeded++;
            } else {
                Log.i(job + " flaked");
            }
        }

        Log.i(succeeded + " of " + photoCount + " jobs succeeded");

        assertTrue(succeeded > (photoCount / 10));
        assertTrue(succeeded < photoCount);

        assertServiceAndUiCompare();
    }

    private void assertServiceAndUiCompare() {
        int countValidLocalAlbums = 0;
        for (PhotoServiceModel.PhotoAlbum album : mockService.getAlbums()) {
            if (album.getPhotoAlbumUri() != null)
                countValidLocalAlbums++;
        }

        // Make sure the mock service and the mock ui have the same data
        Assert.assertEquals("UI and Service album counts differ", countValidLocalAlbums, mockService.getAlbumCount());
        for (PhotoServiceModel.PhotoAlbum servicealbum : mockService.getAlbums()) {
            PhotoServiceModel.PhotoAlbum uialbum = mockPhotoAppUi.getAlbum(servicealbum.name());
            assertNotNull("UI is missing album " + servicealbum.name(), uialbum);
            for (PhotoServiceModel.Photo photo : uialbum.getPhotos()) {
                assertTrue("Service is missing photo " + servicealbum + "/" + photo, servicealbum.getPhotos().contains(photo));
            }
            for (PhotoServiceModel.Photo photo : servicealbum.getPhotos()) {
                assertTrue("UI is missing photo " + servicealbum + "/" + photo, uialbum.getPhotos().contains(photo));
            }
            assertEquals(servicealbum.getPhotoCount(), uialbum.getPhotoCount());
        }
    }
}
