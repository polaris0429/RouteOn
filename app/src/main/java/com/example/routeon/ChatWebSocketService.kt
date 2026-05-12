package com.example.routeon

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.IBinder
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ChatWebSocketService : Service() {

    private var webSocket: WebSocket? = null
    private val httpClient = OkHttpClient.Builder()
        // pingInterval 제거: 서버(uvicorn --ws-ping-interval 20)가 PING을 보내고
        // OkHttp가 자동으로 PONG 응답 → 양쪽 동시 PING 충돌 방지
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private var reconnectDelayMs = 3_000L
    private var isReconnecting = false

    companion object {
        const val ACTION_CHAT_MESSAGE = "com.example.routeon.CHAT_MESSAGE"
        const val EXTRA_MSG_ID          = "msg_id"
        const val EXTRA_MSG_CONTENT     = "msg_content"
        const val EXTRA_MSG_SENDER_ID   = "msg_sender_id"
        const val EXTRA_MSG_CONV_ID     = "msg_conv_id"
        const val EXTRA_MSG_CREATED_AT  = "msg_created_at"

        @Volatile var isChatActivityVisible = false

        fun start(context: Context) =
            context.startService(Intent(context, ChatWebSocketService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, ChatWebSocketService::class.java))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (webSocket == null) connect()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.cancel()
        webSocket = null
    }

    private fun connect() {
        val prefs = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        val token = prefs.all["access_token"]?.toString()

        if (token.isNullOrEmpty()) return

        // 💡 핵심 해결: HTTP 주소를 무조건 강제로 WS 주소로 변환하여 연결 보장
        val wsUrl = Constants.BASE_URL
            .replaceFirst("http://", "ws://")
            .replaceFirst("https://", "wss://") + "/ws/chat?token=$token"

        val request = Request.Builder().url(wsUrl).build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectDelayMs = 3_000L
                isReconnecting = false
                Log.d("ChatWsService", "WebSocket 연결 성공: $wsUrl")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleEvent(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("ChatWsService", "WS 연결 실패: ${t.message}")
                this@ChatWebSocketService.webSocket = null
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                this@ChatWebSocketService.webSocket = null
                if (code != 1000) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (isReconnecting) return
        isReconnecting = true
        android.os.Handler(mainLooper).postDelayed({
            isReconnecting = false
            if (webSocket == null) connect()
        }, reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30_000L)
    }

    private fun handleEvent(raw: String) {
        try {
            val json = JSONObject(raw)
            val eventType = json.optString("type", json.optString("event", ""))

            // 서버 heartbeat ping → pong 즉시 응답
            if (eventType == "ping") {
                webSocket?.send("""{"type":"pong"}""")
                return
            }

            // 💡 핵심 해결: 이벤트가 chat.message 거나 일단 뭔가 넘어오면 무조건 캐치
            if (eventType == "chat.message" || raw.contains("content") || raw.contains("message")) {
                val data = json.optJSONObject("data") ?: json.optJSONObject("message") ?: json
                val content = data.optString("content", data.optString("text", "")).trim()
                val senderId = data.optString("sender_id", "")
                val msgId = data.optString("id", "")
                val convId = data.optString("conversation_id", "")
                val createdAt = data.optString("created_at", "")

                val myId = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).all["user_id"]?.toString() ?: ""

                // 내 메시지 무시
                if (myId.isNotEmpty() && senderId == myId) return

                val intent = Intent(ACTION_CHAT_MESSAGE).apply {
                    putExtra(EXTRA_MSG_ID, msgId)
                    putExtra(EXTRA_MSG_CONTENT, content)
                    putExtra(EXTRA_MSG_SENDER_ID, senderId)
                    putExtra(EXTRA_MSG_CONV_ID, convId)
                    putExtra(EXTRA_MSG_CREATED_AT, createdAt)
                }
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

                if (!isChatActivityVisible) playChatSound()
            }
        } catch (e: Exception) { Log.e("ChatWsService", "Parse Error: ${e.message}") }
    }

    private fun playChatSound() {
        try {
            MediaPlayer.create(this, R.raw.chat)?.apply {
                setOnCompletionListener { release() }
                start()
            }
        } catch (e: Exception) { }
    }
}