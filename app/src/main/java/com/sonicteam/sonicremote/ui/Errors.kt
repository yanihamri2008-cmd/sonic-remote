package com.sonicteam.sonicremote.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.sonicteam.sonicremote.R
import java.util.Locale
import kotlinx.coroutines.delay

/** Maps a backend/network error key to a localized message. */
@StringRes
fun fnErrorRes(key: String): Int = when (key) {
    "invalid_code", "invalid_requester_device_id" -> R.string.fn_invalid_code
    "too_many_attempts" -> R.string.fn_too_many
    "same_account" -> R.string.fn_same_account
    "device_not_owned" -> R.string.fn_device_not_owned
    "agent_busy" -> R.string.fn_agent_busy
    "duplicate_request" -> R.string.fn_duplicate
    "expired" -> R.string.fn_expired
    "already_resolved" -> R.string.fn_resolved
    "not_found" -> R.string.fn_not_found
    "device_unavailable" -> R.string.fn_device_unavailable
    "unauthorized", "missing_token", "invalid_token" -> R.string.fn_unauthorized
    "not_deployed" -> R.string.fn_not_deployed
    "network" -> R.string.fn_network
    else -> R.string.fn_unknown
}

fun formatRemaining(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
}

/** Current time in ms, refreshed every second (for countdowns). */
@Composable
fun rememberNowMs(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    return now
}

fun prettyCode(code: String): String = code.chunked(4).joinToString("-")
