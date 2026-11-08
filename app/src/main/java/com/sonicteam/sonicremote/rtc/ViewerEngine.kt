package com.sonicteam.sonicremote.rtc

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import org.json.JSONObject
import org.webrtc.DataChannel
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

    /** What the Agent currently allows (they can change it at any time). */
    var controlAllowed by mutableStateOf(false)
        private set
    var termAllowed by mutableStateOf(false)
        private set
    val termLog = mutableStateListOf<String>()
    private var nextTermId = 1

    val eglBase: EglBase by lazy { EglBase.create() }
    private var dataChannel: DataChannel? = null

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

                override fun onDataChannel(p0: DataChannel?) {
                    val ch = p0 ?: return
                    dataChannel = ch
                    ch.registerObserver(object : DataChannel.Observer {
                        override fun onBufferedAmountChange(p0: Long) {}
                        override fun onStateChange() {
                            if (ch.state() == DataChannel.State.OPEN) send(JSONObject().put("t", "hello"))
                        }
                        override fun onMessage(p0: DataChannel.Buffer?) {
                            val b = p0 ?: return
                            if (b.data.remaining() > 16384) return
                            val bytes = ByteArray(b.data.remaining())
                            b.data.get(bytes)
                            onData(String(bytes, Charsets.UTF_8))
                        }
                    })
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

    // ------------------------------------------------ control + terminal messages

    private fun send(o: JSONObject): Boolean {
        val ch = dataChannel ?: return false
        if (ch.state() != DataChannel.State.OPEN) return false
        return ch.send(DataChannel.Buffer(ByteBuffer.wrap(o.toString().toByteArray(Charsets.UTF_8)), false))
    }

    private fun onData(text: String) {
        val j = try { JSONObject(text) } catch (e: Exception) { return }
        when (j.optString("t")) {
            "perm" -> {
                controlAllowed = j.optBoolean("control", false)
                termAllowed = j.optBoolean("term", false)
            }
            "term_out" -> {
                j.optString("text").split("\n").forEach { termLog.add(it) }
                while (termLog.size > 200) termLog.removeAt(0)
            }
        }
    }

    // Coordinates are normalized (0..1) in the Agent's screen space; the Agent re-checks consent.
    fun sendTap(x: Float, y: Float) {
        if (controlAllowed) send(JSONObject().put("t", "tap").put("x", x.toDouble()).put("y", y.toDouble()))
    }

    fun sendLong(x: Float, y: Float) {
        if (controlAllowed) send(JSONObject().put("t", "long").put("x", x.toDouble()).put("y", y.toDouble()))
    }

    fun sendSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        if (controlAllowed) {
            send(
                JSONObject().put("t", "swipe").put("x1", x1.toDouble()).put("y1", y1.toDouble())
                    .put("x2", x2.toDouble()).put("y2", y2.toDouble()).put("d", durationMs.coerceIn(50L, 1500L))
            )
        }
    }

    fun sendKey(name: String) {
        if (controlAllowed) send(JSONObject().put("t", "key").put("k", name))
    }

    fun runTerm(cmd: String) {
        val c = cmd.trim().take(40)
        if (c.isEmpty()) return
        if (c == "clear") { termLog.clear(); return }
        termLog.add("$ $c")
        if (!send(JSONObject().put("t", "term").put("id", nextTermId++).put("cmd", c))) {
            termLog.add("(not connected)")
        }
    }

    fun stop() {
        runCatching { dataChannel?.close() }
        runCatching { dataChannel?.dispose() }
        dataChannel = null
        controlAllowed = false
        termAllowed = false
        termLog.clear()
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
