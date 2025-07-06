package com.dozingcatsoftware.util

import android.os.Build
import android.view.View
import android.view.WindowInsets

// In API 35 or later, apps are always in edge-to-edge mode. This function applies padding
// to a view so that its area won't cover status bars or display cutouts.
fun adjustPaddingForSystemUi(view: View, flags: Int? = null) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        val f = flags ?: (WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        view.setOnApplyWindowInsetsListener { v, insets ->
            val systemBars = insets.getInsets(f)
            v.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )
            insets
        }
    }
}
