package com.serfshack.jobwrangler.core;

/**
 * A RunPolicy may have zero or more GatingConditions. A Job will not proceed beyond WAIT (and onPrepare() will not be
 * called) until all the RunPolicy's GatingConditions are satisfied.
 *
 * For example, if a job requires network, create a GatingCondition that checks for connectivity and assign that
 * GatingCondition to the job's RunPolicy.
 */
public interface GatingCondition {
    /**
     * @return True if the condition is satisfied.
     */
    boolean isSatisfied();

    /**
     * @return A message describing the gating condition (for logging)
     */
    String getMessage();
}
