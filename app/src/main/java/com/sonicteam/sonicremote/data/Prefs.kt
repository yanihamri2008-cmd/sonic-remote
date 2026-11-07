package com.sonicteam.sonicremote.data

import android.content.Context

/** Non-sensitive local settings (terms acceptance). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("sonic_prefs", Context.MODE_PRIVATE)

    fun termsAccepted(): Boolean = sp.getInt(KEY_TERMS, 0) >= TERMS_VERSION

    fun acceptTerms() {
        sp.edit().putInt(KEY_TERMS, TERMS_VERSION).apply()
    }

    companion object {
        /** Bump this number whenever the terms text changes: everyone is asked to accept again. */
        const val TERMS_VERSION = 1
        private const val KEY_TERMS = "terms_version"
    }
}
