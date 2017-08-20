package com.serfshack.jobwrangler.core;

import com.serfshack.jobwrangler.core.concurrencypolicy.AbstractConcurrencyPolicy;
import com.serfshack.jobwrangler.util.Log;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

public class RunPolicy {

    // Initialize with sane defaults
    private int maxJobAttempts = 10;
    private int attemptsRemaining = maxJobAttempts;
    private long jobTimeout = TimeUnit.MINUTES.toMillis(1);
    private long attemptTimeout = Long.MAX_VALUE;

    // Time between attempts is dynamic if exponential backoff is used
    private long delayOnFailedAttempt = TimeUnit.SECONDS.toMillis(5);
    private long delayOnFailedAttemptMax = TimeUnit.SECONDS.toMillis(30);

    // Job may be delayed initially for debouncing purposes
    private long initialDelay = 0;

    private final long timeCreated = now();
    private long timeStarted = timeCreated;
    private long timeAttemptStarted = 0;
    private long timeOfNextAttempt;

    private String stateMessage;
    private Job.JobId jobId;

    private HashSet<GatingCondition> gatingConditions;
    private AbstractConcurrencyPolicy concurrencyPolicy;

    static final String FAILED_NO_MORE_RETRIES = "No more retries";
    static final String FAILED_TIMED_OUT = "Job timed out";

    protected RunPolicy() {
    }

    // Copy constructor
    public RunPolicy(RunPolicy other) {
        maxJobAttempts = other.maxJobAttempts;
        jobTimeout = other.jobTimeout;
        attemptTimeout = other.attemptTimeout;
        delayOnFailedAttempt = other.delayOnFailedAttempt;
        initialDelay = other.initialDelay;
        delayOnFailedAttemptMax = other.delayOnFailedAttemptMax;
        timeOfNextAttempt = Math.max(other.timeOfNextAttempt, timeStarted + initialDelay);
        attemptsRemaining = other.attemptsRemaining;
        stateMessage = other.stateMessage;
        if (other.gatingConditions != null)
            gatingConditions = new HashSet<>(other.gatingConditions);
        concurrencyPolicy = other.concurrencyPolicy;
    }

    /**
     * @return A RunPolicy Builder based on this RunPolicy. Note the Builder does not modify
     * existing RunPolicies, it builds new ones.
     */
    public Builder buildUpon() {
        return new Builder(this);
    }

    void setJobId(Job.JobId jobId) {
        if (getJobId() != null && !getJobId().equals(jobId)) {
            throw new IllegalStateException("RunPolicy already assigned to another job");
        }

        this.jobId = jobId;
    }

    /**
     * @return The ID of the associated job
     */
    public Job.JobId getJobId() {
        return jobId;
    }

    /**
     * @return The RunPolicy's ConcurrencyPolicy, or null
     */
    public AbstractConcurrencyPolicy getConcurrencyPolicy() {
        return concurrencyPolicy;
    }

    /**
     * Builder class for creating new RunPolicy objects.
     */
    public static class Builder {

        RunPolicy runPolicy;

        public Builder() {
            runPolicy = new RunPolicy();
        }

        public Builder(RunPolicy runPolicy) {
            this.runPolicy = new RunPolicy(runPolicy);
        }

        /**
         * @param attempts The maximum number of attempts before the job faults.
         * @return this Builder for chaining
         */
        public Builder withMaxAttempts(int attempts) {
            runPolicy.attemptsRemaining = attempts;
            runPolicy.maxJobAttempts = attempts;
            return this;
        }

        /**
         * @param timeout The maximum age for the Job before it faults.
         * @param timeUnit Units for timeout
         * @return this Builder for chaining
         */
        public Builder withJobTimeout(long timeout, TimeUnit timeUnit) {
            runPolicy.jobTimeout = timeUnit.toMillis(timeout);
            return this;
        }

        /**
         * Sets a static RetryDelay.
         *
         * @param retryDelay The time to wait after a failed attempt before initiating a new one.
         * @param timeUnit Units for timeout
         * @return this Builder for chaining
         */
        public Builder withRetryDelay(long retryDelay, TimeUnit timeUnit) {
            runPolicy.delayOnFailedAttempt = timeUnit.toMillis(retryDelay);
            runPolicy.delayOnFailedAttemptMax = 0;
            return this;
        }

        /**
         * Sets an attempt timeout.
         *
         * @param singleAttemptTimeout The maximum age for a single Attempt before it fails.
         * @param timeUnit Units for timeout
         * @return this Builder for chaining
         */
        public Builder withAttemptTimeout(long singleAttemptTimeout, TimeUnit timeUnit) {
            runPolicy.attemptTimeout = timeUnit.toMillis(singleAttemptTimeout);
            return this;
        }

        /**
         * Sets an initial delay before the Job proceeds to the READY state. Note this does not
         * delay Job initialization or onAdded(), which always happen immediately on submit.
         *
         * @param initialDelay Minimum to wait before proceeding to the READY state.
         * @param timeUnit Units for initialDelay
         * @return this Builder for chaining
         */
        public Builder withInitialDelay(long initialDelay, TimeUnit timeUnit) {
            runPolicy.initialDelay = timeUnit.toMillis(initialDelay);
            runPolicy.timeOfNextAttempt = Math.max(runPolicy.timeOfNextAttempt, runPolicy.timeStarted + initialDelay);
            return this;
        }

        /**
         * Use a default exponential backoff strategy for retries with a random initial retry delay of
         * 500-1500ms and a max retry delay of 30 seconds.
         *
         * @return this Builder for chaining
         */
        public Builder withExponentialBackoff() {
            return withExponentialBackoff(
                    (Math.abs(new SecureRandom().nextInt()) % 1500) + 500,
                    30000, TimeUnit.MILLISECONDS);
        }

        /**
         * Use an exponential backoff strategy for retries with the specified initial and max retry delays.
         * Each retry delay will be double the previous one.
         *
         * @param initialRetryDelay Initial retry delay
         * @param maxRetryDelay Maximum retry delay
         * @param timeUnit Units for delay params
         *
         * @return this Builder for chaining
         */
        public Builder withExponentialBackoff(long initialRetryDelay, long maxRetryDelay, TimeUnit timeUnit) {
            runPolicy.delayOnFailedAttempt = Math.max(1, timeUnit.toMillis(initialRetryDelay));
            runPolicy.delayOnFailedAttemptMax = Math.max(runPolicy.delayOnFailedAttempt, timeUnit.toMillis(maxRetryDelay));
            return this;
        }

        /**
         * Assign a GatingCondition to the RunPolicy. May be called repeatedly to add multiple
         * gating conditions. No attempt will be started and no retry logic comes into play
         * until all GatingConditions are satisfied.
         *
         * @param gatingCondition a GatingCondition
         *
         * @return this Builder for chaining
         */
        public Builder withGatingCondition(GatingCondition gatingCondition) {
            if (runPolicy.gatingConditions == null)
                runPolicy.gatingConditions = new HashSet<>();
            runPolicy.gatingConditions.add(gatingCondition);
            return this;
        }

        /**
         * Assign a ConcurrencyPolicy to the RunPolicy. Only one ConcurrencyPolicy may be associated
         * with a RunPolicy.
         *
         * See FIFOPolicy, SingletonPolicyKeepExisting, SingletonPolicyReplaceExisting
         *
         * @param concurrencyPolicy a ConcurrencyPolicy
         *
         * @return this Builder for chaining
         */
        public Builder withConcurrencyPolicy(AbstractConcurrencyPolicy concurrencyPolicy) {
            runPolicy.concurrencyPolicy = concurrencyPolicy;
            return this;
        }

        /**
         * Get a new RunPolicy as configured in the Builder
         *
         * @return the RunPolicy
         */
        public RunPolicy build() {
            return new RunPolicy(runPolicy).reset();
        }
    }

    /**
     * @return Get the timestamp the job was started.
     */
    public long getTimeJobStarted() {
        return timeStarted;
    }

    /**
     * @return Get the timestamp of the next attempt, if applicable. The attempt may have
     * already started.
     */
    public long getTimeOfNextAttempt() {
        return timeOfNextAttempt;
    }

    /**
     * @return Get the time the most recent attempt started, if applicable.
     */
    public long getTimeAttemptStarted() {
        return timeAttemptStarted;
    }

    /**
     * Notify the RunPolicy that a new attempt has started
     */
    public void onAttemptStarted() {
        timeAttemptStarted = now();
        attemptsRemaining--;
        Log.d(timeStarted + " : Job attempt started at " + timeAttemptStarted + " " + jobId);
    }

    /**
     * @return True if the Job should fail due to excessive attempts or timeout.
     */
    public boolean shouldFailJob() {
        if ((timeAttemptStarted == 0 || shouldFailAttempt()) && attemptsRemaining <= 0) {
            Log.d("Job is out of retries. " + jobId);
            stateMessage = FAILED_NO_MORE_RETRIES;
            return true;
        }

        return isJobTimedOut();
    }

    /**
     * @return True if the attempt should fail due to timeout
     */
    public boolean shouldFailAttempt() {
        if (attemptsRemaining == maxJobAttempts)
            return false;

        if (isJobTimedOut())
            return true;

        if (timeAttemptStarted == 0)
            return true;

        if (now() - timeAttemptStarted >= attemptTimeout) {
            Log.d(timeStarted + " : Aborting attempt because it's more than " + attemptTimeout + " ms old " + jobId);
            return true;
        }

        return false;
    }

    /**
     * @return True if the Job's timeout has expired.
     */
    private boolean isJobTimedOut() {
        if (now() - timeStarted >= jobTimeout) {
            Log.d("Aborting job because it is more than " + jobTimeout + " ms old " + jobId);
            stateMessage = FAILED_TIMED_OUT;
            return true;
        }
        return false;
    }

    /**
     * Validate a requested state against a run policy.
     *
     * @param state The desired state to transition to
     * @return Returns the desired state if legal, or a different one if not.
     */
    // Validate a requested state against a run policy. Returns the requested state
    // if legal, or a different one if not.
    public State validateRequestedState(State state) {
        if (state.isTerminal())
            return state;

        if (shouldFailJob())
            return State.FAULTED;

        if (state == State.READY) {
            if (timeAttemptStarted > 0)
                return onAttemptFailed();

            if (timeOfNextAttempt > now())
                return State.WAIT;
        }

        if (state == State.BUSY) {
            if (timeAttemptStarted > 0 && shouldFailAttempt())
                return onAttemptFailed();
        }

        return state;
    }

    /**
     * Notify the RunPolicy of a failed attempt.
     * @return FAULTED if the RunPolicy is out of attempts, else WAIT
     */
    public State onAttemptFailed() {
        Log.d("onAttemptFailed " + jobId);
        timeOfNextAttempt = now() + delayOnFailedAttempt;
        timeAttemptStarted = 0;

        if (delayOnFailedAttemptMax > 0) {
            delayOnFailedAttempt = Math.min(delayOnFailedAttemptMax, delayOnFailedAttempt * 2);
        }

        if ((timeOfNextAttempt - timeStarted) >= jobTimeout) {
            Log.d(timeStarted + " : Job faulting because job timed out " + jobId);
            stateMessage = FAILED_TIMED_OUT;
            timeOfNextAttempt = 0;
            return State.FAULTED;
        }
        if (attemptsRemaining <= 0) {
            Log.d(timeStarted + " : Job faulting because no retries left " + jobId);
            stateMessage = FAILED_NO_MORE_RETRIES;
            timeOfNextAttempt = 0;
            return State.FAULTED;
        }
        Log.d(timeStarted + " : Next attempt at " + timeOfNextAttempt + " ; " + attemptsRemaining + " retries left " + jobId);

        stateMessage = null;
        return State.WAIT;
    }

    /**
     * Check the RunPolicy to see if it's time to start an attempt
     * @return True if an attempt should start.
     */
    public boolean shouldStart() {
        if (timeAttemptStarted > 0) {
            return false;
        }

        boolean ret = attemptsRemaining > 0 && now() >= timeOfNextAttempt;

        if (ret)
            ret = !shouldFailJob();

        if (ret && gatingConditions != null) {
            for (GatingCondition gatingCondition : gatingConditions) {
                ret = gatingCondition.isSatisfied();
                if (!ret) {
                    Log.d("Unsatisfied condition: " + stateMessage);
                    break;
                }
            }
        }

        return ret;
    }

    /**
     * Reset all attempt counts to zero and the start time to now.
     * @return the reset RunPolicy
     */
    public RunPolicy reset() {
        if (jobId != null)
            Log.d("Reset RunPolicy for job " + jobId);
        attemptsRemaining = maxJobAttempts;
        timeStarted = now();
        timeAttemptStarted = 0;
        timeOfNextAttempt = timeStarted + initialDelay;
        stateMessage = null;
        return this;
    }

    private long now() {
        return System.currentTimeMillis();
    }

    /**
     * Schedule an attempt to run as soon as possible, if any attempts remain.
     */
    public void scheduleNow() {
        timeOfNextAttempt = 0;
    }

    /**
     * @return The job timeout value as a duration in ms
     */
    public long getJobTimeout() {
        return jobTimeout;
    }

    @Override
    public String toString() {
        long now = now();
        return "attemptsRemaining:" + attemptsRemaining + " next try in " + (Math.max(0, timeOfNextAttempt - now)) + "ms jobTimeout in " + (Math.max(0, timeStarted + jobTimeout - now)) + "ms " + jobId;
    }

    /**
     * @return A message for logging, one of "No more retries", "Job timed out", or null
     */
    public String getMessage() {
        if (stateMessage != null)
            return stateMessage;

        if (gatingConditions != null) {
            for (GatingCondition gatingCondition : gatingConditions) {
                if (!gatingCondition.isSatisfied())
                    return gatingCondition.getMessage();
            }
        }

        return null;
    }

    /**
     * @return The RunPolicy's initial delay in ms
     */
    public long getInitialDelay() {
        return initialDelay;
    }

    /**
     * Convenience function to generate a RunPolicy Builder for a specified number
     * of attempts and sane defaults for retry delay, attempt timeout, and job timeout.
     *
     * @param numAttempts The max number of attempts
     * @return a RunPolicy Builder
     */
    public static Builder newLimitAttemptsPolicy(int numAttempts) {
        return new Builder()
                .withMaxAttempts(numAttempts)
                .withJobTimeout(24, TimeUnit.HOURS)
                .withAttemptTimeout(24, TimeUnit.HOURS)
                .withRetryDelay(5, TimeUnit.SECONDS);
    }

    /**
     * Convenience function to generate a RunPolicy Builder for max 5 attempts and sane defaults
     * for retry delay, attempt timeout, and job timeout.
     *
     * @return a RunPolicy builder
     */
    public static Builder newLimitAttemptsPolicy() {
        return newLimitAttemptsPolicy(5);
    }

    /**
     * Convenience function to generate a RunPolicy Builder with a specified job timeout and
     * sane defaults for retry delay, attempt timeout, and job timeout.
     *
     * @return a RunPolicy builder
     */
    public static Builder newJobTimeoutPolicy(long timeout, TimeUnit timeUnit) {
        return newLimitAttemptsPolicy(50)
                .withJobTimeout(timeout, timeUnit);
    }

    /**
     * @return The max number of job attempts as configured
     */
    public int getMaxJobAttempts() {
        return maxJobAttempts;
    }

    /**
     * @return The remaining number of attempts
     */
    public int getAttemptsRemaining() {
        return attemptsRemaining;
    }

    /**
     * @return The configured delay before attempting a retry. This value can be
     * dynamic if exponential backoff is used.
     */
    public long getDelayOnFailedAttempt() {
        return delayOnFailedAttempt;
    }

    /**
     * @return The configured maximum delay before attempting a retry. Used in
     * conjunction with exponential backoff.
     */
    public long getDelayOnFailedAttemptMax() {
        return delayOnFailedAttemptMax;
    }

    /**
     * @return The maximum age for an attempt in ms
     */
    public long getAttemptTimeout() {
        return attemptTimeout;
    }


}
