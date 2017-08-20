package com.serfshack.jobwrangler.core.persist;

import com.serfshack.jobwrangler.core.Job;

import java.util.List;

public interface Persistor {
    List<Job> getJobs();

    void putJob(Job job);

    void removeJob(Job.JobId jobId);

    void clear();
}
