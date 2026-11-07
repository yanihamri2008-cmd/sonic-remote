package com.sonicteam.sonicremote.rtc

import android.content.Context
import com.sonicteam.sonicremote.data.ApiClient
import com.sonicteam.sonicremote.data.FnResult
import com.sonicteam.sonicremote.data.RealtimeClient
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/** Lets the foreground service reuse the app's (single, mutex-protected) token refresh logic. */
object TokenBridge {
    @Volatile
    var provider: (suspend () -> String?)? = null
}

class RtcDeps(
    val api: ApiClient,
    val url: String,
    val key: String,
    val token: suspend () -> String?,
)

object Rtc {
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
        )
        initialized = true
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}

open class PcObserver : PeerConnection.Observer {
    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
    override fun onIceConnectionReceivingChange(p0: Boolean) {}
    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
    override fun onIceCandidate(p0: IceCandidate?) {}
    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
    override fun onAddStream(p0: MediaStream?) {}
    override fun onRemoveStream(p0: MediaStream?) {}
    override fun onDataChannel(p0: DataChannel?) {}
    override fun onRenegotiationNeeded() {}
}

suspend fun PeerConnection.createOfferAwait(): SessionDescription = suspendCancellableCoroutine { cont ->
    createOffer(object : SimpleSdpObserver() {
        override fun onCreateSuccess(p0: SessionDescription?) {
            if (p0 != null) cont.resume(p0) else cont.resumeWithException(IllegalStateException("empty offer"))
        }
        override fun onCreateFailure(p0: String?) {
            cont.resumeWithException(IllegalStateException(p0 ?: "offer failed"))
        }
    }, MediaConstraints())
}

suspend fun PeerConnection.createAnswerAwait(): SessionDescription = suspendCancellableCoroutine { cont ->
    createAnswer(object : SimpleSdpObserver() {
        override fun onCreateSuccess(p0: SessionDescription?) {
            if (p0 != null) cont.resume(p0) else cont.resumeWithException(IllegalStateException("empty answer"))
        }
        override fun onCreateFailure(p0: String?) {
            cont.resumeWithException(IllegalStateException(p0 ?: "answer failed"))
        }
    }, MediaConstraints())
}

suspend fun PeerConnection.setLocalAwait(sdp: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
    setLocalDescription(object : SimpleSdpObserver() {
        override fun onSetSuccess() { cont.resume(Unit) }
        override fun onSetFailure(p0: String?) { cont.resumeWithException(IllegalStateException(p0 ?: "setLocal failed")) }
    }, sdp)
}

suspend fun PeerConnection.setRemoteAwait(sdp: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
    setRemoteDescription(object : SimpleSdpObserver() {
        override fun onSetSuccess() { cont.resume(Unit) }
        override fun onSetFailure(p0: String?) { cont.resumeWithException(IllegalStateException(p0 ?: "setRemote failed")) }
    }, sdp)
}

/** Parses the `ice-config` Edge Function response. Falls back to a public STUN server. */
fun parseIceServers(json: JSONObject?): List<PeerConnection.IceServer> {
    val fallback = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
    val arr = json?.optJSONArray("ice_servers") ?: return fallback
    val out = mutableListOf<PeerConnection.IceServer>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val urls = mutableListOf<String>()
        val u = o.opt("urls")
        if (u is String) urls += u
        val ua = o.optJSONArray("urls")
        if (ua != null) for (j in 0 until ua.length()) urls += ua.optString(j)
        if (urls.isEmpty()) continue
        val b = PeerConnection.IceServer.builder(urls)
        if (o.has("username")) b.setUsername(o.optString("username"))
        if (o.has("credential")) b.setPassword(o.optString("credential"))
        out += b.createIceServer()
    }
    return out.ifEmpty { fallback }
}

fun iceConfig(servers: List<PeerConnection.IceServer>): PeerConnection.RTCConfiguration =
    PeerConnection.RTCConfiguration(servers).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    }

class SignalMsg(val id: String, val type: String, val payload: JSONObject)

/**
 * Signaling for one session: receives via Realtime (RLS-protected) with a REST catch-up poll,
 * sends through the `send-signal` Edge Function (the server decides sender/recipient).
 */
class SignalingChannel(
    private val deps: RtcDeps,
    private val scope: CoroutineScope,
    private val sessionId: String,
    private val myDeviceId: String,
    private val onMessage: suspend (SignalMsg) -> Unit,
) {
    private val seen = HashSet<String>()
    private val inbox = Channel<SignalMsg>(Channel.UNLIMITED)
    private val jobs = mutableListOf<Job>()
    private val realtime = RealtimeClient(deps.url, deps.key, deps.token, scope)

    /** Poll quickly while negotiating, slowly once connected. */
    @Volatile
    var fast = true

    fun start() {
        jobs += scope.launch {
            for (m in inbox) {
                try {
                    onMessage(m)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
        }
        realtime.start(listOf("signaling_messages"), mapOf("signaling_messages" to "session_id=eq.$sessionId")) { _, type, rec ->
            if (type == "INSERT" && rec != null && rec.optString("recipient_device_id") == myDeviceId) accept(rec)
        }
        jobs += scope.launch {
            while (isActive) {
                poll()
                delay(if (fast) 2_000L else 15_000L)
            }
        }
    }

    private fun accept(o: JSONObject) {
        val id = o.optString("id")
        val payload = o.optJSONObject("payload") ?: return
        synchronized(seen) { if (!seen.add(id)) return }
        inbox.trySend(SignalMsg(id, o.optString("message_type"), payload))
    }

    private suspend fun poll() {
        val res = deps.api.get(
            "/rest/v1/signaling_messages?session_id=eq.$sessionId&recipient_device_id=eq.$myDeviceId" +
                "&select=id,message_type,payload&order=created_at.asc"
        ) ?: return
        if (!res.ok) return
        val arr = res.array() ?: return
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { accept(it) }
    }

    suspend fun send(type: String, payload: JSONObject): FnResult =
        deps.api.function(
            "send-signal",
            JSONObject().put("session_id", sessionId).put("message_type", type).put("payload", payload),
        )

    fun stop() {
        realtime.stop()
        jobs.forEach { it.cancel() }
        jobs.clear()
        inbox.close()
    }
}

fun candidateJson(c: IceCandidate): JSONObject =
    JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)

fun candidateFrom(p: JSONObject): IceCandidate? {
    val sdp = p.optString("candidate", "")
    if (sdp.isEmpty()) return null
    return IceCandidate(p.optString("sdpMid", ""), p.optInt("sdpMLineIndex", 0), sdp)
}
