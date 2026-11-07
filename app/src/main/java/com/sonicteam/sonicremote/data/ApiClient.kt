package com.sonicteam.sonicremote.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class FnResult(val ok: Boolean, val json: JSONObject?, val error: String?)

/**
 * Talks to Supabase with the signed-in user's access token (RLS applies).
 * Security-sensitive writes go through Edge Functions via [function].
 */
class ApiClient(
    supabaseUrl: String,
    private val key: String,
    private val token: suspend () -> String?,
) {
    private val base = supabaseUrl.trimEnd('/')
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    class Res(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
        fun array(): JSONArray? = try { JSONArray(body) } catch (e: JSONException) { null }
        fun obj(): JSONObject? = try { JSONObject(body) } catch (e: JSONException) { null }
    }

    /** Returns null on network failure. */
    suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        headers: Map<String, String> = emptyMap(),
    ): Res? {
        val tok = token() ?: return Res(401, "")
        return withContext(Dispatchers.IO) {
            try {
                val rb = if (method == "GET" || method == "DELETE") null
                else (body?.toString() ?: "{}").toRequestBody(jsonType)
                val b = Request.Builder().url(base + path).method(method, rb)
                    .header("apikey", key)
                    .header("Authorization", "Bearer $tok")
                headers.forEach { (k, v) -> b.header(k, v) }
                http.newCall(b.build()).execute().use { Res(it.code, it.body?.string() ?: "") }
            } catch (e: IOException) {
                null
            }
        }
    }

    suspend fun get(path: String): Res? = request("GET", path)

    suspend fun function(name: String, body: JSONObject): FnResult {
        val res = request("POST", "/functions/v1/$name", body) ?: return FnResult(false, null, "network")
        val obj = res.obj()
        if (res.ok) return FnResult(true, obj, null)
        val err = obj?.optString("error", "").orEmpty()
        return when {
            err.isNotEmpty() -> FnResult(false, obj, err)
            res.code == 401 -> FnResult(false, obj, "unauthorized")
            res.code == 404 -> FnResult(false, obj, "not_deployed") // gateway: function does not exist
            else -> FnResult(false, obj, "unknown")
        }
    }
}
