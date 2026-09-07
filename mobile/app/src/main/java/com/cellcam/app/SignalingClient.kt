package com.cellcam.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cliente de sinalização WebSocket para o servidor local do CellCam.
 * Conecta como "sender" (celular) numa sala e recebe mensagens relay do receiver (PC).
 */
class SignalingClient(
    val serverIp: String,
    val roomCode: String,
    private val scope: CoroutineScope,
    private val listener: Listener,
    val port: Int = 8081
) {
    interface Listener {
        fun onConnected()
        fun onPeerJoined()
        fun onPeerLeft()
        fun onSignal(from: String, data: JSONObject)
        fun onError(message: String)
        fun onClosed()
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var job: Job? = null

    fun connect() {
        runCatching { ws?.cancel() }
        ws = null

        val url = "ws://$serverIp:$port"
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val join = JSONObject()
                    .put("type", "join")
                    .put("payload", JSONObject().put("roomCode", roomCode).put("role", "sender"))
                webSocket.send(join.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncoming(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onError(t.message ?: "Falha de conexão com o servidor")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed()
            }
        })
    }

    private fun handleIncoming(text: String) {
        try {
            val msg = JSONObject(text)
            when (msg.getString("type")) {
                "joined" -> listener.onConnected()
                "peer-joined" -> listener.onPeerJoined()
                "peer-left" -> listener.onPeerLeft()
                "signal" -> {
                    val from = msg.getString("from")
                    val data = msg.getJSONObject("data")
                    listener.onSignal(from, data)
                }
                "error" -> listener.onError(msg.optString("message", "Erro desconhecido"))
            }
        } catch (e: Exception) {
            listener.onError("Mensagem inválida: ${e.message}")
        }
    }

    fun sendSignal(kind: String, payload: JSONObject) {
        val data = JSONObject().put("kind", kind)
        val it = payload.keys()
        while (it.hasNext()) {
            val key = it.next()
            data.put(key, payload.get(key))
        }
        val msg = JSONObject().put("type", "signal").put("data", data)
        ws?.send(msg.toString())
    }

    fun close() {
        runCatching { ws?.close(1000, "bye") }
        job?.cancel()
    }
}