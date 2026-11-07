package com.sonicteam.sonicremote.data

import java.time.OffsetDateTime

data class DeviceInfo(val id: String, val name: String, val pairingCode: String)

sealed interface DeviceResult {
    data class Ok(val device: DeviceInfo) : DeviceResult
    data class Err(val error: String) : DeviceResult // "network" | "unauthorized" | "unknown"
}

enum class Role { Controller, Agent }

data class IncomingRequest(
    val id: String,
    val deviceName: String,
    val accountLabel: String,
    val expiresAtMs: Long,
)

data class ActiveSession(
    val id: String,
    val role: Role,
    val peerDeviceName: String,
    val expiresAtMs: Long,
    val controllerDeviceId: String,
    val agentDeviceId: String,
)

sealed interface Outgoing {
    data object None : Outgoing
    data object Sending : Outgoing
    data class Waiting(val requestId: String, val targetName: String, val expiresAtMs: Long) : Outgoing
    data object Rejected : Outgoing
    data object Expired : Outgoing
    data class Failed(val errorKey: String) : Outgoing
}

fun parseTimeMs(s: String?): Long =
    try { OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (e: Exception) { 0L }
