package com.sonicteam.sonicremote.data

import android.content.Context

/** Non-sensitive local settings (terms acceptance, which device row belongs to which account). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("sonic_prefs", Context.MODE_PRIVATE)

    fun termsAccepted(): Boolean = sp.getInt(KEY_TERMS, 0) >= TERMS_VERSION

    fun acceptTerms() {
        sp.edit().putInt(KEY_TERMS, TERMS_VERSION).apply()
    }

    /** Device row id for this phone under a given account (one phone can be used by several accounts). */
    fun deviceId(userId: String): String? = sp.getString("device_$userId", null)

    fun setDeviceId(userId: String, id: String?) {
        val e = sp.edit()
        if (id == null) e.remove("device_$userId") else e.putString("device_$userId", id)
        e.apply()
    }

    companion object {
        /** Bump this number whenever the terms text changes: everyone is asked to accept again. */
        const val TERMS_VERSION = 2
        private const val KEY_TERMS = "terms_version"
    }
}
