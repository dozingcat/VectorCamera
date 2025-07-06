package com.dozingcatsoftware.vectorcamera

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.View
import android.view.WindowInsets
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.dozingcatsoftware.util.adjustPaddingForSystemUi

class VCPreferencesActivity: AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Use a layout that has only a FrameLayout that we replace with the preferences fragment.
        setContentView(R.layout.preferences_layout)

        val rootView: View = findViewById(R.id.prefs_main)
        adjustPaddingForSystemUi(rootView)

        supportFragmentManager
                .beginTransaction()
                .replace(R.id.prefs_main, VCPreferencesFragment())
                .commit()
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

class VCPreferencesFragment : PreferenceFragmentCompat() {
    private val handler = Handler()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        val pm = this.preferenceManager

        val autoConvertPref: Preference? = findPreference(getString(R.string.autoConvertPicturesPrefsKey))
        autoConvertPref!!.setOnPreferenceChangeListener { pref, value ->
            // Update broadcast receivers immediately so the change takes effect even if the user
            // doesn't go back to the main activity.
            setAutoConvertEnabled(this.requireContext(), java.lang.Boolean.TRUE == value)
            true
        }

        // HACK: when the user updates the character set of an ASCII effect, update the current
        // effect if it matches. This is so when they return to the main activity the effect will
        // be loaded with the new characters.
        val asciiPrefIds = arrayOf(
                getString(R.string.whiteOnBlackPixelCharsPrefId),
                getString(R.string.blackOnWhitePixelCharsPrefId),
                getString(R.string.ansiColorPixelCharsPrefId),
                getString(R.string.fullColorPixelCharsPrefId))
        for (prefId in asciiPrefIds) {
            pm.findPreference<Preference?>(prefId)!!.setOnPreferenceChangeListener { pref, value ->
                handler.post {
                    val storedPrefs = VCPreferences(this.requireContext())
                    if (storedPrefs.effectName() == "ascii") {
                        val params = storedPrefs.effectParameters()
                        if (params["prefId"] == prefId) {
                            val newEffectParams = HashMap(params)
                            newEffectParams["pixelChars"] = value
                            storedPrefs.saveEffectInfo("ascii", newEffectParams)
                        }
                    }
                }
                true
            }
        }
        // Same for number of characters.
        val textEffectNames = setOf("ascii", "matrix")
        val numColumnsPrefId = getString(R.string.numAsciiColumnsPrefId)
        pm.findPreference<Preference?>(numColumnsPrefId)!!.setOnPreferenceChangeListener { pref, value ->
            handler.post {
                val storedPrefs = VCPreferences(this.requireContext())
                if (textEffectNames.contains(storedPrefs.effectName())) {
                    val params = storedPrefs.effectParameters()
                    val newEffectParams = HashMap(params)
                    newEffectParams["numColumns"] = Integer.parseInt(value as String)
                    storedPrefs.saveEffectInfo(storedPrefs.effectName(), newEffectParams)
                }
            }
            true
        }
        // And update matrix text color.
        val matrixColorPrefId = getString(R.string.matrixTextColorPrefId)
        pm.findPreference<Preference?>(matrixColorPrefId)!!.setOnPreferenceChangeListener { pref, value ->
            handler.post {
                val storedPrefs = VCPreferences(this.requireContext())
                if (storedPrefs.effectName() == "matrix") {
                    val params = storedPrefs.effectParameters()
                    val newEffectParams = HashMap(params)
                    newEffectParams["textColor"] = value
                    storedPrefs.saveEffectInfo(storedPrefs.effectName(), newEffectParams)
                }
            }
        }
    }

    /**
     * Sets whether pictures saved by the camera app (or other apps which broadcast the appropriate
     * intent) should automatically be imported and processed. If necessary, requests permissions
     * needed to read the pictures.
     */
    private fun setAutoConvertEnabled(context: Context, enabled: Boolean) {
        if (enabled && !PermissionsChecker.hasStoragePermissions(requireActivity())) {
            android.util.Log.i("VCPreferencesActivity", "Requesting storage permissions")
            PermissionsChecker.requestStoragePermissions(requireActivity())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // For N and above, schedule or cancel a JobService.
            if (enabled) {
                NewPictureJob.scheduleJob(context)
            } else {
                NewPictureJob.cancelJob(context)
            }
        } else {
            // For M, enable or disable NewPictureReceiver.
            val pm = context.packageManager
            pm.setComponentEnabledSetting(ComponentName(context, NewPictureReceiver::class.java),
                    if (enabled)
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP)
        }
    }
}