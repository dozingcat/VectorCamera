package com.dozingcatsoftware.vectorcamera

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.preference.PreferenceActivity

class VCPreferencesActivity : PreferenceActivity() {
    val handler = Handler()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preferences)
        val pm = this.preferenceManager

        val autoConvertPref = pm.findPreference(getString(R.string.autoConvertPicturesPrefsKey))
        autoConvertPref!!.setOnPreferenceChangeListener({pref, value ->
            // Update broadcast receivers immediately so the change takes effect even if the user
            // doesn't go back to the main activity.
            setAutoConvertEnabled(this@VCPreferencesActivity, java.lang.Boolean.TRUE == value)
            true
        })

        // HACK: when the user updates the character set of an ASCII effect, update the current
        // effect if it matches. This is so when they return to the main activity the effect will
        // be loaded with the new characters.
        val asciiPrefIds = arrayOf(
                getString(R.string.whiteOnBlackPixelCharsPrefId),
                getString(R.string.blackOnWhitePixelCharsPrefId),
                getString(R.string.ansiColorPixelCharsPrefId),
                getString(R.string.fullColorPixelCharsPrefId))
        for (prefId in asciiPrefIds) {
            pm.findPreference(prefId).setOnPreferenceChangeListener({pref, value ->
                handler.post({
                    val storedPrefs = VCPreferences(this)
                    if (storedPrefs.effectName() == "ascii") {
                        val params = storedPrefs.effectParameters()
                        if (params["prefId"] == prefId) {
                            val newEffectParams = HashMap(params)
                            newEffectParams["pixelChars"] = value
                            storedPrefs.saveEffectInfo("ascii", newEffectParams)
                        }
                    }
                })
                true
            })
        }
        // Same for number of characters.
        val numColumnsPrefId = getString(R.string.numAsciiColumnsPrefId)
        pm.findPreference(numColumnsPrefId).setOnPreferenceChangeListener({pref, value ->
            handler.post({
                val storedPrefs = VCPreferences(this)
                if (storedPrefs.effectName() == "ascii") {
                    val params = storedPrefs.effectParameters()
                    val newEffectParams = HashMap(params)
                    newEffectParams["numColumns"] = Integer.parseInt(value as String)
                    storedPrefs.saveEffectInfo("ascii", newEffectParams)
                }
            })
            true
        })
    }

    /**
     * Sets whether pictures saved by the camera app (or other apps which broadcast the appropriate intent)
     * should automatically be converted to ascii via the NewPictureReceiver broadcast receiver.
     */
    private fun setAutoConvertEnabled(context: Context, enabled: Boolean) {
        // For N and above, schedule or cancel a JobService.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (enabled) {
                NewPictureJob.scheduleJob(context)
            } else {
                NewPictureJob.cancelJob(context)
            }
        } else {
            if (enabled) {
                android.hardware.Camera::class.java.getField("ACTION_NEW_PICTURE")
            }
            val pm = context.packageManager
            pm.setComponentEnabledSetting(ComponentName(context, NewPictureReceiver::class.java),
                    if (enabled)
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP)
        }
    }

    companion object {
        // sets FLAG_ACTIVITY_NO_HISTORY so exiting and relaunching won't go back to this screen
        fun startIntent(parent: Activity): Intent {
            val prefsIntent = Intent(parent, VCPreferencesActivity::class.java)
            prefsIntent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            parent.startActivity(prefsIntent)
            return prefsIntent
        }
    }
}