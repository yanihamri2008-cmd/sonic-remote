package com.sonicteam.sonicremote.share

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import com.sonicteam.sonicremote.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The "support terminal". It is NOT a shell: it only answers a fixed list of read-only
 * diagnostic commands and can never run arbitrary code or touch other apps' data.
 */
object Diagnostics {
    val commands = listOf("help", "info", "battery", "network", "storage", "memory", "screen", "uptime", "time")

    fun run(context: Context, raw: String): String {
        val cmd = raw.trim().lowercase(Locale.ROOT)
        return when (cmd) {
            "help" -> "Available commands (read-only):\n" + commands.joinToString("  ")
            "info" -> "Model: ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\nApp: SONIC REMOTE ${BuildConfig.VERSION_NAME}\nLocale: ${Locale.getDefault()}"
            "battery" -> battery(context)
            "network" -> network(context)
            "storage" -> storage()
            "memory" -> memory(context)
            "screen" -> screen(context)
            "uptime" -> uptime()
            "time" -> SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.ROOT).format(Date())
            "" -> ""
            else -> "Command not available: ${cmd.take(40)}\nThis terminal is read-only diagnostics. Type help."
        }
    }

    private fun battery(c: Context): String {
        val i: Intent = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return "Battery: unknown"
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return "Battery: ${if (pct >= 0) "$pct%" else "unknown"}${if (charging) " (charging)" else ""}"
    }

    private fun network(c: Context): String {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Network: offline"
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
        val vpn = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) " + VPN" else ""
        val internet = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "internet OK" else "no internet"
        return "Network: $type$vpn, $internet"
    }

    private fun gb(bytes: Long) = String.format(Locale.ROOT, "%.1f GB", bytes / 1_073_741_824.0)

    private fun storage(): String {
        val s = StatFs(Environment.getDataDirectory().path)
        return "Storage: ${gb(s.availableBytes)} free of ${gb(s.totalBytes)}"
    }

    private fun memory(c: Context): String {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return "Memory: ${gb(mi.availMem)} free of ${gb(mi.totalMem)}${if (mi.lowMemory) " (LOW)" else ""}"
    }

    @Suppress("DEPRECATION")
    private fun screen(c: Context): String {
        val dm = DisplayMetrics()
        (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return "Screen: ${dm.widthPixels}x${dm.heightPixels} px, ${dm.densityDpi} dpi"
    }

    private fun uptime(): String {
        val s = SystemClock.elapsedRealtime() / 1000
        return "Uptime: ${s / 3600}h ${(s % 3600) / 60}m"
    }
}
