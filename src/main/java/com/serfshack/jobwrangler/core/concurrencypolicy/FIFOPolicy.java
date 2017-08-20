package com.serfshack.jobwrangler.core.concurrencypolicy;

import com.serfshack.jobwrangler.core.Dependable;
import com.serfshack.jobwrangler.core.Job;

/**
 * Policy to ensure Jobs are run sequentially rather than in parallel.
 *
 * The new job will not proceed to the READY state until any earlier job(s) with a matching policy reach a
 * terminal state. Failure of a depended job does not cascade to the depending job.
 */
public class FIFOPolicy extends AbstractConcurrencyPolicy {

    public FIFOPolicy(Object... key) {
        super(key);
    }

    @Override
    public void onCollision(Job existing, Job candidate) {
        if (!candidate.isDependingOn(existing.getId()))
           candidate.addDepended(existing, Dependable.DependencyFailureStrategy.IGNORE_FAILURE);
    }
}
