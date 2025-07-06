package com.dozingcatsoftware.vectorcamera

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.dozingcatsoftware.util.adjustPaddingForSystemUi
import com.dozingcatsoftware.vectorcamera.databinding.AboutBinding

class AboutActivity: AppCompatActivity() {

    private lateinit var binding: AboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = AboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Normally we'd add the padding to the webview rather than the root layout,
        // so that the webview contents would scroll under the system bars and cutouts,
        // but there's a WebView bug that causes padding to not be applied correctly:
        // https://stackoverflow.com/questions/9170042/how-to-add-padding-around-a-webview
        adjustPaddingForSystemUi(binding.root)

        val onBackPressedCallback = object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webview.canGoBack()) {
                    binding.webview.goBack()
                }
            }
        }
        onBackPressedDispatcher.addCallback(onBackPressedCallback)

        // https://stackoverflow.com/questions/40576567/on-clicking-the-hyperlink-in-webview-the-app-crashes-i-have-paced-all-the-html/40753538#40753538
        binding.webview.webViewClient = object: WebViewClient() {
            // Deprecated and should be replaced with the version that takes a WebResourceRequest
            // rather than a String, but that's only available in API level 24 and we still want
            // to support 23.
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

            // Selectively enable the back navigation callback depending on
            // whether it should do a "back" action in the browser.
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                onBackPressedCallback.isEnabled = binding.webview.canGoBack()
            }
        }
        binding.webview.loadUrl("file:///android_asset/about.html")
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