package com.serfshack.jobwrangler.core;

import com.serfshack.jobwrangler.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class JobObserver<T> {
    private final Job<T> job;
    private HashMap<OnUpdateCallback<Job<T>>, ExecutorService> onUpdateListeners;
    private HashMap<OnCompleteCallback<Job<T>>, ExecutorService> onCompletelisteners;
    private static ExecutorService defaultExecutor;
    private static final Object executorSync = new Object();
    private final Object sync = new Object();

    /**
     * Notiication key for a state change notification
     */
    public static final int NOTIFY_KEY_STATE_CHANGE = 0;

    JobObserver(Job<T> job) {
        this.job = job;
    }

    /**
     * Block until the Job has been added or the add has failed. Subject to timeout.

     * @param t Max time to wait
     * @param timeUnit Units for t
     * @return The state of the job after adding. Typically WAIT or READY but may be a terminal state if the
     * add failed, was canceled, succeeded early, or was assimilated by another job. In the event of a timeout,
     * the job's current state is returned.
     */
    public State waitUntilAdded(long t, TimeUnit timeUnit) {
        return job.waitUntilAdded(timeUnit.toMillis(t));
    }

    /**
     * Block until the job has reached a terminal State (see State.isTerminal()). Subject to timeout.
     *
     * @param t Max time to wait
     * @param timeUnit Units for t
     * @return The final state of the job. In the event of a timeout, the job's current state is returned.
     */
    public State waitForTerminalState(long t, TimeUnit timeUnit) {
        return job.waitForTerminalState(timeUnit.toMillis(t));
    }

    /**
     * @return The Job's ID
     */
    public DependableId getId() {
        return job.getId();
    }

    /**
     *
     * @return The Job associated with this observer
     */
    public Job getJob() {
        return job;
    }

    /**
     * @return The Job's current State
     */
    public State getState() {
        return job.getState();
    }

    /**
     * Fetch the Job's result
     *
     * @return The Job's result, as passed to Job.setResult() on Job completion.
     */
    public T getResult() {
        return job.getResult();
    }

    /**
     * Convenience function that combines waitForTerminalState() and getResult().
     *
     * @param t Max time to wait
     * @param timeUnit Units for t
     *
     * @return The Job's result, as passed to Job.setResult() on Job completion.
     */
    public T getResultBlocking(long t, TimeUnit timeUnit) {
        return job.getResultBlocking(t, timeUnit);
    }

    public JobObserver<T> subscribe(OnUpdateCallback<Job<T>> onUpdateCallback) {
        if (onUpdateCallback == null)
            return this;

        if (defaultExecutor == null) {
            synchronized (executorSync) {
                if (defaultExecutor == null)
                    defaultExecutor = Executors.newCachedThreadPool();
            }
        }

        return subscribe(onUpdateCallback, defaultExecutor);
    }

    /**
     * Subscribe to events from this JobObserver
     *
     * @param onUpdateCallback The OnUpdateCallback implementation
     * @param executorService The executor to handle the onCompleteCallback, or null to use the default executor.
     *
     * @return The JobObserver
     */
    public JobObserver<T> subscribe(OnUpdateCallback<Job<T>> onUpdateCallback, ExecutorService executorService) {
        if (onUpdateCallback == null)
            return this;

        synchronized (sync) {
            if (onUpdateListeners == null)
                onUpdateListeners = new HashMap<>();

            onUpdateListeners.put(onUpdateCallback, executorService);
            return this;
        }
    }

    public JobObserver<T> subscribeOnComplete(OnCompleteCallback<Job<T>> onCompleteCallback) {
        return subscribeOnComplete(onCompleteCallback, null);
    }

    /**
     * Subscribe to events from this JobObserver
     *
     * @param onCompleteCallback The OnCompleteCallback implementation
     * @param executorService The executor to handle the onCompleteCallback, or null to use the default executor.
     *
     * @return The JobObserver
     */
    public JobObserver<T> subscribeOnComplete(OnCompleteCallback<Job<T>> onCompleteCallback, ExecutorService executorService) {
        if (onCompleteCallback == null)
            return this;

        synchronized (sync) {
            if (onUpdateListeners == null)
                onUpdateListeners = new HashMap<>();

            onCompletelisteners.put(onCompleteCallback, executorService);
            return this;
        }
    }

    /**
     * Notify callbacks
     *
     * @param key An informational key passed through to the onCompleteCallback along with the Job.
     */
    public void notifyUpdate(int key) {
        Set<Map.Entry<OnUpdateCallback<Job<T>>, ExecutorService>> entries;

        synchronized (sync) {
            if (onUpdateListeners == null)
                return;

            entries = onUpdateListeners.entrySet();
        }

        for (Map.Entry<OnUpdateCallback<Job<T>>, ExecutorService> entry : entries) {
            try {
                if (entry.getKey() != null && entry.getValue() != null)
                    entry.getValue().submit(new OnUpdateCallbackTask(entry.getKey(), job, key));
            } catch (Throwable t) {
                Log.e(t);
            }
        }
    }

    public interface OnUpdateCallback<S extends Job> {
        /**
         * OnUpdateCallback interface for the JobObserver. This is called whenever Job.notifyUpdate() is called.
         *
         * The default behavior is to tickle this onCompleteCallback when the job state changes. If no executor
         * has been specified, a default SingleThreadExecutor will be used. The default executor is shared
         * among all job observers.
         *
         * @param job The job on which notifyUpdate() was called
         * @param key The key passed to notifyUpdate(). This can be used to differentiate types of notification.
         *            NOTIFY_KEY_STATE_CHANGE (0) is reserved.
         */
        void onUpdate(S job, int key);
    }

    public interface OnCompleteCallback<S extends Job> {
        /**
         * OnCompleteCallback interface for the JobObserver. This is called once when the job reaches a terminal state.
         *
         * If no executor has been specified, a default SingleThreadExecutor will be used. The default
         * executor is shared among all job observers.
         *
         * @param job The job on which notifyUpdate() was called
         */
        void onComplete(S job);
    }

    private class OnUpdateCallbackTask implements Callable<Void> {
        private final OnUpdateCallback<Job<T>> onUpdateCallback;
        private final Job<T> job;
        private final int key;

        OnUpdateCallbackTask(OnUpdateCallback<Job<T>> onUpdateCallback, Job<T> job, int key) {
            this.onUpdateCallback = onUpdateCallback;
            this.job = job;
            this.key = key;
        }

        @Override
        public Void call() throws Exception {
            try {
                onUpdateCallback.onUpdate(job, key);
            } catch (Throwable t) {
                Log.e(t);
            }
            return null;
        }
    }

    private class OnCompleteCallbackTask implements Callable<Void> {
        private final OnCompleteCallback<Job<T>> onCompleteCallback;
        private final Job<T> job;

        OnCompleteCallbackTask(OnCompleteCallback<Job<T>> onCompleteCallback, Job<T> job) {
            this.onCompleteCallback = onCompleteCallback;
            this.job = job;
        }

        @Override
        public Void call() throws Exception {
            try {
                onCompleteCallback.onComplete(job);
            } catch (Throwable t) {
                Log.e(t);
            }
            return null;
        }
    }

    @Override
    public String toString() {
        return "Observer:" + job.toString();
    }
}
