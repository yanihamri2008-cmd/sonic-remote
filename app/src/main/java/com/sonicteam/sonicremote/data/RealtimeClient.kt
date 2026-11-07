package com.sonicteam.sonicremote.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal Supabase Realtime (Phoenix protocol) client for postgres_changes.
 * Row-level security applies: the user's JWT decides which rows are delivered.
 * It reconnects automatically (and with a fresh token every 45 min).
 */
class RealtimeClient(
    supabaseUrl: String,
    private val key: String,
    private val token: suspend () -> String?,
    private val scope: CoroutineScope,
) {
    private val wsUrl = supabaseUrl.trimEnd('/').replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://") + "/realtime/v1/websocket?apikey=$key&vsn=1.0.0"
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var job: Job? = null
    private var refCounter = 0

    /** [onEvent] gets (table, eventType e.g. INSERT/UPDATE, new record or null). Called from a background thread. */
    fun start(tables: List<String>, filters: Map<String, String> = emptyMap(), onEvent: (String, String, JSONObject?) -> Unit) {
        stop()
        job = scope.launch {
            while (isActive) {
                try {
                    connectOnce(tables, filters, onEvent)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
                delay(3_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun connectOnce(
        tables: List<String>,
        filters: Map<String, String>,
        onEvent: (String, String, JSONObject?) -> Unit,
    ) {
        val tok = token() ?: return
        val closed = CompletableDeferred<Unit>()
        val socket = http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                tables.forEach { webSocket.send(joinMessage(it, filters[it], tok)) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    if (msg.optString("event") != "postgres_changes") return
                    val data = msg.optJSONObject("payload")?.optJSONObject("data") ?: return
                    onEvent(data.optString("table"), data.optString("type"), data.optJSONObject("record"))
                } catch (e: Exception) {
                    // ignore malformed frames
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.complete(Unit)
            }
        })
        val heartbeat = scope.launch {
            while (isActive) {
                delay(25_000)
                socket.send(frame("phoenix", "heartbeat", JSONObject()))
            }
        }
        try {
            withTimeoutOrNull(45 * 60_000L) { closed.await() }
        } finally {
            heartbeat.cancel()
            socket.close(1000, null)
        }
    }

    private fun nextRef(): String = (++refCounter).toString()

    private fun frame(topic: String, event: String, payload: JSONObject): String =
        JSONObject().put("topic", topic).put("event", event).put("payload", payload)
            .put("ref", nextRef()).toString()

    private fun joinMessage(table: String, filter: String?, accessToken: String): String {
        val change = JSONObject().put("event", "*").put("schema", "public").put("table", table)
        if (filter != null) change.put("filter", filter)
        val payload = JSONObject()
            .put("config", JSONObject().put("postgres_changes", JSONArray().put(change)).put("private", false))
            .put("access_token", accessToken)
        return frame("realtime:sonic-$table", "phx_join", payload)
    }
}
