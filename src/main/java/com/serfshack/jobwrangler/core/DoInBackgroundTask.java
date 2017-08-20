package com.serfshack.jobwrangler.core;

import com.serfshack.jobwrangler.util.Log;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

class DoInBackgroundTask implements Callable<State> {
    private final Job job;

    public DoInBackgroundTask(Job job) {
        this.job = job;
    }

    @Override
    public State call() {
        State result = job.getState();

        try {
            result = job.doDoWork();

            if (job.getState().isTerminal()) {
                Log.w("doWork finished (" + result + ") but job is already in a terminal state: " + job);
                result = job.getState();
            } else {
                if (job.getState() != State.BUSY)
                    Log.w("doWork finished (" + result + ") but job is no longer in BUSY state. Continuing anyway. " + job);

                result = job.getRunPolicy().validateRequestedState(result);
            }

            job.setState(result).get();
        } catch (InterruptedException e) {
            result = job.getRunPolicy().validateRequestedState(State.READY);
            try {
                job.setState(result).get();
            } catch (InterruptedException | ExecutionException e1) {
                Log.e(e1);
            }
            Log.e(e, "Job  " + job + " was interrupted");
        } catch (Throwable t) {
            Log.e(t, "Job  " + job + " threw an exception");
            if (!job.getState().isTerminal()) {
                result = State.FAULTED;
                try {
                    job.setState(result, t.getLocalizedMessage()).get();
                } catch (InterruptedException | ExecutionException e1) {
                    Log.e(e1);
                }
            }
        } finally {
            Log.v("DoInBackgroundTask complete: " + job);
        }

        return result;
    }
}
