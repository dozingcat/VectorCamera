package com.dozingcatsoftware.boojiecam

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.renderscript.RenderScript
import android.util.Log
import com.dozingcatsoftware.boojiecam.effect.Effect
import com.dozingcatsoftware.boojiecam.effect.EffectRegistry
import java.util.*

/**
 * Created by brian on 1/20/18.
 */
class BCPreferences(val context: Context) {
    val EFFECT_NAME_KEY = "effectName"
    val EFFECT_PARAMETERS_KEY = "effectParams"

    private fun sharedPrefs() = PreferenceManager.getDefaultSharedPreferences(context)

    fun effectName() = sharedPrefs().getString(EFFECT_NAME_KEY, "")

    fun saveEffectInfo(effectName: String, params: Map<String, Any>) {
        withPrefsEditor {
            it.putString(EFFECT_NAME_KEY, effectName)
            it.putString(EFFECT_PARAMETERS_KEY, mapToJsonString(params))
        }
    }

    fun effectParameters(): Map<String, Any> {
        val effectJson = sharedPrefs().getString(EFFECT_PARAMETERS_KEY, "")
        if (effectJson.length == 0) {
            return mapOf()
        }
        return jsonStringToMap(effectJson)
    }

    fun effect(rs: RenderScript, defaultFn: (() -> Effect)): Effect {
        val name = effectName()
        val params = effectParameters()
        if (name.isNotEmpty()) {
            try {
                return EffectRegistry.forNameAndParameters(rs, name, params)
            }
            catch (ex: Exception) {
                Log.w(TAG, "Error reading effect from preferences", ex)
            }
        }
        return defaultFn()
    }

    private fun withPrefsEditor(editFn: (SharedPreferences.Editor) -> Unit) {
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        editFn(editor)
        editor.commit()
    }

    companion object {
        val TAG = "BCPreferences"
    }
}
