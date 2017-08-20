package com.serfshack.jobwrangler.core.concurrencypolicy;

import com.serfshack.jobwrangler.core.Dependable;
import com.serfshack.jobwrangler.core.Job;

/**
 * Ensures only one Job with this policy runs at a time. Newest Job wins. Useful when a newer job supersedes
 * an older one before the older one has executed.
 *
 * If an existing job matches, the existing job is canceled and the candidate job's assimilate() is called
 * with the existing job.
 */
public class SingletonPolicyReplaceExisting extends AbstractConcurrencyPolicy {

    public SingletonPolicyReplaceExisting(Object... key) {
        super(key);
    }

    @Override
    public void onCollision(Job existing, Job candidate) {
        if (candidate.assimilate(existing))
            existing.setAssimilatedBy(candidate);
        else
            existing.addDepended(candidate, Dependable.DependencyFailureStrategy.IGNORE_FAILURE);
    }
}
