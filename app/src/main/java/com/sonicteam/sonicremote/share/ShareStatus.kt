package com.sonicteam.sonicremote.share

import kotlinx.coroutines.flow.MutableStateFlow

/** Process-wide sharing state, read by the UI. */
object ShareStatus {
    enum class State { Idle, Starting, Live, Error }

    val state = MutableStateFlow(State.Idle)
}
