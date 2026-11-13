package dev.deang.tabaod;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Twice-daily update check (see {@link Updater#schedule}). */
public class UpdateJobService extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            Updater.runJob(this);
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return true; // try again later
    }
}
