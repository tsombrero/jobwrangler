package com.serfshack.jobwrangler.core;

import com.google.common.util.concurrent.Futures;
import com.serfshack.jobwrangler.util.Log;
import com.serfshack.jobwrangler.util.Utils;

import java.util.*;

import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;

/**
 * Base class for Jobs.
 *
 * @param <T> The job's result type
 */
public abstract class Job<T> extends Dependable {

    private boolean isDirty;
    Future<State> backgroundWorkFuture;
    private ScheduledFuture scheduledFuture;

    private HashMap<State, Long> stateDurations = new HashMap<>();
    private long timeLastStateTransition = System.currentTimeMillis();
    private Object doWorkLock = new Object();
    private Condition isAddedCondition = lock.newCondition();
    private Condition isTerminalStateCondition = lock.newCondition();
    private long pollInterval = ServiceJobTask.DEFAULT_POLL_INTERVAL;

    // internal use only; not necessarily a complete list of depending jobs,
    // just jobs that happen to have requested service upon this job's completion
    private Set<Job<?>> dependingJobsWaiting = new HashSet<>();

    // these may be serialized
    private int attempts;
    private volatile State state = State.NEW;
    private String stateMessage;
    private RunPolicy runPolicy;
    private Job assimilatedBy;

    // Implement doWork and use setResult(T) on success.
    private T result;

    static final String FAULTED_MESSAGE_DEPENDENCY = "Job failed due to upstream dependency";
    private JobObserver<T> observer;
    private boolean isCanceled;

    void replaceScheduledServiceFuture(ScheduledFuture<?> scheduledFuture) {
        if (this.scheduledFuture != null)
            this.scheduledFuture.cancel(false);
        this.scheduledFuture = scheduledFuture;
    }

    public boolean isDependingOn(Dependable dependable) {
        return getDependedDependables().containsKey(dependable);
    }

    List<Job<?>> getDependedJobs() {
        ArrayList<Job<?>> jobs = new ArrayList<>();
        for (Dependable dependedJob : getDependedDependables().keySet()) {
            if (dependedJob instanceof Job) {
                jobs.add((Job) dependedJob);
            }
        }
        return jobs;
    }

    void serviceJobOnCompletion(Job dependingJob) {
        dependingJobsWaiting.add(dependingJob);
    }

    public Job getAssimilatedBy() {
        if (assimilatedBy != null)
            return assimilatedBy;

        lock.lock();
        try {
            return assimilatedBy;
        } finally {
            lock.unlock();
        }
    }

    public Future<State> setAssimilatedBy(Job replacedBy) {
        lock.lock();
        Future<State> ret = null;
        try {
            this.assimilatedBy = replacedBy;
            ret = setState(State.ASSIMILATED);
        } catch (Exception e) {
            Log.e(e);
        } finally {
            lock.unlock();
        }

        if (getJobManager() != null) {
            for (Job<?> job : getJobManager().getJobs()) {
                if (job == this || job == replacedBy)
                    continue;

                job.onJobAssimilated(replacedBy, this);
            }
        }

        return ret;
    }

    /**
     * Add a hard dependency with the CASCADE_FAILURE DependencyFailureStrategy.
     *
     * @param dependable The depended
     * @throws DependencyCycleException Adding this dependency would create a cycle
     * @throws IllegalStateException    The proposed dependency is not active in the JobManager
     */
    @Override
    public void addDepended(Dependable dependable) {
        addDepended(dependable, DependencyFailureStrategy.CASCADE_FAILURE);
    }

    /**
     * Add a dependency with the specified DependencyFailureStrategy.
     *
     * @param dependable     The depended
     * @param inheritFailure The DependencyFailureStrategy to apply. Overwrites any
     *                       existing DependencyFailureStrategy.
     * @throws DependencyCycleException Adding this dependency would create a cycle
     * @throws IllegalStateException    The proposed dependency is not active in the JobManager
     */
    @Override
    public void addDepended(Dependable dependable, DependencyFailureStrategy inheritFailure) {
        Job replacedBy = getAssimilatedBy();
        if (replacedBy != null) {
            super.addDepended(replacedBy, inheritFailure);
        } else {
            super.addDepended(dependable, inheritFailure);
        }
    }

    public static class JobId extends DependableId {
        JobId() {
            super(UUID.randomUUID().toString());
        }
    }

    protected Job() {
        // Note we don't call setRunPolicy(configureRunPolicy()) here because some aspects of the RunPolicy may
        // depend on the Job being fully constructed, for example a ConcurrencyPolicy key may include a Job field.
        super(new JobId());
    }

    /**
     * Initialize with a JobManager instance.
     *
     * @param jobManager
     * @throws IllegalStateException    Job is already initialized
     * @throws NullPointerException     JobManager must not be null
     * @throws DependencyCycleException Adding this Dependable to the provided JobManager would result in a dependency cycle
     */
    public void init(JobManager jobManager) {
        lock.lock();
        try {
            if (getJobManager() != null)
                throw new IllegalStateException("Job is already initialized");

            if (jobManager == null)
                throw new NullPointerException("JobManager cannot be null");

            for (Dependable dependable : getDependedDependables().keySet()) {
                if (!dependable.isInitialized()) {
                    throw new Dependable.DependencyException("Unresolved Dependency : " + dependable);
                }
            }

            cycleCheck();

            setJobManager(jobManager);
        } finally {
            lock.unlock();
        }
        setRunPolicy(configureRunPolicy());
    }

    /**
     * Every job has one RunPolicy that governs its lifecycle. See RunPolicy
     *
     * @return A new RunPolicy to be applied to the job. Called once as part of initializing
     * the job, or as part of inflating a persisted job as the RunPolicy itself is not persisted.
     */
    protected RunPolicy configureRunPolicy() {
        return RunPolicy.newLimitAttemptsPolicy().build();
    }

    /**
     * Determine if it's safe to remove a Job from the graph. Some jobs may want to
     * override this to persist in a faulted state.
     *
     * @return True if the job is in a terminal state and no non-removable jobs
     * depend on this one.
     */
    protected boolean isRemovable() {
        if (!state.isTerminal())
            return false;

        lock.lock();
        try {
            cycleCheck();
            // ensure no non-removable jobs depend on this one
            for (Job job : getJobManager().getJobs()) {
                if (job.getDependingMode(this) != null && !job.isRemovable()) {
                    return false;
                }
            }
        } catch (Exception e) {
            Log.e(e);
        } finally {
            lock.unlock();
        }
        return true;
    }

    /**
     * @return The job's current state. Note that all state transitions happen asynchronously on
     * the Job Service thread, so calling getState() immediately after setState() may return
     * unexpected results. setState() returns a gettable Future to help with timing issues.
     */
    @Override
    public final State getState() {
        // note not locking here, callable from a UI thread
        return state;
    }

    /**
     * @return An unstructured text message about the current state, typically for logging.
     */
    public String getStateMessage() {
        return stateMessage == null && runPolicy != null ? runPolicy.getMessage() : stateMessage;
    }

    final State doPrepare() {
        if (getState().isTerminal())
            return getState();

        if (!getRunPolicy().shouldStart()) {
            Log.v(this + " should not start yet");
            return State.WAIT;
        }

        State requestedState = onPrepare();
        switch (requestedState) {
            case NEW:
            case BUSY:
                throw new IllegalStateException("Invalid state " + requestedState + " returned from onPrepare");
            case WAIT:
            case FAULTED:
                return requestedState;
            case READY:
            case SUCCEEDED:
            default:
        }

        if (requestedState != State.READY)
            return requestedState;

        return getAggregateStateOfDepended();
    }

    private State getAggregateStateOfDepended() {
        for (Map.Entry<Dependable, DependencyFailureStrategy> entry : getDependedDependables().entrySet()) {
            Dependable depended = entry.getKey();
            if (depended != null) {
                if (depended.getState() == State.FAULTED) {
                    if (entry.getValue() == DependencyFailureStrategy.CASCADE_FAILURE) {
                        State newState = onDependencyFailed(depended);
                        setState(newState, getDependencyFaultMessage(depended));
                        return newState;
                    }
                } else if (!depended.isSatisfied()) {
                    return State.WAIT;
                }
            }
        }
        return State.READY;
    }

    class SetStateCallable implements Callable<State> {

        private final State newState;
        private final String newStateMessage;

        SetStateCallable(State newState, String newStateMessage) {
            this.newState = newState;
            this.newStateMessage = newStateMessage;
        }

        @Override
        public State call() throws Exception {
            try {
                if (getJobManager() != null && !getJobManager().isServiceThread())
                    throw new IllegalStateException("SetStateCallable must be run on the service thread");

                final State oldState = Job.this.state;
                final String oldStateMessage = Job.this.stateMessage;

                // Avoid churning when setting state is redundant. Cancel is special because additional
                // cleanup may be required even if we were already in a faulted state
                if (newState == oldState) {
                    return oldState;
                }

                if (oldState.isTerminal() && !newState.isTerminal()) {
                    return oldState;
                }

                if (oldState == State.CANCELED) {
                    throw new IllegalStateException("Job is canceled: " + Job.this + " Cannot set state to " + newState);
                }

                if (newState.isFailed() && oldState.isInWorkLoop())
                    doRollback();

                lock.lock();
                try {
                    Job.this.state = newState;
                    Job.this.stateMessage = newStateMessage;

                    Log.d("Job " + Job.this + " " + " (was " + oldState
                            + (Utils.isEmpty(oldStateMessage) ? "" : " / " + oldStateMessage)
                            + ")");

                    recordStateTransitionDuration(oldState, newState);

                    if (oldState == State.NEW || (oldState.isTerminal() && !newState.isTerminal())) {
                        isAddedCondition.signalAll();
                    }

                    if (oldState.transitionIsPersistable(newState)) {
                        setDirty(true);
                    }

                    if (getState().isTerminal())
                        onTerminalState();
                } catch (Throwable e) {
                    Log.e(e);
                } finally {
                    lock.unlock();
                }

                if (getState() != oldState) {
                    if (observer != null && oldState.transitionIsPersistable(newState))
                        observer.notifyUpdate(JobObserver.NOTIFY_KEY_STATE_CHANGE);
                    onStateChanged(oldState);
                }

                if (isInitialized() && !oldState.isTerminal() && newState == State.FAULTED) {
                    for (Job otherjob : getJobManager().getJobs()) {
                        DependencyFailureStrategy dependingMode = otherjob.getDependingMode(Job.this);
                        if (dependingMode == DependencyFailureStrategy.CASCADE_FAILURE) {
                            otherjob.setState(otherjob.onDependencyFailed(Job.this), getDependencyFaultMessage(Job.this));
                        }
                    }
                }
            } catch (Throwable t) {
                Log.e(t);
            }
            return getState();
        }

        // Call rollback() on a separate thread and wait synchronously to catch and warn if it takes too long
        private void doRollback() {
            try {
                Executors.newSingleThreadExecutor().submit(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        try {
                            rollback();
                        } catch (Throwable t) {
                            Log.w(t);
                        }
                        return null;
                    }
                }).get(1, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException e) {
                Log.w(e);
            } catch (TimeoutException e) {
                Log.w(e, "Rollback is taking too long, continuing anyway. Possible concurrency issues. " + Job.this);
            }
        }
    }

    private void onTerminalState() {
        isTerminalStateCondition.signalAll();
        if (isInitialized()) {
            for (Job dependingJob : dependingJobsWaiting) {
                getJobManager().serviceJob(dependingJob);
            }
        }
    }

    /**
     * Set the Job's state
     *
     * @param newState The new state
     * @return A gettable future for the newly set state
     */
    final Future<State> setState(State newState) {
        return setState(newState, null);
    }

    /**
     * Set the Job's state. Job State transitions always happen on the Job Service thread, therefore
     * this is usually an async operation. The returned Future allows for a blocking call if that is
     * desired.
     *
     * @param newState        The new state
     * @param newStateMessage A message about the state
     * @return A gettable future for the newly set state
     * @throws IllegalArgumentException if newState is NEW or null
     */
    final Future<State> setState(State newState, String newStateMessage) {
        if (newState == null) {
            throw new IllegalArgumentException("State cannot be set to null");
        }

        if (newState == State.NEW) {
            throw new IllegalArgumentException("State cannot be set to NEW");
        }

        SetStateCallable setStateCallable = new SetStateCallable(newState, newStateMessage);
        if (!isInitialized() || getJobManager().isServiceThread()) {
            try {
                return Futures.immediateFuture(setStateCallable.call());
            } catch (Throwable e) {
                Log.e(e, "Failed setState on service thread");
            }
        }

        return getJobManager().getScheduledExecutor().submit(setStateCallable);
    }

    /**
     * Cancel the job
     *
     * @return A gettable Future State, allowing a blocking call to read the
     * final state. This will either be CANCELED (the usual case) or
     * SUCCEEDED (the Job can't be canceled because it already completed successfully).
     * Note it is legitimate to cancel a job in the FAULTED state.
     */
    final public Future<State> cancel() {
        lock.lock();
        try {
            if (!isCanceled()) {
                isCanceled = true;

                if (backgroundWorkFuture != null)
                    backgroundWorkFuture.cancel(false);

                if (getState() != State.SUCCEEDED) {
                    return setState(State.CANCELED);
                }
            }
        } finally {
            lock.unlock();
        }

        return Futures.immediateFuture(getState());
    }

    private void recordStateTransitionDuration(State oldState, State newState) {
        Long existingDuration = stateDurations.get(oldState);
        long now = System.currentTimeMillis();
        long timeToAdd = now - timeLastStateTransition;
        if (existingDuration == null) {
            stateDurations.put(oldState, timeToAdd);
        } else {
            stateDurations.put(oldState, existingDuration + timeToAdd);
        }
        timeLastStateTransition = now;

        if (newState == State.BUSY)
            attempts++;

        if (newState == State.CANCELED) {
            // don't log these, too chatty
        } else if (newState.isTerminal()) {
            StringBuilder sb = new StringBuilder(toString())
                    .append(" ")
                    .append(" Duration: ");

            for (State iterstate : State.values()) {
                if (!stateDurations.containsKey(iterstate))
                    continue;

                sb.append(iterstate.name())
                        .append(":")
                        .append(stateDurations.get(iterstate))
                        .append("ms ");
            }

            if (attempts > 1) {
                sb
                        .append(": ")
                        .append(attempts)
                        .append(" attempts ");
            }
            Log.i(sb.toString());
        }
    }

    public long getTotalDuration() {
        long ret = 0;
        for (State state : State.values()) {
            if (!state.isTerminal() && stateDurations.containsKey(state)) {
                ret += stateDurations.get(state);
            }
        }
        return ret;
    }

    /**
     * @return The JobObserver associated with this job, creating one if necessary.
     */
    public JobObserver<T> getObserver() {
        if (observer != null)
            return observer;

        lock.lock();
        try {
            if (observer == null) {
                observer = new JobObserver<>(this);
            }
            return observer;
        } finally {
            lock.unlock();
        }
    }

    // public access via the JobObserver
    State waitUntilAdded(long msTimeout) {
        lock.lock();
        try {
            if (getState() == State.NEW) {
                boolean succeeded = isAddedCondition.await(msTimeout, TimeUnit.MILLISECONDS);
                if (!succeeded) {
                    Log.e("FAILED waiting for job " + this + " to add");
                }
                return getState();
            } else {
                Log.d("Already added " + this);
            }
        } catch (InterruptedException e) {
            Log.w(e, "Wait interrupted.");
        } finally {
            lock.unlock();
        }
        return getState();
    }

    // public access via the JobObserver
    State waitForTerminalState(long msTimeout) {
        lock.lock();
        try {
            if (!getState().isTerminal()) {
                boolean succeeded = isTerminalStateCondition.await(msTimeout, TimeUnit.MILLISECONDS);
                if (!succeeded) {
                    Log.e("FAILED waiting for job " + this + " to complete");
                }
                return getState();
            }
        } catch (InterruptedException e) {
            Log.w(e, "Wait interrupted.");
        } finally {
            lock.unlock();
        }
        return getState();
    }

    private String className;
    /**
     * Override to provide a friendly description of this Job for logging purposes.
     *
     * @return The default description is "[classname] [state] [stateMessage]"
     * <p>
     * see toString()
     */
    public String getDescription() {
        if (className == null) {
            className = getClass().getName().substring(getClass().getName().lastIndexOf(".") + 1);
        }

        return className + " " + getState() +
                (Utils.isEmpty(getStateMessage()) ? "" : " / " + getStateMessage());
    }

    /**
     * Accessor for the Job's RunPolicy
     *
     * @return the RunPolicy, or null
     */
    public final RunPolicy getRunPolicy() {
        return runPolicy;
    }

    /**
     * Setter for the RunPolicy
     *
     * @param runPolicy The new RunPolicy, may not be null. This is called during job
     *                  initialization with the results of configureRunPolicy
     */
    final void setRunPolicy(RunPolicy runPolicy) {
        Utils.checkNull(runPolicy, "RunPolicy cannot be null");
        runPolicy.setJobId((JobId) getId());
        this.runPolicy = runPolicy;
    }

    /**
     * @return True if the Job has been canceled.
     */
    final public boolean isCanceled() {
        return isCanceled || getState() == State.CANCELED;
    }

    boolean isDirty() {
        return isDirty;
    }

    void setDirty(boolean dirty) {
        if (this.isDirty != dirty)
            this.isDirty = dirty;
    }

    long incrementPollInterval() {
        pollInterval = Math.min(10000, (long) ((float) pollInterval * 1.2f));
        return pollInterval;
    }

    void resetPollInterval() {
        pollInterval = ServiceJobTask.DEFAULT_POLL_INTERVAL;
    }

    final State doAdd() {
        if (!isInitialized())
            throw new RuntimeException("Job is not initialized");
        return onAdded();
    }

    State doDoWork() {
        synchronized (doWorkLock) {
            if (getState() != State.BUSY) {
                Log.i("Aborting DoInBackgroundTask because job is not in BUSY state: ", this);
                return getState();
            }

            State ret = doWork();

            if (ret == State.SUCCEEDED && getResult() == null)
                throw new RuntimeException("Job Result must be set on success. Call setResult() or override getResult()");

            return ret;
        }
    }

    /**
     * @return The Job's result value as set by setResult()
     */
    protected T getResult() {
        return result;
    }

    /**
     * Convenience function that combines waitForTerminalState() and getResult().
     *
     * @param t Max time to wait
     * @param timeUnit Units for t
     *
     * @return The Job's result, as passed to Job.setResult() on Job completion.
     */
    protected T getResultBlocking(long t, TimeUnit timeUnit) {
        waitForTerminalState(timeUnit.toMillis(t));
        return result;
    }

    /**
     * Set the job's result, if applicable.
     * @param result
     */
    protected void setResult(T result) {
        if (result == null)
            throw new NullPointerException("Result cannot be null");
        this.result = result;
    }

    private String getDependencyFaultMessage(Dependable job) {
        return String.format("%s : %s", FAULTED_MESSAGE_DEPENDENCY, job.toString());
    }

    // common overrides:

    /**
     * Callback, called once when the Job is submitted. Useful for writing a local representation
     * of the job to any UI or database.
     * <p>
     * Called from the main Job Service thread. Avoid blocking operations.
     *
     * @return The desired state, typically READY indicating the job is set to proceed
     * to onPrepare(). SUCCEEDED, FAULTED, CANCELED are also allowed.
     */
    protected State onAdded() {
        return State.READY;
    }

    /**
     * Callback, called after any new job has been successfully added to the JobManager.
     * <p>
     * Called from the main Job Service thread. Avoid blocking operations.
     *
     * @param newjob The job that was added. Any action may be taken here including canceling
     *               or modifying the new job or the current one. May be useful if
     *               ConcurrencyPolicy logic is not sufficient to manage the situation.
     */
    protected void onNewJobAdded(Job newjob) {
    }

    /**
     * Callback, called after any other job gets assimilated. This takes care of removing the
     * assimilated job from the job's dependencies and replacing it with the assimilating one.
     *
     * @param assimilated  The job moved to the ASSIMILATED state
     * @param assimilating The surviving job
     */
    protected void onJobAssimilated(Job assimilating, Job assimilated) {
        DependencyFailureStrategy dependencyMode = getDependedDependables().get(assimilated);
        if (dependencyMode != null)
            addDepended(assimilating, dependencyMode);
    }

    /**
     * Callback, called after any state transition, so getState() will return the new state.
     * <p>
     * Called from the main Job Service thread. Avoid blocking operations.
     *
     * @param oldState The previous state
     */
    protected void onStateChanged(State oldState) {
    }

    /**
     * Callback, called when the Job's RunPolicy and dependencies allow this job to
     * proceed to the READY state. This lets the job do any other last-minute checks
     * before proceeding. A call to doWork() is always gated by a call
     * to onPrepare().
     * <p>
     * Called from the main Job Service thread. Avoid blocking operations.
     *
     * @return The desired State:
     * <p>
     * READY : Success is possible. doWork will be called immediately.
     * WAIT : Job is not ready. onPrepare() will be called again on a backoff schedule.
     * SUCCEEDED, FAULTED, CANCELED are also allowed.
     * <p>
     * Note, this call does not count against any RunPolicy attempt limit.
     */
    protected State onPrepare() {
        return State.READY;
    }

    /**
     * Callback, called when onPrepare() has returned READY. The Job's main work
     * is done here.
     * <p>
     * While doWork() is running, the job will be in the BUSY state.
     * <p>
     * Called from a worker thread.
     *
     * @return The desired state:
     * SUCCEEDED : Job completed
     * READY/WAIT : Attempt failed, retry according to the RunPolicy schedule
     * FAULTED/CANCELED : Job failed in a bad way, no retries will be scheduled
     * BUSY : The job's work has started and continues asynchronously. See checkProgress()
     * <p>
     * The return value will be validated against the Job's RunPolicy before transitioning
     * the state. For example, returning READY when the attempt limit has been spent will
     * result in a transition to the FAULTED state.
     */
    abstract protected State doWork();

    /**
     * Callback, called periodically while the Job is in the BUSY state. This is
     * useful when doWork() has started some asynchronous operation. In that case
     * checkProgress() is called on a backoff schedule as allowed by the RunPolicy.
     * <p>
     * Called from the main Job Service thread. Avoid blocking operations.
     *
     * @return The desired state:
     * SUCCEEDED : Job completed
     * READY/WAIT : Attempt failed, retry according to the RunPolicy schedule
     * FAULTED/CANCELED : Job failed in a bad way, no retries will be scheduled
     * BUSY : The Job is still working
     * <p>
     * The return value will be validated against the Job's RunPolicy before transitioning
     * the state.
     */
    protected State checkProgress() {
        return getState();
    }

    /**
     * Callback, called when a depended Job has faulted.
     * <p>
     * Called from the main Job Service thread. Avoid blocking operations.
     *
     * @param failedDependency
     * @return The desired state. In the default implementation, if the dependency relationship
     * uses the CASCADE_FAILURE DependencyFailureStrategy, the depending job will also fail.
     */
    protected State onDependencyFailed(Dependable failedDependency) {
        if (!getState().isTerminal()
                && getDependedDependables().get(failedDependency) == DependencyFailureStrategy.CASCADE_FAILURE) {
            Log.d("Ending job %s because it depends on %s", toString(), failedDependency.toString());
            return State.FAULTED;
        }
        return getState();
    }

    /**
     * Callback, called when this job has been successfully added and subsequently moves to
     * a FAULTED or CANCELED state. May be used to clean up things that were added in
     * onAdded().
     * <p>
     * Called on a worker thread.
     */
    protected void rollback() {
    }

    /**
     * Callback, called when this Job's ConcurrencyPolicy has collided with another one,
     * and this Job wins. Override this method so the surviving job to takes on the redundant
     * job's work as necessary. Handy for use in throttling/debouncing schemes.
     * <p>
     * This method is always called in the context of enqueueing either the redundant or the
     * surviving job, depending on the ConcurrencyPolicy.
     *
     * @param redundantJob The job slated for assimilation
     * @return true if the redundant job's work was absorbed successfully and should be
     * moved to the terminal ASSIMILATED state. Return false to allow redundantJob to
     * continue normally.
     */
    public boolean assimilate(Job redundantJob) {
        return true;
    }

    ////

    public static Comparator<Job> ascendingStartTimeComparator = new Comparator<Job>() {

        public int compare(Job lhs, Job rhs) {
            return Long.compare(lhs.getRunPolicy().getTimeJobStarted(), rhs.getRunPolicy().getTimeJobStarted());
        }
    };
}
