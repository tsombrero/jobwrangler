package com.serfshack.jobwrangler.core;

/**
 * State Flow Diagram:
 * https://github.com/tsombrero/jobwrangler/blob/master/docs/res/stateflow.png
 */
public enum State {
    /**
     * Initial state.
     * Next job lifecycle callback: onAdded()
     */
    NEW,

    /**
     * Initialized and Added but blocked on some condition.
     * Next Job lifecycle callback: onPrepare()
     */
    WAIT,

    /**
     * Ready for processing.
     * Next Job lifecycle callback: doWork()
     */
    READY,

    /**
     * Job attempt is actively underway
     * Next Job lifecycle callback: checkProgress()
     */
    BUSY,

    /**
     * Job completed successfully
     */
    SUCCEEDED,

    /**
     * Job faulted
     */
    FAULTED,

    /**
     * Job was deliberately canceled
     */
    CANCELED,

    /**
     *  Job was assimilated into another job of the same type, see getAssimilatedBy()
     *  Jobs may not be assimilated once they are added (no longer in NEW state).
     */
    ASSIMILATED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAULTED || this == CANCELED || this == ASSIMILATED;
    }

    public boolean isFailed() {
        return this == FAULTED || this == CANCELED;
    }

    public boolean isInWorkLoop() {
        return this == WAIT || this == READY || this == BUSY;
    }

    public boolean transitionIsPersistable(State newState) {
        if (this.isInWorkLoop())
            return newState.isTerminal();

        return newState != this;
    }

    public boolean isPreExecute() {
        return this == NEW || this == WAIT || this == READY;
    }
}
