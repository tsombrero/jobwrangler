package com.serfshack.jobwrangler.core;

import com.serfshack.jobwrangler.core.concurrencypolicy.SingletonPolicyKeepExisting;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.serfshack.jobwrangler.core.State.ASSIMILATED;
import static com.serfshack.jobwrangler.core.State.SUCCEEDED;
import static com.serfshack.jobwrangler.core.State.WAIT;
import static com.serfshack.jobwrangler.core.TestUtil.assertTimedCondition;
import static org.junit.Assert.*;

public class JobManagerTest {

    private JobManager jobManager;

    @Before
    public void init() {
        jobManager = new JobManager(null);
        ServiceJobTask.DEFAULT_POLL_INTERVAL = 50;
    }

    @Test
    public void testSubmit() {
        SimpleJob job1 = new SimpleJob() {
            @Override
            public State doWork() {
                return State.SUCCEEDED;
            }
        };
        assertEquals(job1.getId(), jobManager.submit(job1).getId());

        SimpleJob job2 = new BasicJobTest.WaitingTestJob();
        SimpleJob job3 = new BasicJobTest.WaitingTestJob();

        job2.addDepended(job3);
        try {
            jobManager.submit(job2);
            fail("that wasn't supposed to work");
        } catch (Dependable.DependencyException e) {
        }

        assertEquals(State.NEW, job2.getState());

        jobManager.submit(job3);
        jobManager.submit(job2);

        try {
            job2.addDepended(new BasicJobTest.WaitingTestJob());
            fail("that wasn't supposed to work");
        } catch (Dependable.DependencyException e) {
        }
    }

    interface WorkData {
        ArrayList<String> getWorkData();
    }

    static ArrayList<String> completedWork = new ArrayList<>();

    static class SingletonWaitingJob extends BasicJobTest.WaitingTestJob implements WorkData {
        private String key;

        public ArrayList<String> workdata = new ArrayList<>();

        SingletonWaitingJob(String key) {
            super("singleton");
            this.key = key;
        }

        @Override
        protected RunPolicy configureRunPolicy() {
            return RunPolicy.newLimitAttemptsPolicy()
                    .withConcurrencyPolicy(new SingletonPolicyKeepExisting(key))
                    .build();
        }

        @Override
        public State doWork() {
            completedWork.addAll(workdata);
            return SUCCEEDED;
        }

        @Override
        public ArrayList<String> getWorkData() {
            return workdata;
        }

        @Override
        public boolean assimilate(Job redundantJob) {
            if (getState().isPreExecute()) {
                workdata.addAll(((WorkData) redundantJob).getWorkData());
                return true;
            }
            return false;
        }
    }

    static class CoSingletonJob extends Job<String> implements WorkData {
        private String key;

        public ArrayList<String> workdata = new ArrayList<>();

        CoSingletonJob(String key) {
            this.key = key;
        }

        @Override
        protected RunPolicy configureRunPolicy() {
            return RunPolicy.newLimitAttemptsPolicy()
                    .withConcurrencyPolicy(new SingletonPolicyKeepExisting(key))
                    .build();
        }

        @Override
        protected State onPrepare() {
            return WAIT;
        }

        @Override
        protected State doWork() {
            completedWork.addAll(workdata);
            return State.SUCCEEDED;
        }

        @Override
        public ArrayList<String> getWorkData() {
            return workdata;
        }
    }

    @Test
    public void testSingletonConcurrencyPolicy() {
        JobObserver job1 = jobManager.submit(new SingletonWaitingJob("foo"));
        job1.waitUntilAdded(100, TimeUnit.MILLISECONDS);
        JobObserver job2 = jobManager.submit(new SingletonWaitingJob("foo"));
        job2.waitUntilAdded(100, TimeUnit.MILLISECONDS);

        // job1 assimilates job2
        assertEquals(ASSIMILATED, job2.getState());
        assertEquals(job1.getId(), job2.getJob().getAssimilatedBy().getId());
        assertTrue(job2.getJob().isRemovable());
        assertFalse(job1.getJob().isRemovable());

        // job3 assimilates job4 and job5
        JobObserver job3 = jobManager.submit(new SingletonWaitingJob("bar"));
        job3.waitUntilAdded(100, TimeUnit.MILLISECONDS);
        JobObserver job4 = jobManager.submit(new SingletonWaitingJob("bar"));
        job4.waitUntilAdded(100, TimeUnit.MILLISECONDS);
        JobObserver<String> job5 = jobManager.submit(new CoSingletonJob("bar"));
        job5.waitUntilAdded(100, TimeUnit.MILLISECONDS);

        assertEquals(job3.getId(), job4.getJob().getAssimilatedBy().getId());
        assertEquals(job3.getId(), job5.getJob().getAssimilatedBy().getId());
        assertEquals(job4.getState(), ASSIMILATED);
        assertEquals(job5.getState(), ASSIMILATED);

        try {
            job1.getJob().cancel().get(5, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            e.printStackTrace();
        }

        assertEquals(State.CANCELED, job1.getState());
        assertEquals(ASSIMILATED, job2.getState());

        JobObserver job6 = jobManager.submit(new SingletonWaitingJob("foo"));
        job6.waitUntilAdded(100, TimeUnit.MILLISECONDS);
        assertEquals(job6.getState(), WAIT);
        assertNull(job6.getJob().getAssimilatedBy());

        ((SingletonWaitingJob) job3.getJob()).canSucceed = true;
        jobManager.serviceJob(job3.getJob(), 0);
        assertEquals(State.SUCCEEDED, job3.waitForTerminalState(100, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testAddDependedCycle() {
        final JobObserver job1 = jobManager.submit(new BasicJobTest.WaitingTestJob());
        Job job2 = new BasicJobTest.WaitingTestJob();
        job2.addDepended(job1.getJob());

        try {
            job1.getJob().addDepended(job2);
            fail("Dependency on non-active job created");
        } catch (Dependable.DependencyException e) {
        }

        jobManager.submit(job2);
        try {
            job1.getJob().addDepended(job2);
            fail("Circular dependency created");
        } catch (Exception e) {}

        Job job3 = new BasicJobTest.WaitingTestJob();
        jobManager.submit(job3);
        job2.addDepended(job3);
        try {
            job3.addDepended(job2);
            fail("Circular dependency created");
        } catch (Exception e) {
        }

        Job job4 = new BasicJobTest.WaitingTestJob();
        job4.addDepended(job2);
        jobManager.submit(job4);
        try {
            job3.setAssimilatedBy(job4);
            fail("Circular dependency created");
        } catch (Exception e) {}
    }

    @Test
    public void testAssimilation() {
        completedWork.clear();

        // SingletonWaitingJob uses a SingletonPolicyKeepExisting concurrency policy
        SingletonWaitingJob job1 = new SingletonWaitingJob("foo");
        job1.getWorkData().add("red");
        JobObserver ob1 = jobManager.submit(job1);
        assertEquals(State.WAIT, ob1.waitUntilAdded(100, TimeUnit.MILLISECONDS));

        SingletonWaitingJob job2 = new SingletonWaitingJob("foo");
        job2.getWorkData().add("green");
        JobObserver ob2 = jobManager.submit(job2);
        assertEquals(State.ASSIMILATED, ob2.waitUntilAdded(100, TimeUnit.MILLISECONDS));

        // CoSingletonJob uses the same SingletonPolicyKeepExisting concurrency policy
        CoSingletonJob job3 = new CoSingletonJob("foo");
        job3.getWorkData().add("yellow");
        JobObserver ob3 = jobManager.submit(job3);
        assertEquals(State.ASSIMILATED, ob3.waitUntilAdded(100, TimeUnit.MILLISECONDS));

        assertTrue(job1.getWorkData().contains("red"));
        assertTrue(job1.getWorkData().contains("green"));
        assertTrue(job1.getWorkData().contains("yellow"));
        assertEquals(3, job1.getWorkData().size());

        assertEquals(WAIT, ob1.getState());
        assertEquals(ASSIMILATED, ob2.getState());
        assertEquals(ASSIMILATED, ob3.getState());

        assertTrue(completedWork.isEmpty());
        job1.canSucceed = true;
        assertEquals(SUCCEEDED, ob1.waitForTerminalState(100, TimeUnit.MILLISECONDS));
        assertEquals(3, completedWork.size());
    }

    @Test
    public void tetClear() {
        jobManager.submit(new BasicJobTest.WaitingTestJob());
        jobManager.submit(new BasicJobTest.WaitingTestJob());
        jobManager.clear();
        assertTrue(jobManager.getJobs().isEmpty());
        jobManager.submit(new BasicJobTest.WaitingTestJob());
        assertFalse(jobManager.getJobs().isEmpty());
        jobManager.clear();
        assertTrue(jobManager.getJobs().isEmpty());
    }

    @Test
    public void testGetJobById() {
        Job job1 = new BasicJobTest.WaitingTestJob();
        Job job2 = new BasicJobTest.WaitingTestJob();
        jobManager.submit(job1);
        jobManager.submit(job2);
        assertEquals(job1, jobManager.getJob(job1.getId()));
        assertEquals(job2, jobManager.getJob(job2.getId()));
        assertNull(jobManager.getJob(new DependableId("1324")));
    }

    @Test
    public void testGetJobs() {
        JobObserver<Boolean> ob1 = jobManager.submit(new BasicJobTest.WaitingTestJob());
        ob1.waitUntilAdded(100, TimeUnit.MILLISECONDS);
        JobObserver<Boolean> ob2 = jobManager.submit(new BasicJobTest.WaitingTestJob());
        ob2.waitUntilAdded(100, TimeUnit.MILLISECONDS);
        List<Job<?>> jobs = jobManager.getJobs();
        assertEquals(jobs.get(0), ob1.getJob());
        assertEquals(jobs.get(1), ob2.getJob());
    }

    @Test
    public void testIsServiceThread() {
        assertFalse(jobManager.isServiceThread());
        final Boolean[] result = {null};
        jobManager.getScheduledExecutor().submit(new Runnable() {
            @Override
            public void run() {
                result[0] = jobManager.isServiceThread();
            }
        });

        while (result[0] == null) {};
        assertTrue(result[0]);

        result[0] = null;
        jobManager.getWorkerService().submit(new Runnable() {
            @Override
            public void run() {
                result[0] = jobManager.isServiceThread();
            }
        });

        while (result[0] == null) {};
        assertFalse(result[0]);
    }

    @Test
    public void testOldJobsRemoved() {
        JobObserver<Boolean> ob1 = jobManager.submit(new BasicJobTest.WaitingTestJob());
        JobObserver<Boolean> ob2 = jobManager.submit(new BasicJobTest.WaitingTestJob());
        assertEquals(2, jobManager.getJobs().size());
        ob1.getJob().cancel();
        ((BasicJobTest.WaitingTestJob)ob2.getJob()).canSucceed = true;
        assertTimedCondition(new TestUtil.Condition(){
            @Override
            public boolean isSatisfied() {
                return jobManager.getJobs().isEmpty();
            }

            @Override
            public void onFail() {
                for (Job<?> job : jobManager.getJobs()) {
                    System.out.println(job.toString());
                }
                assertEquals(0, jobManager.getJobs().size());
            }
        }, 1000);
    }


}
