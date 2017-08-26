package com.serfshack.jobwrangler.core;

import com.serfshack.jobwrangler.core.concurrencypolicy.FIFOPolicy;
import com.serfshack.jobwrangler.core.concurrencypolicy.SingletonPolicyKeepExisting;
import com.serfshack.jobwrangler.core.concurrencypolicy.SingletonPolicyReplaceExisting;
import org.junit.Test;

import static junit.framework.TestCase.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConcurrencyPolicyTest {

    @Test
    public void testFifoPolicy() {
        BasicJobTest.EasyJob job1 = new BasicJobTest.EasyJob();
        BasicJobTest.EasyJob job2 = new BasicJobTest.EasyJob();
        job1.setRunPolicy(RunPolicy.newLimitAttemptsPolicy().withConcurrencyPolicy(new FIFOPolicy("foo")).build());
        job2.setRunPolicy(RunPolicy.newLimitAttemptsPolicy().withConcurrencyPolicy(new FIFOPolicy("foo")).build());
        assertTrue(job1.getRunPolicy().getConcurrencyPolicy().equals(job2.getRunPolicy().getConcurrencyPolicy()));
        assertFalse(job1.getRunPolicy().getConcurrencyPolicy().equals(new FIFOPolicy("foo", "bar")));
        assertFalse(job1.getRunPolicy().getConcurrencyPolicy().equals(new SingletonPolicyKeepExisting("foo")));
        job1.getRunPolicy().getConcurrencyPolicy().onCollision(job1, job2);
        assertTrue(job2.getDependedDependables().containsKey(job1));
        assertTrue(job1.getDependedDependables().isEmpty());
    }

    @Test
    public void testSingletonKeepExisting() {
        BasicJobTest.EasyJob job1 = new BasicJobTest.EasyJob();
        BasicJobTest.EasyJob job2 = new BasicJobTest.EasyJob();
        job1.setRunPolicy(RunPolicy.newLimitAttemptsPolicy().withConcurrencyPolicy(new SingletonPolicyKeepExisting("foo")).build());
        job2.setRunPolicy(RunPolicy.newLimitAttemptsPolicy().withConcurrencyPolicy(new SingletonPolicyKeepExisting("foo")).build());
        assertTrue(job1.getRunPolicy().getConcurrencyPolicy().equals(job2.getRunPolicy().getConcurrencyPolicy()));
        assertFalse(job1.getRunPolicy().getConcurrencyPolicy().equals(new SingletonPolicyKeepExisting("foo", "bar")));
        assertFalse(job1.getRunPolicy().getConcurrencyPolicy().equals(new FIFOPolicy("foo")));
        job1.getRunPolicy().getConcurrencyPolicy().onCollision(job1, job2);
        job2.waitForTerminalState(100);
        assertEquals(job1.getState(), State.NEW);
        assertEquals(job2.getState(), State.ASSIMILATED);
    }

    @Test
    public void testSingletonReplaceExisting() {
        BasicJobTest.EasyJob job1 = new BasicJobTest.EasyJob();
        BasicJobTest.EasyJob job2 = new BasicJobTest.EasyJob();
        job1.setRunPolicy(RunPolicy.newLimitAttemptsPolicy().withConcurrencyPolicy(new SingletonPolicyReplaceExisting("foo")).build());
        job2.setRunPolicy(RunPolicy.newLimitAttemptsPolicy().withConcurrencyPolicy(new SingletonPolicyReplaceExisting("foo")).build());
        assertTrue(job1.getRunPolicy().getConcurrencyPolicy().equals(job2.getRunPolicy().getConcurrencyPolicy()));
        assertFalse(job1.getRunPolicy().getConcurrencyPolicy().equals(new SingletonPolicyReplaceExisting("foo", "bar")));
        assertFalse(job1.getRunPolicy().getConcurrencyPolicy().equals(new FIFOPolicy("foo")));
        job1.getRunPolicy().getConcurrencyPolicy().onCollision(job1, job2);
        job1.waitForTerminalState(100);
        assertEquals(job1.getState(), State.ASSIMILATED);
        assertEquals(job2.getState(), State.NEW);
    }

}
