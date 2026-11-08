package com.sonicteam.sonicremote.share

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Per-session consent switches, owned by the Agent. Everything defaults to OFF and is reset
 * whenever sharing stops, so control/terminal can never carry over into a new session.
 */
object ControlState {
    /** True while the SONIC REMOTE accessibility service is enabled in Android settings. */
    val serviceConnected = MutableStateFlow(false)

    /** The Agent allows the Controller to tap/swipe on this phone (this session only). */
    val control = MutableStateFlow(false)

    /** The Agent allows the read-only diagnostics terminal. */
    val terminal = MutableStateFlow(false)

    /** Commands the Controller ran on this phone (shown to the Agent). */
    val termLog = MutableStateFlow<List<String>>(emptyList())

    fun log(cmd: String) {
        termLog.value = (termLog.value + cmd).takeLast(30)
    }

    fun reset() {
        control.value = false
        terminal.value = false
        termLog.value = emptyList()
    }
}
