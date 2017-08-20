package com.serfshack.jobwrangler.core.persist;

import java.util.HashMap;

public class PersistedData {
    private HashMap<String, String> data = new HashMap<>();

    public String getValue(String key) {
        return data.get(key);
    }

    public void setValue(String key, String value) {
        data.put(key, value);
    }
}
