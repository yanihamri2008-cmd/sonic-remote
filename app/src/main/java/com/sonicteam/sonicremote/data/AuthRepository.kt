package com.sonicteam.sonicremote.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * Supabase Auth (GoTrue) over its public REST API. Uses only the URL and the
 * publishable key; no service-role key ever exists in the app.
 */
class AuthRepository(supabaseUrl: String, private val publishableKey: String) {
    private val baseUrl = supabaseUrl.trimEnd('/')
    val isConfigured: Boolean get() = publishableKey.isNotBlank()

    private class Http(val code: Int, val body: String)

    suspend fun signUp(email: String, password: String): AuthResult {
        if (!isConfigured) return AuthResult.Failure(AuthError.NotConfigured)
        val res = post("/auth/v1/signup", creds(email, password)) ?: return AuthResult.Failure(AuthError.Network)
        if (res.code in 200..299) {
            val json = parse(res.body)
            val session = json?.let { toSession(it) }
            return if (session != null) AuthResult.Success(session) else AuthResult.NeedsEmailConfirmation
        }
        return AuthResult.Failure(mapError(res))
    }

    suspend fun signIn(email: String, password: String): AuthResult {
        if (!isConfigured) return AuthResult.Failure(AuthError.NotConfigured)
        val res = post("/auth/v1/token?grant_type=password", creds(email, password))
            ?: return AuthResult.Failure(AuthError.Network)
        if (res.code in 200..299) {
            val session = parse(res.body)?.let { toSession(it) }
            if (session != null) return AuthResult.Success(session)
            return AuthResult.Failure(AuthError.Unknown)
        }
        return AuthResult.Failure(mapError(res))
    }

    suspend fun refresh(refreshToken: String): AuthResult {
        if (!isConfigured) return AuthResult.Failure(AuthError.NotConfigured)
        val body = JSONObject().put("refresh_token", refreshToken)
        val res = post("/auth/v1/token?grant_type=refresh_token", body) ?: return AuthResult.Failure(AuthError.Network)
        if (res.code in 200..299) {
            val session = parse(res.body)?.let { toSession(it) }
            if (session != null) return AuthResult.Success(session)
        }
        return AuthResult.Failure(if (res.code in 200..299) AuthError.Unknown else mapError(res))
    }

    /** Best effort: the local session is cleared by the caller regardless of the outcome. */
    suspend fun logout(accessToken: String) {
        post("/auth/v1/logout", null, accessToken)
    }

    private fun creds(email: String, password: String) =
        JSONObject().put("email", email).put("password", password)

    private suspend fun post(path: String, body: JSONObject?, bearer: String? = null): Http? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("apikey", publishableKey)
                    if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
                }
                conn.outputStream.use { it.write((body?.toString() ?: "{}").toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                Http(code, text)
            } catch (e: IOException) {
                null
            } finally {
                conn?.disconnect()
            }
        }

    private fun parse(text: String): JSONObject? =
        try { JSONObject(text) } catch (e: JSONException) { null }

    private fun toSession(j: JSONObject): Session? {
        val access = j.optString("access_token", "")
        val refresh = j.optString("refresh_token", "")
        val user = j.optJSONObject("user")
        val uid = user?.optString("id", "") ?: ""
        if (access.isEmpty() || refresh.isEmpty() || uid.isEmpty()) return null
        val now = System.currentTimeMillis() / 1000
        val exp = if (j.has("expires_at")) j.optLong("expires_at") else now + j.optLong("expires_in", 3600)
        return Session(access, refresh, exp, uid, user?.optString("email", "") ?: "")
    }

    private fun mapError(res: Http): AuthError {
        val j = parse(res.body)
        val code = (j?.optString("error_code", "") ?: "").ifEmpty { j?.optString("code", "") ?: "" }
        val msg = (j?.optString("msg", "") ?: "").ifEmpty { j?.optString("error_description", "") ?: "" }.lowercase()
        return when {
            res.code == 429 || code == "over_request_rate_limit" || code == "over_email_send_rate_limit" -> AuthError.RateLimited
            code == "invalid_credentials" || "invalid login credentials" in msg -> AuthError.InvalidCredentials
            code == "email_not_confirmed" || "email not confirmed" in msg -> AuthError.EmailNotConfirmed
            code == "user_already_exists" || code == "email_exists" || "already registered" in msg -> AuthError.AlreadyRegistered
            code == "weak_password" || "password should be" in msg -> AuthError.WeakPassword
            code == "signup_disabled" || "signups not allowed" in msg -> AuthError.SignupDisabled
            else -> AuthError.Unknown
        }
    }
}
