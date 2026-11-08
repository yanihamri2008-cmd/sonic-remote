package com.sonicteam.sonicremote.share

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

/**
 * Performs ONLY taps, swipes and Back/Home/Recents, and ONLY while the Agent has switched
 * remote control ON for the current session. It does not read window content, passwords or
 * keystrokes (see res/xml/accessibility_service_config.xml: canRetrieveWindowContent=false).
 * The user must enable it manually in Android Settings; it can never be enabled silently.
 */
class SonicAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ControlState.serviceConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* intentionally ignored */ }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        ControlState.serviceConnected.value = false
        ControlState.control.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        ControlState.serviceConnected.value = false
        super.onDestroy()
    }

    private fun stroke(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        if (!ControlState.control.value) return // defense in depth: consent is re-checked on every gesture
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(30L, 1500L)))
            .build()
        dispatchGesture(g, null, null)
    }

    fun tap(x: Float, y: Float) = stroke(x, y, x, y, 60L)

    fun longPress(x: Float, y: Float) = stroke(x, y, x, y, 800L)

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) = stroke(x1, y1, x2, y2, durationMs)

    fun key(name: String) {
        if (!ControlState.control.value) return
        when (name) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    companion object {
        @Volatile
        var instance: SonicAccessibilityService? = null
    }
}

/** Turns a validated control message from the Controller into a gesture on this phone. */
object ControlExecutor {
    private var lastAt = 0L

    fun run(j: JSONObject, screenW: Int, screenH: Int) {
        if (!ControlState.control.value) return
        val svc = SonicAccessibilityService.instance ?: return
        val now = System.currentTimeMillis()
        if (now - lastAt < 15) return // basic flood protection
        lastAt = now

        fun coord(key: String, size: Int): Float? {
            if (!j.has(key)) return null
            val v = j.optDouble(key, Double.NaN)
            if (v.isNaN()) return null
            return (v.toFloat().coerceIn(0f, 1f)) * (size - 1)
        }
        when (j.optString("t")) {
            "tap" -> svc.tap(coord("x", screenW) ?: return, coord("y", screenH) ?: return)
            "long" -> svc.longPress(coord("x", screenW) ?: return, coord("y", screenH) ?: return)
            "swipe" -> svc.swipe(
                coord("x1", screenW) ?: return, coord("y1", screenH) ?: return,
                coord("x2", screenW) ?: return, coord("y2", screenH) ?: return,
                j.optLong("d", 300L),
            )
            "key" -> svc.key(j.optString("k"))
        }
    }
}
