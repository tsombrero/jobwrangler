package com.serfshack.jobwrangler.core;

import com.serfshack.jobwrangler.util.Log;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Base class to contain dependency logic for Jobs
 */
public abstract class Dependable {

    public abstract State getState();

    final ReentrantLock lock = new ReentrantLock();
    private JobManager jobManager;

    private ConcurrentHashMap<DependableId, DependencyFailureStrategy> dependedDependables = new ConcurrentHashMap<>();
    private DependableId id;

    Dependable(DependableId id) {
        this.id = id;
    }

    public static enum DependencyFailureStrategy {
        CASCADE_FAILURE, IGNORE_FAILURE
    }

    /**
     * Retrieve ID's of Dependables that this Dependable depends on directly.
     *
     * @return A map of ID's to DependencyFailureStrategy values;
     */
    Map<DependableId, DependencyFailureStrategy> getDependedDependables() {
        try {
            return new HashMap<>(dependedDependables);
        } catch (Throwable t) {
            // concurrency? try again with the lock
            lock.lock();
            try {
                return new HashMap<>(dependedDependables);
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * An initialized Dependency is associated with a JobManager.
     *
     * @return JobManager, or null if not initialized
     */
    public final JobManager getJobManager() {
        if (jobManager != null)
            return jobManager;

        lock.lock();
        try {
            return jobManager;
        } finally {
            lock.unlock();
        }
    }

    final void setJobManager(JobManager jobManager) {
        this.jobManager = jobManager;
    }

    /**
     * Remove a dependency
     *
     * @param dependableId ID of the depended Dependable
     * @return true if something was removed
     */
    public final boolean removeDepended(DependableId dependableId) {
        return dependedDependables.remove(dependableId) != null;
    }

    /**
     * Check to see if this Dependable depends directly on another one
     *
     * @param id ID of the possible dependency
     * @return one of DependencyFailureStrategy if this Dependable depends directly on ID, or null
     */
    public final DependencyFailureStrategy getDependingMode(DependableId id) {
        return dependedDependables.get(id);
    }

    /**
     * Check to see if this Dependable depends directly on another one
     *
     * @param dependable The possible dependency
     * @return one of DependencyFailureStrategy if this Dependable depends directly on ID, or null
     */
    public final DependencyFailureStrategy getDependingMode(Dependable dependable) {
        return getDependingMode(dependable.getId());
    }

    /**
     *
     * @return ID of this Dependable
     */
    public DependableId getId() {
        return id;
    }

    /**
     * @return Description, defaults to the class name
     */
    public String getDescription() {
        return getClass().getSimpleName();
    }

    final void cycleCheck() {
        cycleCheck(jobManager);
    }

    /**
     * Check the state of the dependency graph looking for cycles.
     *
     * @param jobManager JobManager for context
     * @throws DependencyCycleException
     */
    final void cycleCheck(JobManager jobManager) {
        lock.lock();
        try {
            if (jobManager == null)
                return;

            for (DependableId dependableId : dependedDependables.keySet()) {
                cycleCheck(dependableId, jobManager);
            }
        } finally {
            lock.unlock();
        }
    }

    private void cycleCheck(DependableId dependedId, JobManager jobManager) {
        Dependable depended = jobManager.getDependable(dependedId);
        if (depended != null) {
            if (depended.getDependingMode(this) != null)
                throw new DependencyCycleException(this, depended);

            for (DependableId indirectDepended : depended.getDependedDependables().keySet()) {
                cycleCheck(indirectDepended, jobManager);
            }
        }
    }

    public static class DependencyCycleException extends RuntimeException {
        private String message;

        DependencyCycleException(DependableId a, DependableId b) {
            this("DependencyCycleException between " + a + " and " + b);
        }

        DependencyCycleException(Dependable a, Dependable b) {
            this("DependencyCycleException between " + a + " and " + b);
        }

        DependencyCycleException(String message) {
            this.message = message;
        }

        public String toString() {
            return getMessage();
        }

        @Override
        public String getMessage() {
            return message;
        }

        @Override
        public String getLocalizedMessage() {
            return getMessage();
        }
    }

    public static class DependencyException extends RuntimeException {
        public DependencyException(String s) {
            super(s);
        }
    }

    public boolean isSatisfied() {
        return getState() == State.SUCCEEDED || getState() == State.ASSIMILATED;
    }

    /**
     * Add a hard dependency with the CASCADE_FAILURE DependencyFailureStrategy.
     *
     * @param dependable The depended
     *
     * @throws DependencyCycleException Adding this dependency would create a cycle
     * @throws IllegalStateException The proposed dependency is not active in the JobManager
     */
    public void addDepended(Dependable dependable) {
        addDepended(dependable, DependencyFailureStrategy.CASCADE_FAILURE);
    }

    /**
     * Add a dependency with the specified DependencyFailureStrategy.
     *
     * @param dependable The depended
     * @param inheritFailure The DependencyFailureStrategy to apply. Overwrites any
     *                       existing DependencyFailureStrategy.
     *
     * @throws DependencyCycleException Adding this dependency would create a cycle
     * @throws IllegalStateException The proposed dependency is not active in the JobManager
     */
    public void addDepended(Dependable dependable, DependencyFailureStrategy inheritFailure) {
        DependableId dependedId = dependable.getId();

        if (dependedId.equals(getId()))
            throw new DependencyCycleException(getId(), getId());

        if (jobManager != null && jobManager.getDependable(dependedId) == null)
            throw new DependencyException("Depended job [" + dependable + "] is not active");

        Log.d("isDependingOn: " + this + " depends on " + dependable);

        lock.lock();
        try {
            try {
                dependedDependables.put(dependedId, inheritFailure);
                cycleCheck();
            } catch (DependencyCycleException e) {
                dependedDependables.remove(dependedId);
                Log.w(e, "Failed setting " + this + " depends on " + dependable);
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    public String toString() {
        return getId() + " " + getDescription();
    }
}
