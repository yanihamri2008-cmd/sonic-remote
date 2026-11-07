package com.sonicteam.sonicremote

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sonicteam.sonicremote.data.AuthError
import com.sonicteam.sonicremote.data.AuthRepository
import com.sonicteam.sonicremote.data.AuthResult
import com.sonicteam.sonicremote.data.Prefs
import com.sonicteam.sonicremote.data.Session
import com.sonicteam.sonicremote.data.SessionStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class Screen { Splash, Terms, TermsView, Auth, Home, About }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val store = SessionStore(app)
    private val repo = AuthRepository(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY)
    private var restoreJob: Job? = null

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

    val isConfigured: Boolean get() = repo.isConfigured

    init {
        restoreJob = viewModelScope.launch { restoreSession() }
    }

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
                is AuthResult.Success -> { store.save(r.session); session = r.session; screen = Screen.Home }
                AuthResult.NeedsEmailConfirmation -> confirmNotice = true
                is AuthResult.Failure -> authError = r.error
            }
            authBusy = false
        }
    }

    fun signOut() {
        val old = session
        session = null
        store.clear()
        clearAuthMessages()
        screen = Screen.Auth
        if (old != null) viewModelScope.launch { repo.logout(old.accessToken) }
    }
}
