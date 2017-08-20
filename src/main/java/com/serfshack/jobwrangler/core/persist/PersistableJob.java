package com.serfshack.jobwrangler.core.persist;

public interface PersistableJob {
    PersistedData getData();

    void setData(PersistedData data);
}
