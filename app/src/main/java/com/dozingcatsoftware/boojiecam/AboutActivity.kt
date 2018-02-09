package com.dozingcatsoftware.boojiecam

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.android.synthetic.main.about.*

/**
 * Created by brian on 2/8/18.
 */
class AboutActivity: Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.about)
        webview.loadUrl("file:///android_asset/about.html")
    }

    companion object {
        fun startIntent(parent: Context): Intent {
            // sets FLAG_ACTIVITY_NO_HISTORY so exiting and relaunching won't go back to this screen
            val aboutIntent = Intent(parent, AboutActivity::class.java)
            aboutIntent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            parent.startActivity(aboutIntent)
            return aboutIntent
        }
    }
}