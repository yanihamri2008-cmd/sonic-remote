package com.sonicteam.sonicremote.data

data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSec: Long,
    val userId: String,
    val email: String,
) {
    fun expiresSoon(nowSec: Long = System.currentTimeMillis() / 1000, marginSec: Long = 60): Boolean =
        expiresAtEpochSec - nowSec < marginSec
}

enum class AuthError {
    InvalidCredentials, EmailNotConfirmed, AlreadyRegistered, WeakPassword,
    RateLimited, SignupDisabled, Network, NotConfigured, Unknown
}

sealed interface AuthResult {
    data class Success(val session: Session) : AuthResult
    data object NeedsEmailConfirmation : AuthResult
    data class Failure(val error: AuthError) : AuthResult
}
