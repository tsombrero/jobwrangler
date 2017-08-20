package com.serfshack.jobwrangler.core;

import org.junit.Assert;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

public class TestUtil {
    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static long now() {
        return System.currentTimeMillis();
    }

    public static boolean IS_DEBUGGING = true;

    public static void waitUntilAdded(JobObserver job) {
        if (IS_DEBUGGING) {
            job.waitUntilAdded(999999, TimeUnit.MILLISECONDS);
        } else {
            long t = System.currentTimeMillis();
            job.waitUntilAdded(1000, TimeUnit.MILLISECONDS);
            if (System.currentTimeMillis() - t > 700)
                fail("Adding took too long");
        }

        Assert.assertNotEquals(job.getState(), State.NEW);
    }

    public static void waitUntilCompleted(JobObserver job) {
        if (IS_DEBUGGING) {
            job.waitForTerminalState(999999, TimeUnit.MILLISECONDS);
        } else {
            long t = System.currentTimeMillis();
            job.waitForTerminalState(5000, TimeUnit.MILLISECONDS);
            if (System.currentTimeMillis() - t > 4000)
                fail("Job took too long to finish");
        }

        assertNotEquals(job.getState(), State.NEW);
    }

    static void assertFutureState(State state, Future<State> future) {
        try {
            assertEquals(state, future.get());
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
            fail();
        }
    }
    static void setJobState(State state, Job job) {
        try {
            assertEquals(state, job.setState(state).get(1, TimeUnit.SECONDS));
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            e.printStackTrace();
            fail("Failed setting state to " + state);
        }
    }

    public static boolean isImmediateFuture(Future<State> future) {
        return future.getClass().getSimpleName().contains("ImmediateSuccessfulFuture");
    }

    interface Condition {
        boolean isSatisfied();
        void onFail();
    }

    public static void assertTimedCondition(Condition condition, long msTimeout) {
        long endTime = System.currentTimeMillis() + msTimeout;
        while (System.currentTimeMillis() < endTime) {
            if (condition.isSatisfied())
                return;
            sleep(20);
        }
        condition.onFail();
    }
}
