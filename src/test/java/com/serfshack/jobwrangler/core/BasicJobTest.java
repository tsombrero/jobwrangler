package com.serfshack.jobwrangler.core;

import com.serfshack.jobwrangler.photoservice.PhotoServiceClientJobs.CreatePhotoAlbumJob;
import com.serfshack.jobwrangler.util.Log;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.concurrent.*;

import static com.serfshack.jobwrangler.core.TestUtil.isImmediateFuture;
import static org.junit.Assert.*;

public class BasicJobTest {

    private JobManager jobManager;

    static class WaitingTestJob extends SimpleJob {

        private final String name;
        boolean canSucceed;
        boolean isRemovable = true;

        WaitingTestJob() {
            this(null);
        }

        WaitingTestJob(String name) {
            this.name = name;
        }

        @Override
        public State onPrepare() {
            return canSucceed ? State.READY : State.WAIT;
        }

        @Override
        public State doWork() {
            return State.SUCCEEDED;
        }

        @Override
        public String toString() {
            return super.toString() + (name == null ? "" : " [" + name + "]");
        }

        @Override
        protected boolean isRemovable() {
            return super.isRemovable() && isRemovable;
        }
    }

    static class EasyJob extends SimpleJob {

        @Override
        public State onPrepare() {
            return State.READY;
        }

        @Override
        public State doWork() {
            return State.SUCCEEDED;
        }
    }

    @Before
    public void init() {
        jobManager = new JobManager(null);
        ServiceJobTask.DEFAULT_POLL_INTERVAL = 1;
    }

    @Test
    public void testInitJob() throws Exception {
        Job job = new EasyJob();
        try {
            job.init(null);
            fail();
        } catch (Exception e) {
        }

        job.init(jobManager);
        assertNotNull(job.getJobManager());
        try {
            job.init(new JobManager(null));
            fail();
        } catch (Exception e) {
        }
    }

    @Test
    public void testIsRemovable() {
        Job job = new CreatePhotoAlbumJob("foo");
        job.init(jobManager);
        for (State state : State.values()) {
            if (state == State.NEW) {
                assertFalse(job.isRemovable());
                continue;
            }

            // test this last since the job can't leave the canceled state
            if (state == State.CANCELED)
                continue;

            TestUtil.setJobState(state, job);

            switch (state) {
                case WAIT:
                case READY:
                case BUSY:
                    assertFalse(job.isRemovable());
                    break;
                case SUCCEEDED:
                case FAULTED:
                case ASSIMILATED:
                    assertTrue(job.isRemovable());
                    break;
            }
        }
        TestUtil.setJobState(State.CANCELED, job);
        assertTrue(job.isRemovable());
    }

    @Test
    public void testIsRemovable2() {
        WaitingTestJob dependedJob = new WaitingTestJob("depended");
        WaitingTestJob dependingJob = new WaitingTestJob("depending");
        dependingJob.addDepended(dependedJob);
        jobManager.submit(dependedJob);
        jobManager.submit(dependingJob);
        assertFalse(dependedJob + " should not be removable", dependedJob.isRemovable());
        assertFalse(dependingJob + " should not be removable", dependingJob.isRemovable());

        // The depended job is not removable as long as a depending job is not removable
        for (State state : new State[]{State.WAIT, State.BUSY, State.READY}) {
            TestUtil.setJobState(state, dependingJob);
            assertEquals(state, dependingJob.getState());
            assertFalse(dependedJob + " should not be removable", dependedJob.isRemovable());
        }

        TestUtil.setJobState(State.FAULTED, dependedJob);
        assertEquals(State.FAULTED, dependingJob.getState());
        assertTrue(dependingJob + " should be removable", dependingJob.isRemovable());
        assertTrue(dependedJob + " should be removable", dependedJob.isRemovable());

        dependedJob.setState(State.SUCCEEDED);
        dependingJob.setState(State.SUCCEEDED);

        assertTrue(dependedJob.isRemovable());
        assertTrue(dependingJob.isRemovable());
    }

    @Test
    public void testDoPrepareWithDependedJob() {
        JobObserver dependedJob = jobManager.submit(new WaitingTestJob("depended"));

        Job dependingJob = new EasyJob();
        dependingJob.addDepended(dependedJob.getJob());

        JobObserver dependingJobObserver = jobManager.submit(dependingJob);
        TestUtil.waitUntilAdded(dependingJobObserver);

        assertEquals(State.WAIT, dependingJob.doPrepare());
    }

    @Test
    public void testDependedJobMissing() {
        final Job dependedJob = new WaitingTestJob("depended");
        Job dependingJob = new EasyJob() {
            @Override
            public State onAdded() {
                addDepended(dependedJob);
                return super.onAdded();
            }
        };

        // depended job not submitted, doPrepare should ignore it happily
        JobObserver observer = jobManager.submit(dependingJob);
        TestUtil.waitUntilCompleted(observer);
        assertEquals(State.FAULTED, dependingJob.doPrepare());
    }

    @Test
    public void testSetState() {
        final Job job = new WaitingTestJob();
        job.init(jobManager);
        try {
            job.setState(null);
            fail();
        } catch (IllegalArgumentException e) {
        }

        try {
            job.setState(State.NEW);
            fail();
        } catch (IllegalArgumentException e) {
        }

        Future<Boolean> didRunSynchronously = jobManager.getScheduledExecutor().submit(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                Future<State> future = job.setState(State.READY);
                return isImmediateFuture(future) && future.isDone();
            }
        });

        try {
            assertTrue(didRunSynchronously.get());
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
            fail();
        }

        assertEquals(State.READY, job.getState());

        Future<State> future = job.setState(State.WAIT);
        assertFalse(isImmediateFuture(future));

        TestUtil.assertFutureState(State.WAIT, future);

        assertEquals(State.WAIT, job.getState());
    }

    @Test
    public void testCancel() {
        Job job = jobManager.submit(new WaitingTestJob()).getJob();
        TestUtil.assertFutureState(State.CANCELED, job.cancel());
        assertEquals(null, job.getStateMessage());
        Log.i("This should throw an IllegalStateException:");
        TestUtil.assertFutureState(State.CANCELED, job.setState(State.READY));
        assertEquals(null, job.getStateMessage());
        assertTrue(job.isCanceled());
    }

    @Test
    public void testOnStateChanged() {
        for (State endState : new State[]{State.SUCCEEDED, State.FAULTED, State.CANCELED}) {
            final ArrayList<State> states = new ArrayList<>();
            EasyJob job = new EasyJob() {
                @Override
                protected void onStateChanged(State oldState) {
                    super.onStateChanged(oldState);
                    states.add(getState());
                }
            };
            job.init(jobManager);

            int statesSize = 0;
            for (State state : new State[]{State.WAIT, State.READY, State.WAIT, State.READY, State.BUSY}) {
                State oldState = job.getState();
                if (!states.isEmpty())
                    assertEquals(oldState, states.get(states.size() - 1));

                TestUtil.assertFutureState(state, job.setState(state));

                if (oldState != state)
                    assertEquals("onStateChanged didn't execute for state " + state, ++statesSize, states.size());
            }

            assertFalse(states.isEmpty());
            TestUtil.setJobState(endState, job);
            assertEquals(++statesSize, states.size());
            assertEquals(endState, states.get(statesSize - 1));
        }
    }

    @Test
    public void testWaitUntilAdded() {
        for (int i = 0; i < 50; i++) {
            JobObserver jobObserver = jobManager.submit(new WaitingTestJob());
            assertEquals(State.WAIT, jobObserver.waitUntilAdded(5000, TimeUnit.MILLISECONDS));
        }

        for (int i = 0; i < 10; i++) {
            JobObserver job = jobManager.submit(new SimpleJob() {
                @Override
                public State onAdded() {
                    TestUtil.sleep(100);
                    return State.WAIT;
                }

                @Override
                public State doWork() {
                    return State.SUCCEEDED;
                }
            });

            assertEquals(State.NEW, job.getState());
            assertEquals(State.NEW, job.waitUntilAdded(20, TimeUnit.MILLISECONDS));
            assertNotEquals(State.NEW, job.waitUntilAdded(1000, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    public void testWaitForTerminalState() {
        for (int i = 0; i < 100; i++) {
            JobObserver job = jobManager.submit(new EasyJob());
            assertEquals(State.SUCCEEDED, job.waitForTerminalState(500, TimeUnit.MILLISECONDS));
        }

        JobObserver job = jobManager.submit(new SimpleJob() {
            @Override
            public State doWork() {
                TestUtil.sleep(100);
                return State.SUCCEEDED;
            }
        });

        assertNotEquals(State.SUCCEEDED, job.waitForTerminalState(20, TimeUnit.MILLISECONDS));
        assertEquals(State.SUCCEEDED, job.waitForTerminalState(500, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testGetSetRunPolicy() {
        Job job1 = new EasyJob();
        Job job2 = new SimpleJob() {
            @Override
            public State doWork() {
                return State.SUCCEEDED;
            }
        };

        assertEquals(job1.configureRunPolicy().getAttemptsRemaining(), job2.configureRunPolicy().getAttemptsRemaining());

        // make sure we can't assign the same RunPolicy instance to multiple jobs
        RunPolicy newRunPolicy = RunPolicy.newLimitAttemptsPolicy().build();
        job1.setRunPolicy(newRunPolicy);
        try {
            job2.setRunPolicy(newRunPolicy);
            fail();
        } catch (IllegalStateException e) {
        }
    }

    @Test
    public void testIsDirty() {
        Job job = new WaitingTestJob();
        job.init(jobManager);
        assertEquals(State.NEW, job.getState());
        assertFalse(job.isDirty());
        TestUtil.setJobState(State.WAIT, job);
        assertTrue(job.isDirty());

        job.setDirty(false);
        TestUtil.setJobState(State.WAIT, job);
        assertFalse(job.isDirty());

        //transitions between READY/WAIT/BUSY don't set isDirty; persisted READY/BUSY jobs revert to WAIT anyway
        TestUtil.setJobState(State.READY, job);
        assertFalse(job.isDirty());
        TestUtil.setJobState(State.WAIT, job);
        assertFalse(job.isDirty());
        TestUtil.setJobState(State.BUSY, job);
        assertFalse(job.isDirty());
        TestUtil.setJobState(State.SUCCEEDED, job);
        assertTrue(job.isDirty());

        job.setDirty(false);
        TestUtil.setJobState(State.SUCCEEDED, job);
        assertFalse(job.isDirty());
        TestUtil.setJobState(State.FAULTED, job);
        assertTrue(job.isDirty());

        job.setDirty(false);
        TestUtil.setJobState(State.ASSIMILATED, job);
        assertTrue(job.isDirty());

        job.setDirty(false);
        TestUtil.setJobState(State.CANCELED, job);
        assertTrue(job.isDirty());
    }

    @Test
    public void testDoAdd() {
        JobObserver job = jobManager.submit(new SimpleJob() {
            @Override
            public State onAdded() {
                return State.NEW;
            }

            @Override
            public State doWork() {
                return State.SUCCEEDED;
            }
        });

        assertEquals(State.FAULTED, job.waitForTerminalState(1000, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testSetAssimilatedBy() {
        Job job1 = new WaitingTestJob();
        Job job2 = new WaitingTestJob();
        job1.setAssimilatedBy(job2);

        assertEquals(State.ASSIMILATED, job1.getState());

        assertEquals(job1.getAssimilatedBy(), job2);
    }


    void testOnAdded() {

    }

    void testOnNewJobAdded() {

    }

    void testCheckProgress() {

    }

    void testDoInBackground() {

    }

}