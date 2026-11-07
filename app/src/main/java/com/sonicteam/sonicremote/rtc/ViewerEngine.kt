package com.sonicteam.sonicremote.rtc

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/** Controller side: answers the Agent's offer and exposes the remote screen as a [VideoTrack]. */
class ViewerEngine(
    private val context: Context,
    private val deps: RtcDeps,
    private val scope: CoroutineScope,
) {
    enum class State { Idle, WaitingOffer, Connecting, Live, Failed }

    var state by mutableStateOf(State.Idle)
        private set
    var remoteTrack by mutableStateOf<VideoTrack?>(null)
        private set

    val eglBase: EglBase by lazy { EglBase.create() }

    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var channel: SignalingChannel? = null
    private var sessionId: String? = null
    private var remoteSet = false
    private val pendingIce = mutableListOf<IceCandidate>()

    fun start(session: String, myDeviceId: String) {
        if (sessionId == session) return
        stop()
        sessionId = session
        state = State.WaitingOffer
        Rtc.init(context)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        channel = SignalingChannel(deps, scope, session, myDeviceId) { handle(it) }.also { it.start() }
    }

    private suspend fun handle(m: SignalMsg) {
        when (m.type) {
            "offer" -> onOffer(m.payload)
            "ice_candidate" -> {
                val c = candidateFrom(m.payload) ?: return
                val conn = pc
                if (conn != null && remoteSet) conn.addIceCandidate(c) else pendingIce += c
            }
        }
    }

    private suspend fun onOffer(p: JSONObject) {
        if (pc != null) return // already negotiating this session
        val f = factory ?: return
        val sid = sessionId ?: return
        state = State.Connecting
        try {
            val ice = deps.api.function("ice-config", JSONObject().put("session_id", sid))
            val conn = f.createPeerConnection(iceConfig(parseIceServers(ice.json)), object : PcObserver() {
                override fun onIceCandidate(p0: IceCandidate?) {
                    val c = p0 ?: return
                    scope.launch { channel?.send("ice_candidate", candidateJson(c)) }
                }

                override fun onTrack(transceiver: RtpTransceiver?) {
                    (transceiver?.receiver?.track() as? VideoTrack)?.let { remoteTrack = it }
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> { state = State.Live; channel?.fast = false }
                        PeerConnection.PeerConnectionState.FAILED -> state = State.Failed
                        PeerConnection.PeerConnectionState.DISCONNECTED -> if (state == State.Live) state = State.Connecting
                        else -> Unit
                    }
                }
            }) ?: throw IllegalStateException("peer connection failed")
            pc = conn
            conn.setRemoteAwait(SessionDescription(SessionDescription.Type.OFFER, p.optString("sdp")))
            remoteSet = true
            pendingIce.forEach { conn.addIceCandidate(it) }
            pendingIce.clear()
            val answer = conn.createAnswerAwait()
            conn.setLocalAwait(answer)
            channel?.send("answer", JSONObject().put("type", "answer").put("sdp", answer.description))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            state = State.Failed
        }
    }

    fun stop() {
        channel?.stop()
        channel = null
        remoteTrack = null
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }
        pc = null
        runCatching { factory?.dispose() }
        factory = null
        pendingIce.clear()
        remoteSet = false
        sessionId = null
        state = State.Idle
    }

    fun release() {
        stop()
        runCatching { eglBase.release() }
    }
}
