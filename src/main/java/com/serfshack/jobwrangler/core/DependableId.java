package com.serfshack.jobwrangler.core;

public class DependableId implements CharSequence {
    private final String id;

    public DependableId(String id) {
        if (id == null)
            throw new NullPointerException("CommonResource ID cannot be null");
        this.id = id;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof DependableId)
            return id.equals(((DependableId) obj).id);
        return false;
    }

    @Override
    public int length() {
        return id.length();
    }

    @Override
    public char charAt(int index) {
        return id.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return id.subSequence(start, end);
    }

    @Override
    public String toString() {
        return id;
    }
}
