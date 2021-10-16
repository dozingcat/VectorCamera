package com.dozingcatsoftware.vectorcamera

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.renderscript.RenderScript
import android.util.Log
import com.dozingcatsoftware.vectorcamera.effect.Effect
import com.dozingcatsoftware.vectorcamera.effect.EffectRegistry
import com.dozingcatsoftware.util.jsonStringToMap
import com.dozingcatsoftware.util.mapToJsonString

class VCPreferences(val context: Context) {

    private val effectRegistry = EffectRegistry()

    private fun sharedPrefs() = PreferenceManager.getDefaultSharedPreferences(context)

    fun effectName(): String = sharedPrefs().getString(EFFECT_NAME_KEY, "")!!

    fun useHighQualityPreview() = sharedPrefs().getBoolean(HIGH_QUALITY_PREVIEW_KEY, false)

    fun setUseHighQualityPreview(flag: Boolean) {
        withPrefsEditor {
            it.putBoolean(HIGH_QUALITY_PREVIEW_KEY, flag)
        }
    }

    val lookupFunction = fun(key: String, defaultValue: Any): Any {
        if (defaultValue is String) {
            return sharedPrefs().getString(key, defaultValue)!!
        }
        if (defaultValue is Int) {
            return sharedPrefs().getInt(key, defaultValue)
        }
        throw IllegalArgumentException("Unsupported type: ${defaultValue.javaClass}")
    }

    fun saveEffectInfo(effectName: String, params: Map<String, Any>) {
        withPrefsEditor {
            it.putString(EFFECT_NAME_KEY, effectName)
            it.putString(EFFECT_PARAMETERS_KEY, mapToJsonString(params))
        }
    }

    fun effectParameters(): Map<String, Any> {
        val effectJson = sharedPrefs().getString(EFFECT_PARAMETERS_KEY, "")!!
        if (effectJson.isEmpty()) {
            return mapOf()
        }
        return jsonStringToMap(effectJson)
    }

    fun effect(rs: RenderScript, defaultFn: (() -> Effect)): Effect {
        val name = effectName()
        val params = effectParameters()
        if (name.isNotEmpty()) {
            try {
                return effectRegistry.effectForNameAndParameters(rs, name, params)
            }
            catch (ex: Exception) {
                Log.w(TAG, "Error reading effect from preferences", ex)
            }
        }
        return defaultFn()
    }

    fun saveCustomScheme(id: String, scheme: CustomColorScheme) {
        withPrefsEditor {
            it.putString(id, mapToJsonString(scheme.toMap()))
        }
    }

    private fun withPrefsEditor(editFn: (SharedPreferences.Editor) -> Unit) {
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        editFn(editor)
        editor.commit()
    }

    companion object {
        const val TAG = "VCPreferences"
        const val EFFECT_NAME_KEY = "effectName"
        const val EFFECT_PARAMETERS_KEY = "effectParams"
        const val HIGH_QUALITY_PREVIEW_KEY = "highQualityPreview"
    }
}
