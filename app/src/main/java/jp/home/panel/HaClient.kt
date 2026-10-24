package jp.home.panel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class Entity(val entityId: String, val state: String, val attributes: JSONObject) {
    val domain: String get() = entityId.substringBefore('.')
    val name: String get() = attributes.optString("friendly_name", entityId)
    val isOn: Boolean
        get() = state in setOf(
            "on", "open", "opening", "heat", "cool", "auto", "heat_cool", "dry", "fan_only",
        )
}

class HaException(message: String) : Exception(message)
class AuthInvalidException : Exception("認証が無効です")

/** Home Assistant WebSocket API クライアント（1接続 = 1インスタンス） */
class HaClient(private val baseUrl: String, private val http: OkHttpClient) {
    private var ws: WebSocket? = null
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val handlers = ConcurrentHashMap<Int, (JSONObject) -> Unit>()
    private val closed = CompletableDeferred<Throwable?>()

    private val _states = MutableStateFlow<Map<String, Entity>>(emptyMap())
    val states: StateFlow<Map<String, Entity>> = _states

    suspend fun connect(accessToken: String) {
        val authed = CompletableDeferred<Unit>()
        val wsUrl = baseUrl.replaceFirst("http", "ws") + "/api/websocket"

        ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = JSONObject(text)
                when (msg.optString("type")) {
                    "auth_required" -> webSocket.send(
                        JSONObject().put("type", "auth").put("access_token", accessToken).toString()
                    )
                    "auth_ok" -> authed.complete(Unit)
                    "auth_invalid" -> {
                        authed.completeExceptionally(AuthInvalidException())
                        webSocket.close(1000, null)
                    }
                    "result" -> pending.remove(msg.optInt("id"))?.complete(msg)
                    "event" -> msg.optJSONObject("event")?.let { handlers[msg.optInt("id")]?.invoke(it) }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                authed.completeExceptionally(HaException("接続が閉じられました"))
                shutdown(null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                authed.completeExceptionally(t)
                shutdown(t)
            }
        })

        withTimeout(10_000) { authed.await() }
        loadStates()
        subscribe(JSONObject().put("type", "subscribe_events").put("event_type", "state_changed")) { ev ->
            val data = ev.optJSONObject("data") ?: return@subscribe
            val id = data.optString("entity_id")
            val newState = data.optJSONObject("new_state")
            _states.value = if (newState == null) _states.value - id else _states.value + (id to newState.toEntity())
        }
    }

    suspend fun awaitClosed(): Throwable? = closed.await()

    fun close() {
        ws?.close(1000, null)
    }

    private fun shutdown(t: Throwable?) {
        closed.complete(t)
        val err = t ?: HaException("切断されました")
        pending.values.forEach { it.completeExceptionally(err) }
        pending.clear()
    }

    private fun send(payload: JSONObject, id: Int): CompletableDeferred<JSONObject> {
        val d = CompletableDeferred<JSONObject>()
        pending[id] = d
        payload.put("id", id)
        if (ws?.send(payload.toString()) != true) {
            pending.remove(id)
            d.completeExceptionally(HaException("サーバーに送信できませんでした"))
        }
        return d
    }

    private fun JSONObject.errorMessage() =
        optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: "サーバーエラー"

    suspend fun request(payload: JSONObject): JSONObject {
        val r = withTimeout(15_000) { send(payload, nextId.getAndIncrement()).await() }
        if (!r.optBoolean("success")) throw HaException(r.errorMessage())
        return r
    }

    /** イベントを受け取るコマンド。戻り値の id は removeHandler に渡す */
    suspend fun subscribe(payload: JSONObject, onEvent: (JSONObject) -> Unit): Int {
        val id = nextId.getAndIncrement()
        handlers[id] = onEvent
        val r = try {
            withTimeout(15_000) { send(payload, id).await() }
        } catch (e: Exception) {
            handlers.remove(id)
            throw e
        }
        if (!r.optBoolean("success")) {
            handlers.remove(id)
            throw HaException(r.errorMessage())
        }
        return id
    }

    fun removeHandler(id: Int) {
        handlers.remove(id)
    }

    fun sendBinary(bytes: ByteArray): Boolean = ws?.send(bytes.toByteString()) ?: false

    private suspend fun loadStates() {
        val arr = request(JSONObject().put("type", "get_states")).getJSONArray("result")
        val map = HashMap<String, Entity>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i).toEntity()
            map[e.entityId] = e
        }
        _states.value = map
    }

    suspend fun callService(domain: String, service: String, entityId: String, data: JSONObject = JSONObject()) {
        request(
            JSONObject()
                .put("type", "call_service")
                .put("domain", domain)
                .put("service", service)
                .put("target", JSONObject().put("entity_id", entityId))
                .put("service_data", data)
        )
    }

    /** 文字で命令（Assist の会話エージェントに渡す） */
    suspend fun processText(text: String): String {
        val r = request(
            JSONObject().put("type", "conversation/process").put("text", text).put("language", "ja")
        )
        return r.optJSONObject("result")?.optJSONObject("response")?.optJSONObject("speech")
            ?.optJSONObject("plain")?.optString("speech").orEmpty()
    }
}

private fun JSONObject.toEntity() =
    Entity(getString("entity_id"), optString("state"), optJSONObject("attributes") ?: JSONObject())
