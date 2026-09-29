package com.crocxshare.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Local-only preferences. Nothing is uploaded anywhere. */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("crocxshare_settings", Context.MODE_PRIVATE)

    var deviceName: String
        get() = prefs.getString("device_name", android.os.Build.MODEL ?: "Android") ?: "Android"
        set(v) = prefs.edit { putString("device_name", v.take(48)) }

    var avatarPath: String?
        get() = prefs.getString("avatar_path", null)
        set(v) = prefs.edit { putString("avatar_path", v) }

    /** SYSTEM | LIGHT | DARK */
    var theme: String
        get() = prefs.getString("theme", "SYSTEM") ?: "SYSTEM"
        set(v) = prefs.edit { putString("theme", v) }

    /** GREEN | BLUE | PURPLE | ORANGE | RED */
    var themeColor: String
        get() = prefs.getString("theme_color", "GREEN") ?: "GREEN"
        set(v) = prefs.edit { putString("theme_color", v) }

    /** SYSTEM | EN | AR | ES | RU */
    var language: String
        get() = prefs.getString("language", "SYSTEM") ?: "SYSTEM"
        set(v) = prefs.edit { putString("language", v) }

    /** Speed boost: larger chunks + wider ACK window. */
    /** First-run intro shown. */
    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboarding_done", false)
        set(v) = prefs.edit { putBoolean("onboarding_done", v) }

    var boostMode: Boolean
        get() = prefs.getBoolean("boost_mode", false)
        set(v) = prefs.edit { putBoolean("boost_mode", v) }

    var receiveDirUri: String?
        get() = prefs.getString("receive_dir", null)
        set(v) = prefs.edit { putString("receive_dir", v) }

    var confirmBeforeAccept: Boolean
        get() = prefs.getBoolean("confirm_accept", true)
        set(v) = prefs.edit { putBoolean("confirm_accept", v) }

    var autoCleanTemp: Boolean
        get() = prefs.getBoolean("auto_clean_temp", true)
        set(v) = prefs.edit { putBoolean("auto_clean_temp", v) }

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notifications", true)
        set(v) = prefs.edit { putBoolean("notifications", v) }

    var preferredMethod: String
        get() = prefs.getString("preferred_method", "AUTO") ?: "AUTO"
        set(v) = prefs.edit { putString("preferred_method", v) }

    var chunkSizeKb: Int
        get() = prefs.getInt("chunk_kb", 256)
        set(v) = prefs.edit { putInt("chunk_kb", v.coerceIn(64, 1024)) }

    fun chunkBytes(): Int = chunkSizeKb * 1024
}
