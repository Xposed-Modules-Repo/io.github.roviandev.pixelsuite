package io.github.pixelsuite;
import android.app.job.*;
public final class ReleaseUpdateJob extends JobService {
    private volatile boolean stopped;
    @Override public boolean onStartJob(final JobParameters parameters){stopped=false;ReleaseUpdates.check(this,false,new ReleaseUpdates.Result(){public void done(String status){if(!stopped)jobFinished(parameters,false);}});return true;}
    @Override public boolean onStopJob(JobParameters parameters){stopped=true;return false;}
}
