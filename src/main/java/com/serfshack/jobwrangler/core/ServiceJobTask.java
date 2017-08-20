package com.serfshack.jobwrangler.core;

import com.serfshack.jobwrangler.util.Log;

import java.util.ArrayList;

public class ServiceJobTask implements Runnable {

    private final Job job;

    static long DEFAULT_POLL_INTERVAL = 200;

    ArrayList<Job> jobsToServiceImmediately = new ArrayList<>();

    ServiceJobTask(Job job) {
        if (job.getJobManager() == null)
            throw new IllegalStateException("Job not initialized");

        this.job = job;
    }

    @Override
    public void run() {
        try {
            State originalState = job.getState();

            if (job.getState() == State.NEW) {
                enqueueJob();
            }

            if (job.getRunPolicy() == null)
                throw new IllegalStateException("Job must have a run policy");

            // note we call prepareJob even if the job says it's ready
            if (job.getState() == State.WAIT || job.getState() == State.READY) {
                job.setState(job.doPrepare());
            }

            if (job.getState() == State.READY) {
                executeJob();
            }

            if (job.getState() == State.BUSY) {
                checkJobProgress();
            }

            if (!job.getState().isTerminal()) {
                reschedule(job.getState() != originalState);
            }

            for (Job serviceNowJob : jobsToServiceImmediately) {
                new ServiceJobTask(serviceNowJob).run();
            }

            if (job.isDirty())
                job.getJobManager().persist(job);

        } catch (Throwable e) {
            Log.e(e);
            job.setState(State.FAULTED, e.getLocalizedMessage());
        }
    }

    private void enqueueJob() {
        job.setRunPolicy(job.configureRunPolicy());
        job.getJobManager().checkForCollision(job);

        if (job.getState() != State.NEW)
            return;

        job.cycleCheck();

        State state = job.doAdd();

        if (state == State.READY)
            state = State.WAIT;

        job.setState(state);

        // Give existing jobs a chance to react to the new job
        for (Job existingJob : job.getJobManager().getJobs()) {
            try {
                if (existingJob.getId().equals(this.job.getId()))
                    continue;

                if (existingJob.getState().isTerminal())
                    continue;

                if (existingJob.getState() == State.NEW)
                    continue;

                existingJob.onNewJobAdded(this.job);

                if (this.job.getState().isTerminal())
                    break;
            } catch (Throwable e) {
                Log.e(e);
            }
        }
    }

    private void executeJob() {
        if (job.getRunPolicy().shouldStart()) {

            if (job.backgroundWorkFuture != null && !job.backgroundWorkFuture.isDone()) {
                Log.e("Canceling orphaned background task for job " + job);
                job.backgroundWorkFuture.cancel(true);
                job.backgroundWorkFuture = null;
            }

            job.getRunPolicy().onAttemptStarted();
            job.setState(State.BUSY);
            job.backgroundWorkFuture = job.getJobManager().getWorkerService().submit(new DoInBackgroundTask(job));
        }
    }

    private void checkJobProgress() {
        State state = job.checkProgress();
        if (!state.isTerminal() && job.getRunPolicy().shouldFailAttempt()) {
            if (job.backgroundWorkFuture != null && !job.backgroundWorkFuture.isDone()) {
                job.backgroundWorkFuture.cancel(true);
            }
            job.backgroundWorkFuture = null;
            job.setState(job.getRunPolicy().validateRequestedState(State.READY));
        } else {
            job.setState(state);
        }
    }

    private void reschedule(boolean resetPollInterval) {
        if (resetPollInterval)
            job.resetPollInterval();

        State state = job.getState();
        long timeToWait = -1;

        switch (state) {
            case WAIT:
                boolean scheduled = false;

                for (Object dependedJobObject : job.getDependedJobs()) {
                    Job dependedJob = (Job) dependedJobObject;
                    if (!dependedJob.getState().isTerminal()) {
                        dependedJob.serviceJobOnCompletion(job);
                        scheduled = true;
//                        Log.i("Depending job " + job + " will be quiet until " + dependedJob + " finishes");

                        if (dependedJob.getState() == State.NEW || dependedJob.getState() == State.READY) {
                            jobsToServiceImmediately.add(dependedJob);
                        }
                    }
                }

                if (!scheduled)
                    timeToWait = Math.max(job.incrementPollInterval(), job.getRunPolicy().getTimeOfNextAttempt() - System.currentTimeMillis());
                break;
            case READY:
                timeToWait = Math.max(0, job.getRunPolicy().getTimeOfNextAttempt() - System.currentTimeMillis());
                break;
            case BUSY:
                timeToWait = Math.min(job.incrementPollInterval(), job.getRunPolicy().getTimeAttemptStarted() + job.getRunPolicy().getAttemptTimeout() - System.currentTimeMillis());
                break;
        }

        if (timeToWait >= 0) {
            job.getJobManager().serviceJob(job, Math.max(timeToWait, DEFAULT_POLL_INTERVAL));
            Log.i("Job " + job + " visit scheduled in " + Math.max(timeToWait, DEFAULT_POLL_INTERVAL) + "ms");
        } else {
            Log.i("Job " + job + " visit not scheduled");
        }
    }
}
