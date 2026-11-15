package com.ridevoice.qq

import android.content.Context

enum class VoicePreset(val label: String, val pitch: Float, val rate: Float) {
    KLEE("可莉感 · 高音童声", 1.55f, 1.05f),
    GENKI("元气少女", 1.32f, 1.05f),
    SOFT("温柔女声", 1.08f, 0.96f),
    BOY("清爽少年", 1.18f, 1.00f),
    CLEAR("清晰播报", 1.00f, 0.96f)
}

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("ride_voice", Context.MODE_PRIVATE)

    var readGroups: Boolean
        get() = sp.getBoolean("read_groups", false)
        set(value) {
            sp.edit().putBoolean("read_groups", value).apply()
        }

    var onlyContact: Boolean
        get() = sp.getBoolean("only_contact", true)
        set(value) {
            sp.edit().putBoolean("only_contact", value).apply()
        }

    var contactName: String
        get() = sp.getString("contact_name", "林霏") ?: "林霏"
        set(value) {
            sp.edit().putString("contact_name", value.trim()).apply()
        }

    var musicEnabled: Boolean
        get() = sp.getBoolean("music_enabled", false)
        set(value) {
            sp.edit().putBoolean("music_enabled", value).apply()
        }

    var musicUri: String
        get() = sp.getString("music_uri", "") ?: ""
        set(value) {
            sp.edit().putString("music_uri", value).apply()
        }

    var musicName: String
        get() = sp.getString("music_name", "") ?: ""
        set(value) {
            sp.edit().putString("music_name", value).apply()
        }

    var lastVoiceError: String
        get() = sp.getString("last_voice_error", "") ?: ""
        set(value) {
            sp.edit().putString("last_voice_error", value).apply()
        }

    var presetIndex: Int
        get() = sp.getInt("preset_index", 0)
        set(value) {
            val safe = value.coerceIn(0, VoicePreset.values().size - 1)
            sp.edit().putInt("preset_index", safe).apply()
        }

    val preset: VoicePreset
        get() = VoicePreset.values()[presetIndex]
}
