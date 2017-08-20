package com.serfshack.jobwrangler.core;

/**
 * A basic Job implementation to use if you don't care about the result.
 */
public abstract class SimpleJob extends Job<Boolean> {

    @Override
    protected Boolean getResult() {
        return true;
    }

    @Override
    protected RunPolicy configureRunPolicy() {
        return RunPolicy.newLimitAttemptsPolicy().build();
    }
}
