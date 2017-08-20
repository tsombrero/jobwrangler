package com.serfshack.jobwrangler.core;

import org.junit.Before;
import org.junit.Test;

public class JobObserverTest {
    private JobManager jobManager;

    @Before
    public void init() {
        jobManager = new JobManager(null);
        ServiceJobTask.DEFAULT_POLL_INTERVAL = 50;
    }

    @Test
    public void testCallback() {

    }

}
