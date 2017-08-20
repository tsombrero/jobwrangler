package com.serfshack.jobwrangler.core;

/**
 * A comparator that attempts to order jobs by start time or dependency.
 */
public class PriorityJobComparator implements java.util.Comparator<Job> {
    @Override
    public int compare(Job j1, Job j2) {
        if (j1.getId().equals(j2.getId()))
            return 0;

        if (j1.getState().isTerminal() && j2.getState().isTerminal())
            return 0;

        if (j1.getState().isTerminal())
            return 1;

        if (j2.getState().isTerminal())
            return -1;

        if (j1.getDependingMode(j2.getId()) != null)
            return 1;

        if (j2.getDependingMode(j1.getId()) != null)
            return -1;

        if (j1.getState() == State.NEW && j2.getState() != State.NEW)
            return -1;

        if (j2.getState() == State.NEW && j1.getState() != State.NEW)
            return 1;

        if (j1.getState() == State.BUSY && j2.getState() != State.BUSY)
            return 1;

        if (j2.getState() == State.BUSY && j1.getState() != State.BUSY)
            return -1;

        if (j1.getDependedDependables().size() < j2.getDependedDependables().size())
            return -1;

        if (j1.getDependedDependables().size() > j2.getDependedDependables().size())
            return 1;

        return Long.compare(j1.getRunPolicy().getTimeJobStarted(), j2.getRunPolicy().getTimeJobStarted());
    }
}
