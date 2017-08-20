package com.serfshack.jobwrangler.core.concurrencypolicy;

import com.serfshack.jobwrangler.core.Job;

/**
 * Base class for Concurrency Policies. Typical uses are to enforce singleton Jobs or
 * to ensure Jobs of some category are run in series.
 *
 * Concurrency Policies are identified by keys. A Job may have one concurrency policy.
 * When a Job is submitted to the JobManager its concurrency policy is validated against
 * those of existing Jobs.
 *
 * If multiple active Jobs have concurrency policies with matching keys, those Jobs are said
 * to collide. See onCollision()
 *
 * See FIFOPolicy, SingletonPolicyKeepExisting, SingletonPolicyReplaceExisting
 *
 */
abstract public class AbstractConcurrencyPolicy {

    private final Object[] key;

    /**
     * Constructor
     *
     * @param key Identifies this policy.
     */
    public AbstractConcurrencyPolicy(Object... key) {
        if (key == null || key.length == 0)
            throw new IllegalArgumentException("AbstractConcurrencyPolicy requries a key");

        for (Object o : key) {
            if (o == null)
                throw new IllegalArgumentException("AbstractConcurrencyPolicy key components cannot be null");
        }
        this.key = key;
    }

    /**
     * @param otherConcurrencyPolicy The concurrency policy from a Job candidate.
     * @return true iff the provided concurrency policy is AbstractConcurrencyPolicy with the same key
     */
    @Override
    public final boolean equals(Object otherConcurrencyPolicy) {
        if (otherConcurrencyPolicy == null || !(otherConcurrencyPolicy instanceof AbstractConcurrencyPolicy))
            return false;

        if (!getClass().equals(otherConcurrencyPolicy.getClass()))
            return false;

        AbstractConcurrencyPolicy other = (AbstractConcurrencyPolicy) otherConcurrencyPolicy;
        if (other.key.length != key.length)
            return false;

        for (int i = 0; i < key.length; i++) {
            if (!key[i].equals(other.key[i]))
                return false;
        }

        return true;
    }

    /**
     * The ConcurrencyPolicy's onCollision implementation determines what happens next
     * when a Job is submitted and its concurrency policy matches that of an existing Job.
     *
     * Do whatever is needed here, for example cancel one job or the other, consolidate
     * jobs with job.assimilate(), or set a dependency. See implementations in
     * FIFOPolicy, SingletonPolicyKeepExisting, and SingletonPolicyReplaceExisting.
     *
     * @param existingJob The existing Job
     * @param candidate The Job that was added
     */
    public abstract void onCollision(Job existingJob, Job candidate);
}
