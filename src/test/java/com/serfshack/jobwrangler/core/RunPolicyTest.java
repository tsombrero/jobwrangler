package com.serfshack.jobwrangler.core;

import com.serfshack.jobwrangler.core.concurrencypolicy.AbstractConcurrencyPolicy;
import com.serfshack.jobwrangler.core.concurrencypolicy.SingletonPolicyKeepExisting;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static com.serfshack.jobwrangler.core.TestUtil.now;
import static com.serfshack.jobwrangler.core.TestUtil.sleep;
import static junit.framework.TestCase.assertFalse;
import static org.junit.Assert.*;

public class RunPolicyTest {

    @Test
    public void testBuildUpon() {
        RunPolicy rp = RunPolicy.newLimitAttemptsPolicy(6).build();
        RunPolicy rp2 = rp.buildUpon().withAttemptTimeout(1234, TimeUnit.MILLISECONDS)
                .withInitialDelay(123, TimeUnit.MILLISECONDS)
                .withJobTimeout(12345, TimeUnit.MILLISECONDS)
                .build();

        assertEquals(rp.getAttemptsRemaining(), rp2.getAttemptsRemaining());
        assertEquals(rp.getMaxJobAttempts(), rp2.getMaxJobAttempts());
        assertNotEquals(rp.getAttemptTimeout(), rp2.getAttemptTimeout());
        assertEquals(rp.getInitialDelay(), 0);
        assertEquals(rp2.getInitialDelay(), 123);
        assertEquals(rp2.getJobTimeout(), 12345);
        assertEquals(rp.getInitialDelay(), 0);
        assertEquals(rp.getJobTimeout(), RunPolicy.newLimitAttemptsPolicy().build().getJobTimeout());

        assertEquals(burnRetries(rp), 6);

        RunPolicy rp3 = rp.buildUpon().withJobTimeout(111, TimeUnit.MILLISECONDS).build();
        assertEquals(rp3.getAttemptsRemaining(), 6);
        assertTrue(rp3.shouldStart());
        assertFalse(rp.shouldStart());
        assertTrue(rp.shouldFailJob());
        assertFalse(rp3.shouldFailJob());
    }

    @Test
    public void testTimeoutPolicy() {
        RunPolicy runPolicy = RunPolicy.newJobTimeoutPolicy(400, TimeUnit.MILLISECONDS).build();
        assertTrue(runPolicy.shouldStart());
        runPolicy.onAttemptStarted();
        sleep(500);
        assertTrue(runPolicy.shouldFailJob());
        assertEquals(State.FAULTED, runPolicy.validateRequestedState(State.READY));
        assertEquals(RunPolicy.FAILED_TIMED_OUT, runPolicy.getMessage());
    }

    private static int burnRetries(RunPolicy rp) {
        int i = 0;
        while (rp.getAttemptsRemaining() > 0) {
            i++;
            rp.onAttemptStarted();
            rp.onAttemptFailed();
            rp.scheduleNow();
        }

        assertTrue(rp.shouldFailJob());

        return i;
    }

    @Test
    public void testGetStartTime() throws Exception {
        RunPolicy rp = RunPolicy.newLimitAttemptsPolicy().build();
        assertTrue(now() - rp.getTimeJobStarted() < 50);
        sleep(100);
        RunPolicy rp2 = rp.buildUpon().build();
        assertTrue(now() - rp2.getTimeJobStarted() < 50);
        assertTrue(now() - rp.getTimeJobStarted() > 90);
        rp.reset();
        assertTrue(now() - rp.getTimeJobStarted() < 50);
    }

    @Test
    public void testOnWorkStarted() throws Exception {
        RunPolicy rp = RunPolicy.newLimitAttemptsPolicy().build();
        assertEquals(0, rp.getTimeAttemptStarted());
        int attemptsLeft = rp.getAttemptsRemaining();
        rp.onAttemptStarted();
        assertTrue("now - timeStarted=" + (now() - rp.getTimeAttemptStarted()), now() - rp.getTimeAttemptStarted() < 50);
        sleep(100);
        rp.onAttemptStarted();
        assertTrue(now() - rp.getTimeAttemptStarted() < 50);
        assertEquals(attemptsLeft - 2, rp.getAttemptsRemaining());
    }

    @Test
    public void testShouldAbortAttempt() throws Exception {
        RunPolicy rp = RunPolicy.newLimitAttemptsPolicy()
                .withAttemptTimeout(50, TimeUnit.MILLISECONDS)
                .build();

        assertFalse(rp.shouldFailAttempt());
        assertTrue(rp.shouldStart());
        rp.onAttemptStarted();
        assertFalse(rp.shouldFailAttempt());
        sleep(100);
        assertTrue(rp.shouldFailAttempt());
        rp.onAttemptFailed();
        assertTrue(rp.shouldFailAttempt());
        rp.onAttemptStarted();
        assertFalse(rp.shouldFailAttempt());
        sleep(100);
        assertTrue(rp.shouldFailAttempt());

        rp.reset();
        assertFalse(rp.shouldFailAttempt());
    }

    @Test
    public void testShouldAbortJob() throws Exception {
        RunPolicy rp = RunPolicy.newLimitAttemptsPolicy()
                .withJobTimeout(100, TimeUnit.MILLISECONDS)
                .build();

        assertFalse(rp.shouldFailJob());
        sleep(110);
        assertTrue(rp.shouldFailJob());

        rp.reset();
        assertFalse(rp.shouldFailJob());
        burnRetries(rp);
        assertTrue(rp.shouldFailJob());
    }

    @Test
    public void testValidateRequestedState() throws Exception {
        RunPolicy rp = RunPolicy.newLimitAttemptsPolicy()
                .withRetryDelay(100, TimeUnit.MILLISECONDS)
                .build();

        rp.onAttemptStarted();
        for (State jobState : State.values()) {
            if (jobState != State.READY) {
                assertEquals(jobState, rp.validateRequestedState(jobState));
            }
        }

        assertEquals(State.WAIT, rp.validateRequestedState(State.READY));
        assertEquals(0, rp.getTimeAttemptStarted());
        assertEquals(rp.getMaxJobAttempts() - 1, rp.getAttemptsRemaining());

        for (State jobState : State.values()) {
            if (jobState == State.READY) {
                assertEquals(State.WAIT, rp.validateRequestedState(jobState));
            } else {
                assertEquals(jobState, rp.validateRequestedState(jobState));
            }
        }

        rp.onAttemptStarted();
        sleep(120);
        for (State jobState : State.values()) {
            if (jobState == State.READY) {
                assertEquals(State.WAIT, rp.validateRequestedState(jobState));
            } else {
                assertEquals(jobState, rp.validateRequestedState(jobState));
            }
        }

        burnRetries(rp);
        for (State jobState : State.values()) {
            if (jobState.isTerminal())
                assertEquals(jobState, rp.validateRequestedState(jobState));
            else
                assertEquals(State.FAULTED, rp.validateRequestedState(jobState));
        }
    }


    @Test
    public void testOnFailedAttempt() throws Exception {
        RunPolicy rp = RunPolicy.newLimitAttemptsPolicy(1).build();
        assertTrue(rp.shouldStart());
        assertFalse(rp.shouldFailJob());
        assertFalse(rp.shouldFailAttempt());
        rp.onAttemptStarted();
        assertFalse(rp.shouldFailJob());
        assertFalse(rp.shouldFailAttempt());
        rp.onAttemptFailed();
        assertTrue(rp.shouldFailJob());
        assertTrue(rp.shouldFailAttempt());
        assertEquals(State.FAULTED, rp.validateRequestedState(State.READY));
    }

    @Test
    public void testShouldStart() throws Exception {
        RunPolicy rp = RunPolicy.newLimitAttemptsPolicy()
                .withRetryDelay(100, TimeUnit.MILLISECONDS)
                .withInitialDelay(100, TimeUnit.MILLISECONDS)
                .build();

        assertFalse(rp.shouldStart());
        sleep(rp.getInitialDelay() + 20);
        assertTrue(rp.shouldStart());
        rp.onAttemptFailed();
        assertFalse(rp.shouldStart());
        sleep(rp.getDelayOnFailedAttempt() + 20);
        assertTrue(rp.shouldStart());
        rp.onAttemptStarted();
        assertFalse(rp.shouldStart());
        rp.onAttemptFailed();
        assertFalse(rp.shouldStart());
        rp.scheduleNow();
        assertTrue(rp.shouldStart());
        burnRetries(rp);
        assertFalse(rp.shouldStart());
    }

    @Test
    public void testReset() throws Exception {
        RunPolicy rp = RunPolicy.newLimitAttemptsPolicy().build();
        sleep(20);
        RunPolicy rp2 = RunPolicy.newLimitAttemptsPolicy().build();
        assertTrue(rp.getTimeOfNextAttempt() < rp2.getTimeOfNextAttempt());
        assertTrue(rp.getTimeJobStarted() < rp2.getTimeJobStarted());
        sleep(20);
        rp.reset();
        assertTrue(rp.getTimeOfNextAttempt() > rp2.getTimeOfNextAttempt());
        assertTrue(rp.getTimeJobStarted() > rp2.getTimeJobStarted());

        burnRetries(rp2);
        sleep(20);
        rp2.reset();
        assertNull(rp2.getMessage());

        assertEquals(rp.getAttemptsRemaining(), rp2.getAttemptsRemaining());
        assertTrue(rp.getTimeOfNextAttempt() + ">=" + rp2.getTimeOfNextAttempt(), rp.getTimeOfNextAttempt() < rp2.getTimeOfNextAttempt());
        assertTrue(rp.getTimeJobStarted() < rp2.getTimeJobStarted());

        rp = rp.buildUpon().withJobTimeout(50, TimeUnit.MILLISECONDS).build();
        rp.onAttemptStarted();
        sleep(rp.getJobTimeout() + 20);
        assertTrue(rp.shouldFailAttempt());
        assertTrue(rp.shouldFailJob());
        rp.reset();
        assertFalse(rp.shouldFailAttempt());
        assertFalse(rp.shouldFailJob());
    }

    @Test
    public void testScheduleNow() throws Exception {
        RunPolicy rp = RunPolicy.newLimitAttemptsPolicy()
                .withInitialDelay(1, TimeUnit.SECONDS)
                .withRetryDelay(1, TimeUnit.SECONDS)
                .build();

        assertFalse(rp.shouldStart());
        assertEquals(State.WAIT, rp.validateRequestedState(State.READY));
        rp.scheduleNow();
        assertTrue(rp.shouldStart());
        assertEquals(State.READY, rp.validateRequestedState(State.READY));

        rp.onAttemptStarted();
        rp.onAttemptFailed();
        assertFalse(rp.shouldStart());
        assertEquals(State.WAIT, rp.validateRequestedState(State.READY));
        rp.scheduleNow();
        assertTrue(rp.shouldStart());
        assertEquals(State.READY, rp.validateRequestedState(State.READY));
    }

    @Test
    public void testGetJobTimeout() throws Exception {
        RunPolicy rp = RunPolicy.newJobTimeoutPolicy(10, TimeUnit.DAYS).build();
        assertEquals(rp.getJobTimeout(), TimeUnit.DAYS.toMillis(10));
        burnRetries(rp);
        assertEquals(rp.getJobTimeout(), TimeUnit.DAYS.toMillis(10));
        rp = rp.buildUpon().withJobTimeout(10, TimeUnit.MILLISECONDS).build();
        assertEquals(rp.getJobTimeout(), 10);
    }

    @Test
    public void testGetStateReason() throws Exception {
        RunPolicy rp = RunPolicy.newJobTimeoutPolicy(20, TimeUnit.MILLISECONDS).build();
        assertEquals(null, rp.getMessage());
        rp.onAttemptStarted();
        sleep(rp.getJobTimeout() + 20);
        rp.onAttemptFailed();
        assertEquals(RunPolicy.FAILED_TIMED_OUT, rp.getMessage());
        rp.reset();
        assertEquals(null, rp.getMessage());

        rp = RunPolicy.newLimitAttemptsPolicy().build();
        burnRetries(rp);
        assertEquals(RunPolicy.FAILED_NO_MORE_RETRIES, rp.getMessage());
        rp.reset();
        assertEquals(null, rp.getMessage());
    }

    @Test
    public void testGetInitialDelay() throws Exception {
        RunPolicy rp = RunPolicy.newJobTimeoutPolicy(1, TimeUnit.MINUTES).build();
        assertEquals(0, rp.getInitialDelay());
        rp = rp.buildUpon().withInitialDelay(200, TimeUnit.DAYS).build();
        assertEquals(TimeUnit.DAYS.toMillis(200), rp.getInitialDelay());
        rp = rp.buildUpon().withInitialDelay(200, TimeUnit.MILLISECONDS).build();
        assertEquals(200, rp.getInitialDelay());
        rp.reset();
        assertEquals(200, rp.getInitialDelay());
        burnRetries(rp);
        assertEquals(200, rp.getInitialDelay());
        RunPolicy rp2 = rp.buildUpon().withExponentialBackoff().build();
        assertEquals(200, rp2.getInitialDelay());
    }

    @Test
    public void testGetMaxAttempts() throws Exception {
        RunPolicy rp = RunPolicy.newLimitAttemptsPolicy(2).build();
        assertEquals(2, rp.getMaxJobAttempts());
        burnRetries(rp);
        assertEquals(2, rp.getMaxJobAttempts());
        rp.reset();
        assertEquals(2, rp.getMaxJobAttempts());
        RunPolicy rp2 = rp.buildUpon().withExponentialBackoff().build();
        assertEquals(2, rp2.getMaxJobAttempts());
    }

    @Test
    public void testGetAttemptsLeft() throws Exception {
        RunPolicy rp = RunPolicy.newLimitAttemptsPolicy(3).build();
        assertEquals(3, rp.getAttemptsRemaining());
        rp.onAttemptStarted();
        assertEquals(2, rp.getAttemptsRemaining());
        rp.onAttemptStarted();
        assertEquals(1, rp.getAttemptsRemaining());
        rp.onAttemptFailed();
        assertEquals(1, rp.getAttemptsRemaining());
        rp.onAttemptStarted();
        assertEquals(0, rp.getAttemptsRemaining());
    }

    @Test
    public void testGetRetryDelay() throws Exception {
        RunPolicy rp = RunPolicy.newLimitAttemptsPolicy(3)
                .withRetryDelay(1, TimeUnit.MINUTES).build();
        assertEquals(TimeUnit.MINUTES.toMillis(1), rp.getDelayOnFailedAttempt());
        burnRetries(rp);
        assertEquals(TimeUnit.MINUTES.toMillis(1), rp.getDelayOnFailedAttempt());
        rp.reset();
        assertEquals(TimeUnit.MINUTES.toMillis(1), rp.getDelayOnFailedAttempt());
        rp = rp.buildUpon().withRetryDelay(2, TimeUnit.MINUTES).build();
        assertEquals(TimeUnit.MINUTES.toMillis(2), rp.getDelayOnFailedAttempt());
    }

    @Test
    public void testExponentialBackoff() throws Exception {
        RunPolicy rp = RunPolicy.newLimitAttemptsPolicy(10)
                .withExponentialBackoff(100, 400, TimeUnit.MILLISECONDS)
                .build();

        assertTrue(rp.shouldStart());
        assertEquals(100, rp.getDelayOnFailedAttempt());
        rp.onAttemptStarted();
        assertEquals(100, rp.getDelayOnFailedAttempt());
        rp.onAttemptFailed();
        assertEquals(200, rp.getDelayOnFailedAttempt());
        assertEquals(100, roundToNearest(25, rp.getTimeOfNextAttempt() - now()));
        rp.onAttemptStarted();
        rp.onAttemptFailed();
        assertEquals(200, roundToNearest(25, rp.getTimeOfNextAttempt() - now()));
        rp.onAttemptStarted();
        rp.onAttemptFailed();
        assertEquals(400, roundToNearest(25, rp.getTimeOfNextAttempt() - now()));
        rp.scheduleNow();
        rp.onAttemptStarted();
        rp.onAttemptFailed();
        assertEquals(400, roundToNearest(25, rp.getTimeOfNextAttempt() - now()));
        rp.onAttemptStarted();
        rp.onAttemptFailed();
        assertEquals(400, roundToNearest(25, rp.getTimeOfNextAttempt() - now()));
        assertEquals(400, rp.getDelayOnFailedAttempt());
        assertFalse(rp.shouldStart());
        sleep(rp.getDelayOnFailedAttempt() + 20);
        assertTrue(rp.shouldStart());
    }

    @Test
    public void testRoundToNearest() {
        assertEquals(100, roundToNearest(20, 109));
        assertEquals(100, roundToNearest(20, 90));
        assertEquals(100, roundToNearest(100, 50));
        assertEquals(100, roundToNearest(100, 149));
        assertEquals(0, roundToNearest(100, 49));
        assertEquals(0, roundToNearest(100, 1));
        assertEquals(0, roundToNearest(100, 0));
        assertEquals(123, roundToNearest(1, 123));
        assertEquals(123, roundToNearest(123, 123));
    }

    static long roundToNearest(long unit, long number) {
        number += (unit / 2);
        number /= unit;
        number *= unit;
        return number;
    }

    @Test
    public void testGetAttemptStartedTime() throws Exception {
        RunPolicy rp = RunPolicy.newLimitAttemptsPolicy().build();
        assertEquals(0, rp.getTimeAttemptStarted());
        rp.onAttemptStarted();
        assertEqualsSloppy(20, now(), rp.getTimeAttemptStarted());
        rp.onAttemptFailed();
        assertEquals(0, rp.getTimeAttemptStarted());
        sleep(50);
        rp.onAttemptStarted();
        assertEqualsSloppy(20, now(), rp.getTimeAttemptStarted());
        rp.onAttemptFailed();
        assertEquals(0, rp.getTimeAttemptStarted());
    }


    static class SimpleGatingCondition implements GatingCondition {

        final static String WAIT = "SimpleGatingCondition WAIT";
        final static String READY = "SimpleGatingCondition READY";

        public boolean isReady;

        @Override
        public boolean isSatisfied() {
            return isReady;
        }

        @Override
        public String getMessage() {
            return isReady ? READY : WAIT;
        }
    }

    @Test
    public void testGatingCondition() {
        SimpleGatingCondition gatingCondition = new SimpleGatingCondition();

        RunPolicy rp = RunPolicy.newLimitAttemptsPolicy()
                .withGatingCondition(gatingCondition)
                .withRetryDelay(0, TimeUnit.MILLISECONDS)
                .build();

        assertFalse(rp.shouldStart());
        assertEquals(SimpleGatingCondition.WAIT, rp.getMessage());

        gatingCondition.isReady = true;
        assertTrue(rp.shouldStart());
        assertEquals(SimpleGatingCondition.READY, gatingCondition.getMessage());
        assertNull(rp.getMessage());

        rp.onAttemptStarted();
        assertFalse(rp.shouldStart());
        assertNull(rp.getMessage());

        gatingCondition.isReady = false;
        assertFalse(rp.shouldStart());

        rp.onAttemptFailed();

        assertFalse(rp.shouldStart());
        gatingCondition.isReady = true;
        assertTrue(rp.shouldStart());
    }

    @Test
    public void testMultiGatingConditions() {
        SimpleGatingCondition gc1 = new SimpleGatingCondition();
        SimpleGatingCondition gc2 = new SimpleGatingCondition();

        RunPolicy rp = RunPolicy.newLimitAttemptsPolicy()
                .withGatingCondition(gc1)
                .withGatingCondition(gc2)
                .build();

        assertFalse(rp.shouldStart());

        gc1.isReady = true;
        assertFalse(rp.shouldStart());

        gc2.isReady = true;
        assertTrue(rp.shouldStart());

        gc1.isReady = false;
        assertFalse(rp.shouldStart());

        gc2.isReady = false;
        assertFalse(rp.shouldStart());
    }

    @Test
    public void testSetConcurrencyPolicy() {
        AbstractConcurrencyPolicy cp = new SingletonPolicyKeepExisting("foo");
        RunPolicy rp = RunPolicy.newLimitAttemptsPolicy()
                .withConcurrencyPolicy(cp)
                .build();

        assertEquals(rp.getConcurrencyPolicy(), cp);

        rp = RunPolicy.newLimitAttemptsPolicy()
                .withConcurrencyPolicy(new SingletonPolicyKeepExisting("bar"))
                .withConcurrencyPolicy(cp) // overwrites the first one
                .build();

        assertEquals(rp.getConcurrencyPolicy(), cp);
    }

    @Test
    public void testAbstractConcurrencyPolicy() {
        AbstractConcurrencyPolicy cp = new SingletonPolicyKeepExisting("foo");
        assertTrue(cp.equals(new SingletonPolicyKeepExisting("foo")));
        assertFalse(cp.equals(new SingletonPolicyKeepExisting("bar")));

        Object foo = new Object();
        cp = new SingletonPolicyKeepExisting("foo", foo);
        assertTrue(cp.equals(new SingletonPolicyKeepExisting("foo", foo)));
        assertFalse(cp.equals(new SingletonPolicyKeepExisting(foo, foo)));
        assertFalse(cp.equals(new SingletonPolicyKeepExisting("foo", "foo")));
    }

    @Test
    public void testAssertEqualsSloppy() {
        assertEqualsSloppy(10, 1, 10);
        assertEqualsSloppy(10, 100, 99);
        try {
            assertEqualsSloppy(10, 1, 12);
            assertTrue(false);
        } catch (Throwable t) {
        }
    }

    void assertEqualsSloppy(int slop, long expected, long actual) {
        assertTrue(Math.abs(expected - actual) <= slop);
    }

}
