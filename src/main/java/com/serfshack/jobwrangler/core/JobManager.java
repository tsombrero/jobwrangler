package com.serfshack.jobwrangler.core;

import com.serfshack.jobwrangler.core.persist.Persistor;
import com.serfshack.jobwrangler.core.concurrencypolicy.AbstractConcurrencyPolicy;
import com.serfshack.jobwrangler.util.Log;
import gobblin.util.executors.ScalingThreadPoolExecutor;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;


/**
 * JobManager provides context for jobs to run in.
 */
public class JobManager {

    private ReentrantLock lock;
    private boolean isInit;
    private ExecutorService workerExecutor;
    private final Persistor persistor;
    private static long threadIndex;
    private Map<DependableId, Job<?>> jobs = new ConcurrentHashMap<>();

    private long jobServiceThreadId = -1;
    private ScheduledThreadPoolExecutor jobServiceExecutor;

    /**
     * Constructor, same as JobManager(null)
     */
    public JobManager() {
        this(null);
    }

    /**
     * Constructor
     *
     * @param persistor implementation of the Persistor interface for persisting jobs across
     *                  instances. May be null, which means jobs do not persist.
     */
    public JobManager(Persistor persistor) {
        lock = new ReentrantLock();
        this.persistor = persistor;
    }

    /**
     * Submit a Job for processing.
     *
     * @param job The Job to submit
     * @return a JobObserver for the submitted job
     * @throws Dependable.DependencyException      The submitted job contains an invalid dependency
     * @throws Dependable.DependencyCycleException Submitting this job would create a dependency cycle
     */
    public <T> JobObserver<T> submit(Job<T> job) {
        if (job == null)
            return null;

        lock.lock();
        try {
            job.init(this);
            jobs.put(job.getId(), job);
        } finally {
            lock.unlock();
        }

        serviceJob(job);
        return job.getObserver();
    }

    void serviceJob(Job<?> job) {
        if (isServiceThread()) {
            new ServiceJobTask(job).run();
        } else {
            serviceJob(job, 0);
        }
    }

    void serviceJob(Job<?> job, long msDelay) {
        job.replaceScheduledServiceFuture(getScheduledExecutor().schedule(new ServiceJobTask(job), msDelay, TimeUnit.MILLISECONDS));
    }

    /**
     * @return A shallow-copy snapshot of the current list of jobs in ascending order by start time
     */
    public List<Job<?>> getJobs() {
        init();
        long t = System.currentTimeMillis();
        List<Job<?>> ret;
        try {
            ret = new ArrayList<>(jobs.values());
        } catch (Exception e) {
            // try again with the lock
            lock.lock();
            try {
                ret = new ArrayList<>(jobs.values());
            } finally {
                lock.unlock();
            }
        }
        Collections.sort(ret, Job.ascendingStartTimeComparator);
        if (System.currentTimeMillis() - t > 500) {
            Log.w("PERF took " + (System.currentTimeMillis() - t) + "ms to return " + ret.size() + " jobs");
        }
        return ret;
    }

    boolean checkForCollision(Job<?> newJob) {
        AbstractConcurrencyPolicy newJobConcurrencyPolicy = newJob.getRunPolicy().getConcurrencyPolicy();
        if (newJobConcurrencyPolicy == null)
            return false;

        for (Job existingJob : getJobs()) {
            try {
                if (existingJob == newJob)
                    continue;

                if (existingJob.getState().isTerminal())
                    continue;

                if (existingJob.getRunPolicy().shouldFailJob())
                    continue;

                AbstractConcurrencyPolicy existingJobConcurrencyPolicy = existingJob.getRunPolicy().getConcurrencyPolicy();
                if (newJobConcurrencyPolicy.equals(existingJobConcurrencyPolicy)) {
                    Log.w("Concurrency collision: {" + newJob + "} vs {" + existingJob + "}");
                    existingJobConcurrencyPolicy.onCollision(existingJob, newJob);
                    if (newJob.getAssimilatedBy() != null)
                        return true;
                }
            } catch (Throwable t) {
                Log.w(t, "Job expelled due to concurrency policy check misbehavior " + existingJob);
                existingJob.cancel();
            }
        }
        return false;
    }

    void persist(Job<?> job) {
        try {
            if (job.isRemovable()) {
                jobs.remove(job.getId());
            }

            if (persistor == null)
                return;

            if (job.getClass().isAnonymousClass() || job.getClass().isMemberClass()) {
                Log.w("Jobs in anonymous or member classes are not persistable. Override shouldPersist()");
                return;
            }

            if (job.isRemovable()) {
                persistor.removeJob((Job.JobId) job.getId());
            } else {
                if (job.isDirty()) {
                    persistor.putJob(job);
                }
            }

            job.setDirty(false);

        } catch (Throwable t) {
            Log.w(t, "update failed. Removing job " + job);
            try {
                job.cancel();
            } catch (Throwable t1) {
                Log.w("failed removing job " + job);
            }
            try {
                persistor.removeJob((Job.JobId) job.getId());
            } catch (Throwable t1) {
                Log.w("failed removing job " + job);
            }
        }
    }

    private void init() {
        if (isInit)
            return;

        if (persistor != null) {
            lock.lock();
            try {
                if (isInit)
                    return;

                Log.i("Initializing...");

                for (Job job : persistor.getJobs()) {
                    jobs.put(job.getId(), job);
                }
                isInit = true;
                serviceAllJobs();
            } finally {
                lock.unlock();
            }
        }
    }

    private void serviceAllJobs() {
        for (Job job : getJobs()) {
            getScheduledExecutor().schedule(new ServiceJobTask(job), 0, TimeUnit.SECONDS);
        }
    }

    ExecutorService getWorkerService() {
        if (workerExecutor != null && !workerExecutor.isShutdown())
            return workerExecutor;

        lock.lock();
        try {
            if (workerExecutor == null || workerExecutor.isShutdown()) {
                workerExecutor = ScalingThreadPoolExecutor.newScalingThreadPool(0,
                        (1 + Runtime.getRuntime().availableProcessors()) * 3, 10000,
                        new ThreadFactory() {
                            @Override
                            public Thread newThread(Runnable runnable) {
                                Thread ret = new Thread(runnable);
                                ret.setDaemon(true);
                                ret.setName("JobWorker-" + threadIndex++);
                                return ret;
                            }
                        });
            }
        } finally {
            lock.unlock();
        }
        return workerExecutor;
    }

    /**
     * Retrieve a Job based on id.
     *
     * @param jobId
     * @return The Job itself, or null.
     */
    public Job getJob(DependableId jobId) {
        if (jobId == null)
            return null;

        lock.lock();
        try {
            init();
            return jobs.get(jobId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clear all jobs from the JobManager. Also calls clear() on the supplied Persistor, if any
     */
    public void clear() {
        Log.i("Clearing JobManager");
        lock.lock();
        try {
            jobs.clear();
            if (persistor != null)
                persistor.clear();
            isInit = false;
        } finally {
            lock.unlock();
        }
    }

    private Job removeJob(final Job.JobId jobId) {
        Job ret = jobs.remove(jobId);
        getWorkerService().submit(new RemoveJobTask(jobId));
        return ret;
    }

    ScheduledThreadPoolExecutor getScheduledExecutor() {
        if (jobServiceExecutor == null || jobServiceExecutor.isShutdown()) {
            lock.lock();
            try {
                if (jobServiceExecutor == null || jobServiceExecutor.isShutdown()) {
                    jobServiceExecutor = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread thread = new Thread(r, "JobService");
                            jobServiceThreadId = thread.getId();
                            Log.d("JobServiceThread ID is " + jobServiceThreadId);
                            return thread;
                        }
                    });
                    jobServiceExecutor.setRemoveOnCancelPolicy(true);
                }
            } finally {
                lock.unlock();
            }
        }
        return jobServiceExecutor;
    }

    boolean isServiceThread() {
        return Thread.currentThread().getId() == jobServiceThreadId;
    }

    public Dependable getDependable(DependableId dependedId) {
        Dependable ret = jobs.get(dependedId);
        return ret;
    }

    private class RemoveJobTask implements Runnable {
        private final Job.JobId id;

        RemoveJobTask(Job.JobId id) {
            this.id = id;
        }

        @Override
        public void run() {
            if (persistor != null) {
                persistor.removeJob(id);
            }
        }
    }
}
