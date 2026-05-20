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
        // 서버(uvicorn --ws-ping-interval 20)가 PING을 보내고 OkHttp가 자동으로 PONG 응답
        // 클라이언트 pingInterval 제거 → 양쪽 동시 PING 충돌 방지
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private var reconnectDelayMs = 3_000L
    private var isReconnecting = false

    companion object {
        const val ACTION_CHAT_MESSAGE  = "com.example.routeon.CHAT_MESSAGE"
        const val EXTRA_MSG_ID         = "msg_id"
        const val EXTRA_MSG_CONTENT    = "msg_content"
        const val EXTRA_MSG_SENDER_ID  = "msg_sender_id"
        const val EXTRA_MSG_CONV_ID    = "msg_conv_id"
        const val EXTRA_MSG_CREATED_AT = "msg_created_at"

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
        // ★ Fix: all["access_token"] 대신 getString 사용 — 타입 안전
        val token = prefs.getString("access_token", null)

        if (token.isNullOrEmpty()) {
            Log.w("ChatWsService", "토큰 없음 — WS 연결 스킵")
            return
        }

        val wsUrl = "${Constants.WS_URL}/ws/chat?token=$token"
        Log.d("ChatWsService", "WS 연결 시도: $wsUrl")

        val request = Request.Builder().url(wsUrl).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectDelayMs = 3_000L
                isReconnecting = false
                Log.d("ChatWsService", "✅ WebSocket 연결 성공")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleEvent(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("ChatWsService", "❌ WS 연결 실패: ${t.message}")
                this@ChatWebSocketService.webSocket = null
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w("ChatWsService", "WS 닫힘: code=$code reason=$reason")
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
            // ★ Fix: json.optString("event","") 폴백 제거 — 서버 스펙상 항상 "type" 필드 사용
            val eventType = json.optString("type", "")

            when (eventType) {

                // ── 서버 heartbeat → pong 즉시 응답 ────────────────────────────
                "ping" -> {
                    webSocket?.send("""{"type":"pong"}""")
                }

                // ── 연결 확인 이벤트 (무시) ──────────────────────────────────────
                "chat.ready" -> {
                    Log.d("ChatWsService", "✅ 채팅 WS 준비 완료 (user_id=${json.optString("user_id")})")
                }

                // ── 상대방 읽음 처리 이벤트 (무시) ──────────────────────────────
                "chat.read" -> {
                    Log.d("ChatWsService", "📖 상대방 읽음 처리 수신")
                }

                // ── 새 메시지 수신 ───────────────────────────────────────────────
                "chat.message" -> {
                    // 서버 스펙: 최상위 "message" 키 안에 실제 메시지 객체
                    val msgObj = json.optJSONObject("message") ?: run {
                        Log.w("ChatWsService", "chat.message 이벤트에 message 객체 없음 — raw: $raw")
                        return
                    }

                    val msgId     = msgObj.optString("id", "")
                    val content   = msgObj.optString("content", "").trim()
                    val senderId  = msgObj.optString("sender_id", "")
                    // conversation_id: 내부 메시지 객체 우선, 없으면 최상위에서 가져옴
                    val convId    = msgObj.optString("conversation_id", "")
                        .ifEmpty { json.optString("conversation_id", "") }
                    val createdAt = msgObj.optString("created_at", "")

                    if (content.isEmpty()) {
                        Log.w("ChatWsService", "빈 content — 무시")
                        return
                    }

                    // ★ Fix: all["user_id"] 대신 getString 사용
                    val myId = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
                        .getString("user_id", "") ?: ""

                    // 내가 보낸 메시지 에코 방지 (서버는 상대방에게만 전송하지만 방어적 처리)
                    if (myId.isNotEmpty() && senderId == myId) {
                        Log.d("ChatWsService", "내 메시지 에코 — 무시")
                        return
                    }

                    Log.d("ChatWsService", "💬 새 메시지 수신: [${content.take(20)}…] from=$senderId conv=$convId")

                    LocalBroadcastManager.getInstance(this).sendBroadcast(
                        Intent(ACTION_CHAT_MESSAGE).apply {
                            putExtra(EXTRA_MSG_ID, msgId)
                            putExtra(EXTRA_MSG_CONTENT, content)
                            putExtra(EXTRA_MSG_SENDER_ID, senderId)
                            putExtra(EXTRA_MSG_CONV_ID, convId)
                            putExtra(EXTRA_MSG_CREATED_AT, createdAt)
                        }
                    )

                    if (!isChatActivityVisible) playChatSound()
                }

                else -> Log.d("ChatWsService", "알 수 없는 이벤트 타입: '$eventType'")
            }
        } catch (e: Exception) {
            Log.e("ChatWsService", "이벤트 파싱 오류: ${e.message} — raw: $raw")
        }
    }

    private fun playChatSound() {
        try {
            MediaPlayer.create(this, R.raw.chat)?.apply {
                setOnCompletionListener { release() }
                start()
            }
        } catch (_: Exception) { }
    }
}
