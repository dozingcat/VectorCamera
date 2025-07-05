package com.dozingcatsoftware.vectorcamera

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.WebView
import android.webkit.WebViewClient
// import kotlinx.android.synthetic.main.about.*

class AboutActivity: Activity() {

    lateinit var webview: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.about)
        // https://stackoverflow.com/questions/40576567/on-clicking-the-hyperlink-in-webview-the-app-crashes-i-have-paced-all-the-html/40753538#40753538
        webview = findViewById(R.id.webview)
        webview.webViewClient = object: WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                // Go to email for mailto: and default browser for http/https URLs.
                if (url.startsWith("mailto:")) {
                    startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(url)))
                    return true
                }
                if (url.startsWith("http:") || url.startsWith("https:")) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    return true
                }
                // Load file URLs in the same WebView.
                if (url.startsWith("file:")) {
                    return false
                }
                return super.shouldOverrideUrlLoading(view, url)
            }
        }
        webview.loadUrl("file:///android_asset/about.html")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (webview.canGoBack()) {
                    webview.goBack()
                }
                else {
                    this.finish()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
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