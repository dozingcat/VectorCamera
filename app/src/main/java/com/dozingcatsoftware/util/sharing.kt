package com.dozingcatsoftware.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.dozingcatsoftware.vectorcamera.ViewImageActivity

// Grants read permission to `uri` for all activities that can handle `intent`.
// Used by ViewImageActivity and ViewVideoActivity to allow the "chooser" activity to access the
// URI of the file being shared. This allows the chooser to show a thumbnail and avoids a
// permission error (which doesn't prevent sharing, but shows up in logcat).
fun grantUriPermissionForIntent(context: Context, uri: Uri, intent: Intent) {
    val resInfos = context.packageManager.queryIntentActivities(
            intent, PackageManager.MATCH_DEFAULT_ONLY)
    for (info in resInfos) {
        val packageName = info.activityInfo.packageName
        Log.i(ViewImageActivity.TAG, "Granting package: $packageName")
        context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
