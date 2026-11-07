package com.sonicteam.sonicremote.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.sonicteam.sonicremote.BuildConfig
import com.sonicteam.sonicremote.R
import com.sonicteam.sonicremote.data.ApiClient
import com.sonicteam.sonicremote.rtc.PcObserver
import com.sonicteam.sonicremote.rtc.RtcDeps
import com.sonicteam.sonicremote.rtc.Rtc
import com.sonicteam.sonicremote.rtc.SignalMsg
import com.sonicteam.sonicremote.rtc.SignalingChannel
import com.sonicteam.sonicremote.rtc.TokenBridge
import com.sonicteam.sonicremote.rtc.candidateFrom
import com.sonicteam.sonicremote.rtc.candidateJson
import com.sonicteam.sonicremote.rtc.createOfferAwait
import com.sonicteam.sonicremote.rtc.iceConfig
import com.sonicteam.sonicremote.rtc.parseIceServers
import com.sonicteam.sonicremote.rtc.setLocalAwait
import com.sonicteam.sonicremote.rtc.setRemoteAwait
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Agent side. Captures the screen with the official MediaProjection permission and streams it
 * over WebRTC. It runs ONLY as a visible foreground service: a persistent notification with a
 * Stop action is shown for as long as the screen is shared.
 */
class ScreenShareService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val api = ApiClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY) { TokenBridge.provider?.invoke() }
    private val deps by lazy {
        RtcDeps(api, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY) { TokenBridge.provider?.invoke() }
    }

    private var running = false
    private var stopping = false
    private var sessionId = ""
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var pc: PeerConnection? = null
    private var channel: SignalingChannel? = null
    private var remoteSet = false
    private val pendingIce = mutableListOf<IceCandidate>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSharing(intent)
            ACTION_STOP -> stopSharing(endSession = true)
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ start

    private fun startSharing(intent: Intent) {
        if (running) return
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        val sid = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
        val peer = intent.getStringExtra(EXTRA_PEER_NAME).orEmpty()
        if (data == null || sid.isEmpty() || deviceId.isEmpty() || resultCode == 0) {
            stopSelf()
            return
        }
        running = true
        sessionId = sid
        ShareStatus.state.value = ShareStatus.State.Starting

        // The visible notification must exist BEFORE the screen is captured.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(peer),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        scope.launch {
            try {
                begin(data, deviceId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ShareStatus.state.value = ShareStatus.State.Error
                stopSharing(endSession = true)
            }
        }
    }

    private suspend fun begin(data: Intent, deviceId: String) {
        Rtc.init(this)
        val eglBase = EglBase.create().also { egl = it }
        val f = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory().also { factory = it }

        val ice = api.function("ice-config", JSONObject().put("session_id", sessionId))

        // If the user (or the system) ends the projection, stop everything immediately.
        val cap = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() {
                scope.launch { stopSharing(endSession = true) }
            }
        }).also { capturer = it }
        val th = SurfaceTextureHelper.create("sonic-capture", eglBase.eglBaseContext).also { helper = it }
        val src = f.createVideoSource(true).also { source = it }
        cap.initialize(th, applicationContext, src.capturerObserver)
        val (w, h) = captureSize()
        cap.startCapture(w, h, 15)
        val t = f.createVideoTrack("sonic-screen", src).also { track = it }

        val conn = f.createPeerConnection(iceConfig(parseIceServers(ice.json)), object : PcObserver() {
            override fun onIceCandidate(p0: IceCandidate?) {
                val c = p0 ?: return
                scope.launch { channel?.send("ice_candidate", candidateJson(c)) }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        ShareStatus.state.value = ShareStatus.State.Live
                        channel?.fast = false
                    }
                    PeerConnection.PeerConnectionState.FAILED -> {
                        ShareStatus.state.value = ShareStatus.State.Error
                        scope.launch { stopSharing(endSession = true) }
                    }
                    else -> Unit
                }
            }
        }) ?: throw IllegalStateException("peer connection failed")
        pc = conn
        conn.addTrack(t, listOf("sonic-stream"))

        channel = SignalingChannel(deps, scope, sessionId, deviceId) { onSignal(it) }.also { it.start() }

        val offer = conn.createOfferAwait()
        conn.setLocalAwait(offer)
        channel?.send("offer", JSONObject().put("type", "offer").put("sdp", offer.description))
    }

    private suspend fun onSignal(m: SignalMsg) {
        val conn = pc ?: return
        when (m.type) {
            "answer" -> {
                if (remoteSet) return
                conn.setRemoteAwait(SessionDescription(SessionDescription.Type.ANSWER, m.payload.optString("sdp")))
                remoteSet = true
                pendingIce.forEach { conn.addIceCandidate(it) }
                pendingIce.clear()
            }
            "ice_candidate" -> {
                val c = candidateFrom(m.payload) ?: return
                if (remoteSet) conn.addIceCandidate(c) else pendingIce += c
            }
        }
    }

    /** Screen size capped at 1280 px on the long side to keep bandwidth reasonable. */
    private fun captureSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val scale = min(1f, 1280f / max(dm.widthPixels, dm.heightPixels))
        val w = (dm.widthPixels * scale).toInt() and 1.inv()
        val h = (dm.heightPixels * scale).toInt() and 1.inv()
        return w to h
    }

    // ------------------------------------------------------------------- stop

    /** Idempotent. When [endSession] is true the Agent's consent is withdrawn on the server too. */
    private fun stopSharing(endSession: Boolean) {
        if (stopping) return
        stopping = true
        scope.launch {
            if (endSession && sessionId.isNotEmpty()) {
                withTimeoutOrNull(5_000) {
                    api.function("session-end", JSONObject().put("session_id", sessionId).put("revoke", true))
                }
            }
            teardown()
            ServiceCompat.stopForeground(this@ScreenShareService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun teardown() {
        channel?.stop(); channel = null
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }; capturer = null
        runCatching { track?.dispose() }; track = null
        runCatching { source?.dispose() }; source = null
        runCatching { helper?.dispose() }; helper = null
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }; pc = null
        runCatching { factory?.dispose() }; factory = null
        runCatching { egl?.release() }; egl = null
        pendingIce.clear()
        remoteSet = false
        running = false
        ShareStatus.state.value = ShareStatus.State.Idle
    }

    override fun onDestroy() {
        // Covers stopService() from the app (session ended by the other side or expired).
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    // ----------------------------------------------------------- notification

    private fun buildNotification(peer: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.share_channel_name), NotificationManager.IMPORTANCE_HIGH)
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_share)
            .setContentTitle(getString(R.string.share_notif_title))
            .setContentText(getString(R.string.share_notif_text, peer))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.share_notif_stop), stop)
            .build()
    }

    companion object {
        private const val ACTION_START = "com.sonicteam.sonicremote.share.START"
        private const val ACTION_STOP = "com.sonicteam.sonicremote.share.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "projection_data"
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_DEVICE_ID = "device_id"
        private const val EXTRA_PEER_NAME = "peer_name"
        private const val CHANNEL_ID = "sonic_sharing"
        private const val NOTIFICATION_ID = 4101

        fun start(context: Context, resultCode: Int, data: Intent, sessionId: String, deviceId: String, peerName: String) {
            val i = Intent(context, ScreenShareService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_DEVICE_ID, deviceId)
                .putExtra(EXTRA_PEER_NAME, peerName)
            ContextCompat.startForegroundService(context, i)
        }

        /** Stops sharing AND ends the session (consent withdrawn). */
        fun requestStop(context: Context) {
            runCatching { context.startService(Intent(context, ScreenShareService::class.java).setAction(ACTION_STOP)) }
        }

        /** Stops local capture only (used when the session already ended on the server). */
        fun stopLocal(context: Context) {
            context.stopService(Intent(context, ScreenShareService::class.java))
        }
    }
}
