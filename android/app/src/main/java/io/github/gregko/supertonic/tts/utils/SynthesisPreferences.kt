package io.github.gregko.supertonic.tts.utils

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.roundToInt

object SynthesisPreferences {
    const val PREFS_NAME = "SupertonicPrefs"
    const val KEY_TEMPERATURE = "sampling_temperature"
    const val KEY_LAST_TEMPERATURE = "last_temperature"
    const val DEFAULT_TEMPERATURE = 0.60f
    const val MIN_TEMPERATURE = 0.40f
    const val MAX_TEMPERATURE = 1.00f

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun normalizeTemperature(value: Float): Float {
        val safeValue = if (value.isFinite()) value else DEFAULT_TEMPERATURE
        return ((safeValue.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE) * 20f).roundToInt()) / 20f
    }

    fun getTemperature(prefs: SharedPreferences): Float {
        return normalizeTemperature(prefs.getFloat(KEY_TEMPERATURE, DEFAULT_TEMPERATURE))
    }
}
