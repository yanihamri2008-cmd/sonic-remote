package com.sonicteam.sonicremote.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Auth tokens live only in encrypted app-private storage (Android Keystore backed). */
class SessionStore(private val context: Context) {
    private val prefs: SharedPreferences = open()

    private fun open(): SharedPreferences {
        fun create(): SharedPreferences {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                context, FILE, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        return try {
            create()
        } catch (e: Exception) {
            // Corrupted keystore state (e.g. after a restore): start clean rather than crash.
            context.deleteSharedPreferences(FILE)
            create()
        }
    }

    fun load(): Session? {
        val access = prefs.getString(K_ACCESS, null) ?: return null
        val refresh = prefs.getString(K_REFRESH, null) ?: return null
        val userId = prefs.getString(K_USER, null) ?: return null
        return Session(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochSec = prefs.getLong(K_EXP, 0L),
            userId = userId,
            email = prefs.getString(K_EMAIL, "") ?: "",
        )
    }

    fun save(s: Session) {
        prefs.edit()
            .putString(K_ACCESS, s.accessToken)
            .putString(K_REFRESH, s.refreshToken)
            .putLong(K_EXP, s.expiresAtEpochSec)
            .putString(K_USER, s.userId)
            .putString(K_EMAIL, s.email)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE = "sonic_secure"
        const val K_ACCESS = "access"
        const val K_REFRESH = "refresh"
        const val K_EXP = "exp"
        const val K_USER = "uid"
        const val K_EMAIL = "email"
    }
}
