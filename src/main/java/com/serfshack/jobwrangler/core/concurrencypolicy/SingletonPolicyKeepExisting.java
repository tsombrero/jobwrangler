package com.serfshack.jobwrangler.core.concurrencypolicy;

import com.serfshack.jobwrangler.core.Dependable;
import com.serfshack.jobwrangler.core.Job;

/**
 * Ensures only one Job with this policy runs at a time. Can be combined with a start delay to implement
 * throttling/debouncing strategies.
 *
 * If an existing job matches, the existing job's assimilate() is called with the candidate and the
 * candidate job is canceled.
 */
public class SingletonPolicyKeepExisting extends AbstractConcurrencyPolicy {

    public SingletonPolicyKeepExisting(Object... key) {
        super(key);
    }

    @Override
    public void onCollision(Job existing, Job candidate) {
        if (existing == candidate || existing.getId().equals(candidate.getId()))
            return;

        if (existing.assimilate(candidate))
            candidate.setAssimilatedBy(existing);
        else
            candidate.addDepended(existing, Dependable.DependencyFailureStrategy.IGNORE_FAILURE);
    }
}
