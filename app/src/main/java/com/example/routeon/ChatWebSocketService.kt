package com.example.routeon

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
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

    // ── AudioFocus: 내비게이션 SDK가 포커스를 점유해도 알림음을 재생하기 위해 사용 ──
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null  // API 26+
    private var focusGranted = false

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

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (webSocket == null) connect()
        // START_STICKY: 시스템이 서비스를 강제 종료해도 자동 재시작
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.cancel()
        webSocket = null
        releaseAudioFocus()
    }

    private fun connect() {
        val prefs = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
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
        Log.d("ChatWsService", "📩 수신: $raw")
        try {
            val json = JSONObject(raw)
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
                    val msgObj = json.optJSONObject("message") ?: run {
                        Log.w("ChatWsService", "chat.message 이벤트에 message 객체 없음 — raw: $raw")
                        return
                    }

                    val msgId     = msgObj.optString("id", "")
                    val content   = msgObj.optString("content", "").trim()
                    val senderId  = msgObj.optString("sender_id", "")
                    val convId    = msgObj.optString("conversation_id", "")
                        .ifEmpty { json.optString("conversation_id", "") }
                    val createdAt = msgObj.optString("created_at", "")

                    if (content.isEmpty()) {
                        Log.w("ChatWsService", "빈 content — 무시")
                        return
                    }

                    val myId = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
                        .getString("user_id", "") ?: ""

                    // 내가 보낸 메시지 에코 방지
                    if (myId.isNotEmpty() && senderId == myId) {
                        Log.d("ChatWsService", "내 메시지 에코 — 무시")
                        return
                    }

                    Log.d("ChatWsService", "💬 새 메시지 수신: [${content.take(20)}…] from=$senderId conv=$convId")

                    // LocalBroadcast → ChatActivity 또는 BaseActivity(globalChatReceiver)가 수신
                    LocalBroadcastManager.getInstance(this).sendBroadcast(
                        Intent(ACTION_CHAT_MESSAGE).apply {
                            putExtra(EXTRA_MSG_ID, msgId)
                            putExtra(EXTRA_MSG_CONTENT, content)
                            putExtra(EXTRA_MSG_SENDER_ID, senderId)
                            putExtra(EXTRA_MSG_CONV_ID, convId)
                            putExtra(EXTRA_MSG_CREATED_AT, createdAt)
                        }
                    )

                    // ChatActivity가 화면에 없을 때만 알림음 재생
                    if (!isChatActivityVisible) playChatSound()
                }

                else -> Log.d("ChatWsService", "알 수 없는 이벤트 타입: '$eventType'")
            }
        } catch (e: Exception) {
            Log.e("ChatWsService", "이벤트 파싱 오류: ${e.message} — raw: $raw")
        }
    }

    /**
     * 채팅 알림음 재생.
     *
     * 카카오 내비게이션 SDK(KNSDK)가 오디오 포커스를 점유한 상태에서도
     * AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK 요청으로 짧게 덕킹(ducking)하여 알림음을 재생한다.
     *
     * ▸ AudioFocusRequest(API 26+) / requestAudioFocus(deprecated, API 25 이하) 분기 처리
     * ▸ 재생 완료 후 즉시 포커스 반환 → 내비 음성 안내 복구
     */
    private fun playChatSound() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // ── API 26+ ─────────────────────────────────────────────────────
                val focusChangeListener = AudioManager.OnAudioFocusChangeListener { }
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()
                audioFocusRequest = req

                val result = am.requestAudioFocus(req)
                focusGranted = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                Log.d("ChatWsService", "오디오 포커스 요청: ${if (focusGranted) "✅ 허용" else "⚠️ 지연/거절 (덕킹으로 재생 시도)"}")
                // 포커스 거절이어도 덕킹 전략이므로 재생 시도 진행

            } else {
                // ── API 25 이하 (deprecated API) ───────────────────────────────
                @Suppress("DEPRECATION")
                val result = am.requestAudioFocus(
                    { },
                    AudioManager.STREAM_NOTIFICATION,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                focusGranted = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            }

            // MediaPlayer를 STREAM_NOTIFICATION 스트림으로 명시 설정
            val mp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                MediaPlayer().apply {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    setAudioAttributes(attrs)
                    val afd = resources.openRawResourceFd(R.raw.chat)
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                    prepare()
                }
            } else {
                @Suppress("DEPRECATION")
                MediaPlayer.create(this, R.raw.chat)
            }

            mp?.apply {
                setOnCompletionListener { mp2 ->
                    mp2.release()
                    releaseAudioFocus()  // 재생 완료 후 즉시 포커스 반환
                    Log.d("ChatWsService", "🔔 알림음 재생 완료 — 오디오 포커스 반환")
                }
                setOnErrorListener { mp2, what, extra ->
                    Log.e("ChatWsService", "MediaPlayer 에러: what=$what extra=$extra")
                    mp2.release()
                    releaseAudioFocus()
                    true
                }
                start()
                Log.d("ChatWsService", "🔔 알림음 재생 시작")
            } ?: run {
                Log.w("ChatWsService", "⚠️ MediaPlayer 생성 실패 — 오디오 포커스 반환")
                releaseAudioFocus()
            }

        } catch (e: Exception) {
            Log.e("ChatWsService", "playChatSound 오류: ${e.message}")
            releaseAudioFocus()
        }
    }

    private fun releaseAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
            focusGranted = false
        } catch (e: Exception) {
            Log.e("ChatWsService", "releaseAudioFocus 오류: ${e.message}")
        }
    }
}
