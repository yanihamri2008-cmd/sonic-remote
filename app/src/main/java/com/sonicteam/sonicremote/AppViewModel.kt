package com.sonicteam.sonicremote

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sonicteam.sonicremote.data.ActiveSession
import com.sonicteam.sonicremote.data.ApiClient
import com.sonicteam.sonicremote.data.AuthError
import com.sonicteam.sonicremote.data.AuthRepository
import com.sonicteam.sonicremote.data.AuthResult
import com.sonicteam.sonicremote.data.DeviceInfo
import com.sonicteam.sonicremote.data.DeviceRepository
import com.sonicteam.sonicremote.data.DeviceResult
import com.sonicteam.sonicremote.data.IncomingRequest
import com.sonicteam.sonicremote.data.Outgoing
import com.sonicteam.sonicremote.data.PairingRepository
import com.sonicteam.sonicremote.data.Prefs
import com.sonicteam.sonicremote.data.RealtimeClient
import com.sonicteam.sonicremote.data.Session
import com.sonicteam.sonicremote.data.SessionStore
import com.sonicteam.sonicremote.data.Role
import com.sonicteam.sonicremote.rtc.RtcDeps
import com.sonicteam.sonicremote.rtc.TokenBridge
import com.sonicteam.sonicremote.rtc.ViewerEngine
import com.sonicteam.sonicremote.share.ControlState
import com.sonicteam.sonicremote.share.ScreenShareService
import com.sonicteam.sonicremote.data.parseTimeMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class Screen { Splash, Terms, TermsView, Auth, Home, About, Agent, Controller, Session }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val store = SessionStore(app)
    private val repo = AuthRepository(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY)
    private val api = ApiClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY) { accessToken() }
    private val devices = DeviceRepository(api, prefs)
    private val pairing = PairingRepository(api)
    private val realtime = RealtimeClient(
        BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY, { accessToken() }, viewModelScope,
    )
    private val tokenMutex = Mutex()
    val viewer = ViewerEngine(
        app,
        RtcDeps(api, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY) { accessToken() },
        viewModelScope,
    )
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var restoreJob: Job? = null
    private var syncJob: Job? = null
    private var lastBeat = 0L

    // ---- navigation / auth state
    var screen by mutableStateOf(Screen.Splash)
        private set
    var session by mutableStateOf<Session?>(null)
        private set
    var authBusy by mutableStateOf(false)
        private set
    var authError by mutableStateOf<AuthError?>(null)
        private set
    var confirmNotice by mutableStateOf(false)
        private set

    // ---- device / pairing / session state
    var device by mutableStateOf<DeviceInfo?>(null)
        private set
    var deviceError by mutableStateOf<String?>(null)
        private set
    var incoming by mutableStateOf<List<IncomingRequest>>(emptyList())
        private set
    var outgoing by mutableStateOf<Outgoing>(Outgoing.None)
        private set
    var activeSession by mutableStateOf<ActiveSession?>(null)
        private set
    var pairingBusy by mutableStateOf(false)
        private set
    var pairingMessage by mutableStateOf<String?>(null)
        private set

    val isConfigured: Boolean get() = repo.isConfigured

    init {
        TokenBridge.provider = { accessToken() } // lets the sharing service use the same token refresh
        restoreJob = viewModelScope.launch { restoreSession() }
    }

    override fun onCleared() {
        viewer.release()
        super.onCleared()
    }

    private fun stopShareService() {
        ControlState.reset()
        ScreenShareService.stopLocal(getApplication())
    }

    // ------------------------------------------------------------------ auth

    private suspend fun restoreSession() {
        val saved = store.load() ?: return
        session = saved
        if (!saved.expiresSoon()) return
        when (val r = repo.refresh(saved.refreshToken)) {
            is AuthResult.Success -> { store.save(r.session); session = r.session }
            is AuthResult.Failure ->
                // Offline: keep the saved session. Rejected by the server: sign out locally.
                if (r.error != AuthError.Network) { store.clear(); session = null }
            AuthResult.NeedsEmailConfirmation -> Unit
        }
    }

    /** Always returns a non-expired access token (refreshing if needed), or null when signed out. */
    suspend fun accessToken(): String? = tokenMutex.withLock {
        val s = session ?: return@withLock null
        if (!s.expiresSoon()) return@withLock s.accessToken
        when (val r = repo.refresh(s.refreshToken)) {
            is AuthResult.Success -> { store.save(r.session); session = r.session; r.session.accessToken }
            is AuthResult.Failure ->
                if (r.error == AuthError.Network) s.accessToken else { forceSignOut(); null }
            AuthResult.NeedsEmailConfirmation -> null
        }
    }

    fun onSplashDone() {
        viewModelScope.launch {
            restoreJob?.join()
            route()
        }
    }

    private fun route() {
        screen = when {
            !prefs.termsAccepted() -> Screen.Terms
            session == null -> Screen.Auth
            else -> Screen.Home
        }
        if (screen == Screen.Home) startSync()
    }

    fun go(target: Screen) {
        screen = target
    }

    fun acceptTerms() {
        prefs.acceptTerms()
        route()
    }

    fun clearAuthMessages() {
        authError = null
        confirmNotice = false
    }

    fun signIn(email: String, password: String) = submit { repo.signIn(email.trim(), password) }

    fun signUp(email: String, password: String) = submit { repo.signUp(email.trim(), password) }

    private fun submit(block: suspend () -> AuthResult) {
        if (authBusy) return
        authBusy = true
        clearAuthMessages()
        viewModelScope.launch {
            when (val r = block()) {
                is AuthResult.Success -> { store.save(r.session); session = r.session; screen = Screen.Home; startSync() }
                AuthResult.NeedsEmailConfirmation -> confirmNotice = true
                is AuthResult.Failure -> authError = r.error
            }
            authBusy = false
        }
    }

    fun signOut() {
        val old = session
        forceSignOut()
        if (old != null) viewModelScope.launch { repo.logout(old.accessToken) }
    }

    private fun forceSignOut() {
        stopSync()
        session = null
        store.clear()
        clearAuthMessages()
        screen = Screen.Auth
    }

    // ------------------------------------------------- sync (realtime + poll)

    private fun startSync() {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            realtime.start(listOf("pairing_requests", "remote_sessions")) { _, _, _ -> signal.trySend(Unit) }
            while (isActive) {
                try {
                    syncOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // keep the loop alive; the next tick retries
                }
                delay(300)
                withTimeoutOrNull(5_000) { signal.receive() } // realtime wakes us early; poll is the safety net
            }
        }
    }

    private fun stopSync() {
        syncJob?.cancel()
        syncJob = null
        realtime.stop()
        viewer.stop()
        stopShareService()
        device = null
        deviceError = null
        incoming = emptyList()
        outgoing = Outgoing.None
        activeSession = null
        pairingMessage = null
        lastBeat = 0L
    }

    private suspend fun syncOnce() {
        val s = session ?: return
        val dev: DeviceInfo = device ?: when (val r = devices.ensure(s.userId)) {
            is DeviceResult.Ok -> { device = r.device; deviceError = null; r.device }
            is DeviceResult.Err -> { deviceError = r.error; return }
        }
        val now = System.currentTimeMillis()
        if (now - lastBeat > 60_000) {
            devices.heartbeat(dev.id)
            lastBeat = now
        }

        pairing.incoming(s.userId)?.let { incoming = it }

        val o = outgoing
        if (o is Outgoing.Waiting) {
            if (System.currentTimeMillis() > o.expiresAtMs) {
                outgoing = Outgoing.Expired
            } else {
                when (pairing.requestStatus(o.requestId)) {
                    "rejected" -> outgoing = Outgoing.Rejected
                    "expired" -> outgoing = Outgoing.Expired
                    "approved" -> outgoing = Outgoing.None // the session shows up just below
                    else -> Unit
                }
            }
        }

        val sessions = pairing.activeSessions(s.userId)
        if (sessions != null) {
            val a = sessions.firstOrNull()
            val prev = activeSession
            activeSession = a
            // Controller: watch the Agent's screen. Anyone: stop capture/streaming once the session is gone.
            if (a != null && a.role == Role.Controller) viewer.start(a.id, dev.id) else viewer.stop()
            if (a == null && prev != null) stopShareService()
            if (a != null && a.id != prev?.id && screen in setOf(Screen.Home, Screen.Agent, Screen.Controller)) {
                screen = Screen.Session
            }
            if (a == null && prev != null && screen == Screen.Session) screen = Screen.Home
        }
    }

    fun refreshNow() {
        signal.trySend(Unit)
    }

    // ----------------------------------------------------------------- actions

    fun sendRequest(rawCode: String) {
        val dev = device ?: return
        val code = rawCode.uppercase().filter { it.isLetterOrDigit() }
        if (code.length != 8) { outgoing = Outgoing.Failed("invalid_code"); return }
        if (outgoing is Outgoing.Sending || outgoing is Outgoing.Waiting) return
        outgoing = Outgoing.Sending
        viewModelScope.launch {
            val r = pairing.sendRequest(dev.id, code)
            outgoing = if (r.ok && r.json != null) {
                Outgoing.Waiting(
                    requestId = r.json.optString("request_id"),
                    targetName = r.json.optString("target_device_name", ""),
                    expiresAtMs = parseTimeMs(r.json.optString("expires_at")),
                )
            } else {
                Outgoing.Failed(r.error ?: "unknown")
            }
            refreshNow()
        }
    }

    fun dismissOutgoing() {
        outgoing = Outgoing.None
    }

    fun respond(requestId: String, approve: Boolean) {
        if (pairingBusy) return
        pairingBusy = true
        pairingMessage = null
        viewModelScope.launch {
            val r = pairing.respond(requestId, approve)
            if (!r.ok) pairingMessage = r.error ?: "unknown"
            incoming = incoming.filter { it.id != requestId }
            pairingBusy = false
            refreshNow()
        }
    }

    fun endSession(revoke: Boolean) {
        val a = activeSession ?: return
        if (pairingBusy) return
        pairingBusy = true
        viewModelScope.launch {
            stopShareService()
            val r = pairing.endSession(a.id, revoke)
            if (!r.ok) pairingMessage = r.error ?: "unknown"
            activeSession = null
            screen = Screen.Home
            pairingBusy = false
            refreshNow()
        }
    }

    fun revokeThisDevice() {
        val s = session ?: return
        val d = device ?: return
        viewModelScope.launch {
            if (devices.revoke(s.userId, d.id)) {
                device = null // a fresh device identity (new pairing code) is registered on the next sync
            }
            refreshNow()
        }
    }

    fun clearPairingMessage() {
        pairingMessage = null
    }
}
