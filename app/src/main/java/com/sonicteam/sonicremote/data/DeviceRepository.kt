package com.sonicteam.sonicremote.data

import android.os.Build
import java.time.Instant
import org.json.JSONObject

class DeviceRepository(private val api: ApiClient, private val prefs: Prefs) {

    private fun deviceName(): String {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        return "$maker ${Build.MODEL.orEmpty()}".trim().ifEmpty { "Android" }
    }

    private fun JSONObject.toDevice() = DeviceInfo(
        id = optString("id"),
        name = optString("device_name"),
        pairingCode = optString("pairing_code"),
    )

    /** Finds this phone's device row for [userId], or registers a new one owned by that user. */
    suspend fun ensure(userId: String): DeviceResult {
        val local = prefs.deviceId(userId)
        if (local != null) {
            val res = api.get("/rest/v1/devices?id=eq.$local&select=id,device_name,pairing_code,revoked_at")
                ?: return DeviceResult.Err("network")
            if (res.code == 401) return DeviceResult.Err("unauthorized")
            if (!res.ok) return DeviceResult.Err("unknown")
            val row = res.array()?.optJSONObject(0)
            if (row != null && row.isNull("revoked_at")) return DeviceResult.Ok(row.toDevice())
            prefs.setDeviceId(userId, null) // gone or revoked: register a fresh identity
        }
        val body = JSONObject()
            .put("owner_id", userId)
            .put("device_name", deviceName())
            .put("platform", "android")
        val res = api.request("POST", "/rest/v1/devices", body, mapOf("Prefer" to "return=representation"))
            ?: return DeviceResult.Err("network")
        if (!res.ok) return DeviceResult.Err(if (res.code == 401) "unauthorized" else "unknown")
        val row = res.array()?.optJSONObject(0) ?: return DeviceResult.Err("unknown")
        val device = row.toDevice()
        prefs.setDeviceId(userId, device.id)
        return DeviceResult.Ok(device)
    }

    suspend fun heartbeat(deviceId: String) {
        api.request(
            "PATCH", "/rest/v1/devices?id=eq.$deviceId",
            JSONObject().put("last_seen", Instant.now().toString()),
            mapOf("Prefer" to "return=minimal"),
        )
    }

    /** Revokes the device. A revoked device can never be restored (enforced by a DB trigger). */
    suspend fun revoke(userId: String, deviceId: String): Boolean {
        val res = api.request(
            "PATCH", "/rest/v1/devices?id=eq.$deviceId",
            JSONObject().put("revoked_at", Instant.now().toString()),
            mapOf("Prefer" to "return=minimal"),
        )
        if (res?.ok == true) prefs.setDeviceId(userId, null)
        return res?.ok == true
    }
}
