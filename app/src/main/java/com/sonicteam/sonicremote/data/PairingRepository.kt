package com.sonicteam.sonicremote.data

import java.net.URLEncoder
import java.time.Instant
import org.json.JSONObject

class PairingRepository(private val api: ApiClient) {

    private fun nowParam(): String = URLEncoder.encode(Instant.now().toString(), "UTF-8")

    /** Pending requests addressed to this account. Null = could not load. */
    suspend fun incoming(userId: String): List<IncomingRequest>? {
        val res = api.get(
            "/rest/v1/pairing_requests?target_owner_id=eq.$userId&status=eq.pending&expires_at=gt.${nowParam()}" +
                "&select=id,requester_device_name,requester_account_label,expires_at&order=created_at.desc"
        ) ?: return null
        if (!res.ok) return null
        val arr = res.array() ?: return null
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            IncomingRequest(
                id = o.optString("id"),
                deviceName = o.optString("requester_device_name", ""),
                accountLabel = o.optString("requester_account_label", ""),
                expiresAtMs = parseTimeMs(o.optString("expires_at")),
            )
        }
    }

    suspend fun requestStatus(requestId: String): String? {
        val res = api.get("/rest/v1/pairing_requests?id=eq.$requestId&select=status") ?: return null
        if (!res.ok) return null
        return res.array()?.optJSONObject(0)?.optString("status")
    }

    /** Active, unexpired sessions this account participates in (RLS limits rows to participants). */
    suspend fun activeSessions(userId: String): List<ActiveSession>? {
        val res = api.get(
            "/rest/v1/remote_sessions?status=eq.active&expires_at=gt.${nowParam()}" +
                "&select=id,controller_device_id,agent_device_id,agent_owner_id,controller_device_name,agent_device_name,expires_at" +
                "&order=created_at.desc&limit=5"
        ) ?: return null
        if (!res.ok) return null
        val arr = res.array() ?: return null
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val isAgent = o.optString("agent_owner_id") == userId
            ActiveSession(
                id = o.optString("id"),
                role = if (isAgent) Role.Agent else Role.Controller,
                peerDeviceName = o.optString(if (isAgent) "controller_device_name" else "agent_device_name", ""),
                expiresAtMs = parseTimeMs(o.optString("expires_at")),
                controllerDeviceId = o.optString("controller_device_id"),
                agentDeviceId = o.optString("agent_device_id"),
            )
        }
    }

    suspend fun sendRequest(requesterDeviceId: String, code: String): FnResult =
        api.function("pairing-request", JSONObject().put("requester_device_id", requesterDeviceId).put("code", code))

    suspend fun respond(requestId: String, approve: Boolean): FnResult =
        api.function("pairing-respond", JSONObject().put("request_id", requestId).put("approve", approve))

    suspend fun endSession(sessionId: String, revoke: Boolean): FnResult =
        api.function("session-end", JSONObject().put("session_id", sessionId).put("revoke", revoke))
}
