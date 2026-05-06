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

/**
 * 앱 전체에서 채팅 WebSocket 연결을 유지하는 백그라운드 서비스.
 *
 * ─ ChatActivity가 열려있을 때 : ACTION_CHAT_MESSAGE 브로드캐스트만 발송 (소리 없음)
 * ─ 다른 화면일 때              : 브로드캐스트 발송 + chat.mp3 재생
 *
 * 시작: LoginActivity 로그인 성공 후 startService()
 * 종료: 로그아웃 시 stopService()
 */
class ChatWebSocketService : Service() {

    private var webSocket: WebSocket? = null
    private val httpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)   // 연결 유지용 ping
        .build()

    private var reconnectDelayMs = 3_000L
    private var isReconnecting = false

    companion object {
        const val ACTION_CHAT_MESSAGE = "com.example.routeon.CHAT_MESSAGE"

        // Intent extras
        const val EXTRA_MSG_ID          = "msg_id"
        const val EXTRA_MSG_CONTENT     = "msg_content"
        const val EXTRA_MSG_SENDER_ID   = "msg_sender_id"
        const val EXTRA_MSG_CONV_ID     = "msg_conv_id"
        const val EXTRA_MSG_CREATED_AT  = "msg_created_at"

        // ChatActivity가 포그라운드에 있는지 추적 (서비스와 액티비티가 같은 프로세스)
        @Volatile var isChatActivityVisible = false

        fun start(context: Context) =
            context.startService(Intent(context, ChatWebSocketService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, ChatWebSocketService::class.java))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 생명주기
    // ─────────────────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (webSocket == null) connect()
        return START_STICKY   // 시스템이 종료해도 자동 재시작
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.cancel()
        webSocket = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebSocket 연결
    // ─────────────────────────────────────────────────────────────────────────

    private fun connect() {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null)

        if (token.isNullOrEmpty()) {
            Log.w("ChatWsService", "토큰 없음 — 연결 안 함")
            return
        }

        val request = Request.Builder()
            .url("${Constants.WS_URL}/ws/chat?token=$token")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("ChatWsService", "WebSocket 연결됨")
                reconnectDelayMs = 3_000L
                isReconnecting   = false
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleEvent(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("ChatWsService", "WS 오류: ${t.message}")
                this@ChatWebSocketService.webSocket = null
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("ChatWsService", "WS 닫힘: $reason")
                this@ChatWebSocketService.webSocket = null
                if (code != 1000) scheduleReconnect()  // 정상 종료가 아니면 재연결
            }
        })
    }

    private fun scheduleReconnect() {
        if (isReconnecting) return
        isReconnecting = true
        Log.d("ChatWsService", "${reconnectDelayMs}ms 후 재연결")
        android.os.Handler(mainLooper).postDelayed({
            isReconnecting = false
            if (webSocket == null) connect()
        }, reconnectDelayMs)
        // 지수 백오프 (최대 30초)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30_000L)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 이벤트 처리
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleEvent(raw: String) {
        try {
            val json  = JSONObject(raw)
            val event = json.optString("type", json.optString("event", ""))

            when (event) {
                "chat.ready" -> Log.d("ChatWsService", "chat.ready 수신")

                "chat.message" -> {
                    val data      = json.optJSONObject("data") ?: json
                    val senderId  = data.optString("sender_id", "")
                    val content   = data.optString("content", "").trim()
                    val msgId     = data.optString("id", "")
                    val convId    = data.optString("conversation_id", "")
                    val createdAt = data.optString("created_at", "")

                    if (content.isEmpty()) return

                    // 내가 보낸 메시지는 무시
                    val myId = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
                        .getString("user_id", "")
                    if (senderId == myId) return

                    // LocalBroadcast → ChatActivity / MainActivity 등 수신
                    val intent = Intent(ACTION_CHAT_MESSAGE).apply {
                        putExtra(EXTRA_MSG_ID,         msgId)
                        putExtra(EXTRA_MSG_CONTENT,    content)
                        putExtra(EXTRA_MSG_SENDER_ID,  senderId)
                        putExtra(EXTRA_MSG_CONV_ID,    convId)
                        putExtra(EXTRA_MSG_CREATED_AT, createdAt)
                    }
                    LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

                    // ChatActivity가 보이지 않을 때만 알림음 재생
                    if (!isChatActivityVisible) playChatSound()
                }

                "chat.read" -> { /* 필요 시 읽음 UI 업데이트 */ }
            }
        } catch (e: Exception) {
            Log.e("ChatWsService", "이벤트 파싱 오류: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 알림음
    // ─────────────────────────────────────────────────────────────────────────

    private fun playChatSound() {
        try {
            MediaPlayer.create(this, R.raw.chat)?.apply {
                setOnCompletionListener { release() }
                start()
            }
        } catch (e: Exception) {
            Log.e("ChatWsService", "알림음 재생 오류: ${e.message}")
        }
    }
}
