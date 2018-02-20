package com.dozingcatsoftware.vectorcamera;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import android.annotation.TargetApi;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;

// Based on https://developer.android.com/reference/android/app/job/JobInfo.Builder.html#addTriggerContentUri(android.app.job.JobInfo.TriggerContentUri)
@TargetApi(24)
public class NewPictureJob extends JobService {
    // The root URI of the media provider, to monitor for generic changes to its content.
    static final Uri MEDIA_URI = Uri.parse("content://" + MediaStore.AUTHORITY + "/");

    // Path segments for image-specific URIs in the provider.
    static final List<String> EXTERNAL_PATH_SEGMENTS
            = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.getPathSegments();

    // The columns we want to retrieve about a particular image.
    static final String[] PROJECTION = new String[] {
            MediaStore.Images.ImageColumns._ID, MediaStore.Images.ImageColumns.DATA
    };
    static final int PROJECTION_ID = 0;
    static final int PROJECTION_DATA = 1;

    // This is the external storage directory where cameras place pictures.
    static final String DCIM_DIR = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DCIM).getPath();

    static final int JOB_ID = 1;

    JobParameters jobParams;

    final Handler handler = new Handler();
    final Runnable worker = new Runnable() {
        @Override public void run() {
            scheduleJob(NewPictureJob.this);
            jobFinished(jobParams, false);
        }
    };

    // Schedule this job, replace any existing one.
    public static void scheduleJob(Context context) {
        JobScheduler js = context.getSystemService(JobScheduler.class);
        JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(context, NewPictureJob.class))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(MEDIA_URI, 0))
                .build();
        js.schedule(info);
        Log.i("NewPictureJob", "JOB SCHEDULED!");
    }

    // Check whether this job is currently scheduled.
    public static boolean isScheduled(Context context) {
        JobScheduler js = context.getSystemService(JobScheduler.class);
        List<JobInfo> jobs = js.getAllPendingJobs();
        if (jobs == null) {
            return false;
        }
        for (int i=0; i<jobs.size(); i++) {
            if (jobs.get(i).getId() == JOB_ID) {
                return true;
            }
        }
        return false;
    }

    // Cancel this job, if currently scheduled.
    public static void cancelJob(Context context) {
        JobScheduler js = context.getSystemService(JobScheduler.class);
        js.cancel(JOB_ID);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        this.jobParams = params;
        final List<String> newImagePaths = new ArrayList<String>();
        if (params.getTriggeredContentAuthorities() != null && params.getTriggeredContentUris() != null) {
            // If we have details about which URIs changed, then iterate through them
            // and collect either the ids that were impacted or note that a generic
            // change has happened.
            ArrayList<String> ids = new ArrayList<String>();
            for (Uri uri : params.getTriggeredContentUris()) {
                List<String> path = uri.getPathSegments();
                if (path != null && path.size() == EXTERNAL_PATH_SEGMENTS.size()+1) {
                    // This is a specific file.
                    ids.add(path.get(path.size()-1));
                }
                else {
                    // Oops, there is some general change!
                }
            }

            if (ids.size() > 0) {
                // If we found some ids that changed, we want to determine what they are.
                // First, we do a query with content provider to ask about all of them.
                StringBuilder selection = new StringBuilder();
                for (int i=0; i<ids.size(); i++) {
                    if (selection.length() > 0) {
                        selection.append(" OR ");
                    }
                    selection.append(MediaStore.Images.ImageColumns._ID);
                    selection.append("='");
                    selection.append(ids.get(i));
                    selection.append("'");
                }

                // Now we iterate through the query, looking at the filenames of
                // the items to determine if they are ones we are interested in.
                Cursor cursor = null;
                try {
                    cursor = getContentResolver().query(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            PROJECTION, selection.toString(), null, null);
                    while (cursor.moveToNext()) {
                        // We only care about files in the DCIM directory.
                        String dir = cursor.getString(PROJECTION_DATA);
                        if (dir.startsWith(DCIM_DIR)) {
                            newImagePaths.add(dir);
                        }
                    }
                } catch (SecurityException e) {
                    Log.e("NewPictureJob", "Error accessing media", e);
                }
                finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
            }
        }
        try {
            if (newImagePaths.size() > 0) {
                // Use a thread rather than an AsyncTask since AsyncTasks are serialized.
                (new Thread(new Runnable() {
                    @Override public void run() {
                        processImagePaths(newImagePaths);
                    }
                })).start();
            }
        }
        finally {
            handler.postDelayed(worker, 1);
        }
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        handler.removeCallbacks(worker);
        return false;
    }

    // This is the only app-specific code. It would be better if this were an abstract class with
    // this as a method to override, but there's an ugly mix of static methods and class names.
    private void processImagePaths(List<String> imagePaths) {
        boolean scheduled = isScheduled(this);
        if (!scheduled) {
            return;
        }
        for (String path : imagePaths) {
            try {
                (new ProcessImageOperation()).processImage(this, Uri.fromFile(new File(path)));
            }
            catch (Exception ex) {
                Log.e("NewPictureJob", "Failed to process image", ex);
            }
        }
    }
}
