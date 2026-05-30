package com.example.routeon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

data class ChatMessage(
    val id: String = "",
    val text: String,
    val isSent: Boolean,
    val time: String,
    val timestampMs: Long
)

class ChatActivity : BaseActivity() {

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var etMessage: EditText
    private var conversationId: String? = null

    // 진행 중인 코루틴을 추적하여 Activity 종료 시 취소
    private val activityScope = CoroutineScope(Dispatchers.Main + Job())

    // ── WS 메시지 수신 BroadcastReceiver ────────────────────────────────────
    private val chatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val content   = intent.getStringExtra(ChatWebSocketService.EXTRA_MSG_CONTENT)    ?: ""
            val msgId     = intent.getStringExtra(ChatWebSocketService.EXTRA_MSG_ID)          ?: ""
            val convId    = intent.getStringExtra(ChatWebSocketService.EXTRA_MSG_CONV_ID)     ?: ""
            val createdAt = intent.getStringExtra(ChatWebSocketService.EXTRA_MSG_CREATED_AT)  ?: ""

            // 현재 열린 대화방 메시지만 처리 (다른 대화방 메시지 무시)
            val myConvId = conversationId
            if (myConvId != null && convId.isNotEmpty() && convId != myConvId) {
                Log.d("ChatActivity", "다른 대화방 메시지 무시: recv=$convId mine=$myConvId")
                return
            }

            if (content.isNotEmpty()) {
                // 중복 방지: 같은 id 메시지가 이미 있으면 추가하지 않음
                if (messages.none { it.id == msgId && msgId.isNotEmpty() }) {
                    addReceivedMessageUI(content, msgId, isoToHHmm(createdAt))
                    myConvId?.let { markRead(it, msgId) }
                }
            } else {
                // content 파싱 실패 시 최신 메시지 한 건 직접 조회
                myConvId?.let { fetchLatestMessageSilent(it) }
            }
        }
    }

    // receiver가 현재 등록되어 있는지 추적 (중복 등록/해제 방지)
    private var isReceiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        setupUI()

        // WS 서비스가 살아있는지 확인하고 재연결
        ChatWebSocketService.start(this)

        // 현재 로그인 user_id와 캐시된 user_id 비교 → 다르면 캐시 무효화
        val prefs         = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        val chatPrefs     = getSharedPreferences("ChatPrefs", Context.MODE_PRIVATE)
        val currentUserId = prefs.getString("user_id", null)
        val cachedUserId  = chatPrefs.getString("cached_user_id", null)
        val cachedConvId  = chatPrefs.getString("chat_conversation_id", null)

        if (!cachedConvId.isNullOrEmpty() && cachedUserId == currentUserId) {
            // 동일 사용자 → 캐시 재사용
            conversationId = cachedConvId
            fetchMessageHistory(cachedConvId)
        } else {
            // 다른 사용자이거나 캐시 없음 → 캐시 초기화 후 새로 조회
            chatPrefs.edit()
                .remove("chat_conversation_id")
                .putString("cached_user_id", currentUserId)
                .apply()
            initConversation()
        }
    }

    // ── 채팅 파트너(관리자) 조회 → 대화방 생성/조회 ──────────────────────────
    private fun initConversation() {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: run {
            Toast.makeText(this, "로그인 정보가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        // 타임아웃을 넉넉히 설정 (내비 SDK가 네트워크를 사용 중일 수 있음)
        activityScope.launch(Dispatchers.IO) {
            try {
                // STEP 1: 채팅 가능한 파트너(관리자) 목록 조회
                val partnersConn = (URL("${Constants.BASE_URL}/chat/partners")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $token")
                    connectTimeout = 15_000   // 내비 중 네트워크 부하를 고려해 여유 있게
                    readTimeout    = 15_000
                }

                val partnersCode = partnersConn.responseCode
                if (partnersCode != 200) {
                    Log.e("ChatActivity", "❌ /chat/partners 실패: HTTP $partnersCode")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@ChatActivity,
                            "관리자 정보를 불러올 수 없습니다. (HTTP $partnersCode)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                val partnersArr = JSONArray(partnersConn.inputStream.bufferedReader().readText())
                if (partnersArr.length() == 0) {
                    Log.w("ChatActivity", "⚠️ 채팅 가능한 파트너 없음")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ChatActivity, "채팅 가능한 관리자가 없습니다.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // 첫 번째 파트너(관리자) 사용
                val partnerId = partnersArr.getJSONObject(0).optString("id", "")
                if (partnerId.isEmpty()) {
                    Log.e("ChatActivity", "❌ 파트너 ID 없음")
                    return@launch
                }
                Log.d("ChatActivity", "✅ 파트너 ID: $partnerId")

                // STEP 2: 대화방 생성 또는 기존 대화방 조회
                val convConn = (URL("${Constants.BASE_URL}/chat/conversations")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 15_000
                    readTimeout    = 15_000
                }
                OutputStreamWriter(convConn.outputStream).use {
                    it.write(JSONObject().apply { put("partner_id", partnerId) }.toString())
                }

                val convCode = convConn.responseCode
                if (convCode !in 200..201) {
                    Log.e("ChatActivity", "❌ /chat/conversations 실패: HTTP $convCode")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@ChatActivity,
                            "대화방을 열 수 없습니다. (HTTP $convCode)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                val convJson = JSONObject(convConn.inputStream.bufferedReader().readText())
                val convId   = convJson.optString("id", "")
                if (convId.isEmpty()) {
                    Log.e("ChatActivity", "❌ conversation_id 없음")
                    return@launch
                }
                Log.d("ChatActivity", "✅ conversation_id: $convId")

                // STEP 3: 캐시 저장 (user_id 함께 저장 — 로그아웃 후 재사용 방지)
                val currentUserId = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
                    .getString("user_id", null)
                getSharedPreferences("ChatPrefs", Context.MODE_PRIVATE).edit()
                    .putString("chat_conversation_id", convId)
                    .putString("cached_user_id", currentUserId)
                    .apply()

                withContext(Dispatchers.Main) {
                    conversationId = convId
                    fetchMessageHistory(convId)
                }

            } catch (e: Exception) {
                Log.e("ChatActivity", "💥 initConversation 오류: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ChatActivity, "채팅 초기화 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupUI() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        recycler = findViewById(R.id.recyclerChat)
        adapter  = ChatAdapter(messages)
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recycler.adapter = adapter

        etMessage = findViewById(R.id.etMessage)

        // IME 조합 중(한글 입력 중) Enter 키로 전송되지 않도록 방지
        etMessage.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                event.action == android.view.KeyEvent.ACTION_DOWN) {
                // 소프트 키보드의 Enter는 별도 처리 (sendMessage에서 처리)
                false
            } else false
        }

        findViewById<FloatingActionButton>(R.id.btnSend).setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                etMessage.text.clear()
                sendMessage(text)
            }
        }
    }

    private fun addReceivedMessageUI(text: String, id: String, time: String) {
        // 이미 UI 스레드이거나 백그라운드에서 호출 시 모두 안전하게 처리
        runOnUiThread {
            messages.add(ChatMessage(id, text, false, time, System.currentTimeMillis()))
            adapter.notifyItemInserted(messages.size - 1)
            recycler.scrollToPosition(messages.size - 1)
        }
    }

    /** WS 수신 시 content 파싱 실패 등 예외 상황에서 최신 메시지 1건 조회 */
    private fun fetchLatestMessageSilent(convId: String) {
        val t = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: return
        activityScope.launch(Dispatchers.IO) {
            try {
                val conn = (URL("${Constants.BASE_URL}/chat/conversations/$convId/messages?limit=1")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $t")
                    connectTimeout = 10_000
                    readTimeout    = 10_000
                }
                if (conn.responseCode == 200) {
                    val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                    if (arr.length() > 0) {
                        val obj       = arr.getJSONObject(0)
                        val id        = obj.optString("id", "")
                        val content   = obj.optString("content", "")
                        val senderId  = obj.optString("sender_id", "")
                        val createdAt = obj.optString("created_at", "")
                        val myId = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
                            .getString("user_id", "") ?: ""
                        withContext(Dispatchers.Main) {
                            if (content.isNotEmpty() &&
                                messages.none { it.id == id && id.isNotEmpty() } &&
                                senderId != myId
                            ) {
                                addReceivedMessageUI(content, id, isoToHHmm(createdAt))
                                markRead(convId, id)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatActivity", "fetchLatestMessageSilent 오류: ${e.message}")
            }
        }
    }

    /** 최근 50건 메시지 히스토리 로드 (오름차순 — 서버 응답 그대로) */
    private fun fetchMessageHistory(convId: String) {
        val t = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: return
        activityScope.launch(Dispatchers.IO) {
            try {
                val conn = (URL("${Constants.BASE_URL}/chat/conversations/$convId/messages?limit=50")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $t")
                    connectTimeout = 15_000
                    readTimeout    = 15_000
                }
                val code = conn.responseCode
                when (code) {
                    200 -> {
                        val arr  = JSONArray(conn.inputStream.bufferedReader().readText())
                        val myId = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
                            .getString("user_id", "") ?: ""
                        val history = (0 until arr.length()).map { i ->
                            val obj = arr.getJSONObject(i)
                            ChatMessage(
                                id          = obj.optString("id", ""),
                                text        = obj.optString("content", ""),
                                isSent      = obj.optString("sender_id", "") == myId,
                                time        = isoToHHmm(obj.optString("created_at", "")),
                                timestampMs = 0L
                            )
                        }
                        withContext(Dispatchers.Main) {
                            messages.clear()
                            messages.addAll(history)
                            adapter.notifyDataSetChanged()
                            if (messages.isNotEmpty())
                                recycler.scrollToPosition(messages.size - 1)
                        }
                    }
                    403, 404 -> {
                        // 캐시된 conversation_id가 유효하지 않으면 캐시 지우고 재초기화
                        Log.w("ChatActivity", "⚠️ conversation_id 유효하지 않음 (HTTP $code) — 재초기화")
                        getSharedPreferences("ChatPrefs", Context.MODE_PRIVATE).edit()
                            .remove("chat_conversation_id").apply()
                        withContext(Dispatchers.Main) {
                            conversationId = null
                            initConversation()
                        }
                    }
                    else -> Log.e("ChatActivity", "❌ fetchMessageHistory HTTP $code")
                }
            } catch (e: Exception) {
                Log.e("ChatActivity", "fetchMessageHistory 오류: ${e.message}")
            }
        }
    }

    private fun sendMessage(text: String) {
        val cid = conversationId ?: run {
            Toast.makeText(this, "대화방 연결 중입니다. 잠시 후 다시 시도해주세요.", Toast.LENGTH_SHORT).show()
            initConversation()
            return
        }
        val t = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: return

        // 낙관적 UI: 전송 즉시 내 말풍선 표시
        val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        messages.add(ChatMessage("", text, true, now, System.currentTimeMillis()))
        adapter.notifyItemInserted(messages.size - 1)
        recycler.scrollToPosition(messages.size - 1)

        // 타임아웃을 넉넉히 설정 (내비 중 네트워크 경합 방지)
        activityScope.launch(Dispatchers.IO) {
            try {
                val conn = (URL("${Constants.BASE_URL}/chat/conversations/$cid/messages")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $t")
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 15_000
                    readTimeout    = 15_000
                }
                OutputStreamWriter(conn.outputStream).use {
                    it.write(JSONObject().apply { put("content", text) }.toString())
                }
                val code = conn.responseCode
                Log.d("ChatActivity", "📤 메시지 전송: HTTP $code")
                if (code !in 200..201) {
                    Log.e("ChatActivity", "❌ 메시지 전송 실패: HTTP $code")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ChatActivity, "메시지 전송에 실패했습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatActivity", "❌ 메시지 전송 오류: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ChatActivity, "네트워크 오류로 전송 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun markRead(cid: String, mid: String) {
        if (mid.isEmpty()) return
        val t = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: return
        activityScope.launch(Dispatchers.IO) {
            try {
                val conn = (URL("${Constants.BASE_URL}/chat/conversations/$cid/read")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $t")
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 5_000
                    readTimeout    = 5_000
                }
                OutputStreamWriter(conn.outputStream).use {
                    it.write(JSONObject().apply { put("last_read_message_id", mid) }.toString())
                }
                conn.responseCode
            } catch (e: Exception) {
                Log.e("ChatActivity", "markRead 오류: ${e.message}")
            }
        }
    }

    /**
     * ISO 8601 타임스탬프 → HH:mm 변환
     * 서버가 밀리초/마이크로초 포함 형식으로 반환하므로 소수점 이하 제거 후 파싱
     * 예) "2026-05-20T02:42:45.438962" → "02:42"
     */
    private fun isoToHHmm(iso: String): String {
        if (iso.isEmpty()) return ""
        return try {
            val normalized = iso.substringBefore('.')
                .takeIf { it.length >= 19 } ?: iso.take(19)
            val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).parse(normalized)
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(date!!)
        } catch (e: Exception) {
            Log.w("ChatActivity", "날짜 파싱 실패: $iso — ${e.message}")
            ""
        }
    }

    /**
     * chatReceiver를 안전하게 등록한다.
     * 이미 등록된 경우 중복 등록하지 않는다.
     *
     * onResume뿐만 아니라 onStart에서도 등록하여
     * 내비게이션 중 화면이 부분적으로 가려져도 메시지를 수신할 수 있게 한다.
     */
    private fun registerChatReceiver() {
        if (isReceiverRegistered) return
        LocalBroadcastManager.getInstance(this).registerReceiver(
            chatReceiver, IntentFilter(ChatWebSocketService.ACTION_CHAT_MESSAGE)
        )
        isReceiverRegistered = true
        Log.d("ChatActivity", "✅ chatReceiver 등록")
    }

    private fun unregisterChatReceiver() {
        if (!isReceiverRegistered) return
        LocalBroadcastManager.getInstance(this).unregisterReceiver(chatReceiver)
        isReceiverRegistered = false
        Log.d("ChatActivity", "🚫 chatReceiver 해제")
    }

    // ── 생명주기: onStart/onStop 기준으로 등록/해제 ─────────────────────────
    // onResume/onPause 대신 onStart/onStop을 사용하는 이유:
    // 내비게이션 View나 다이얼로그가 위에 올라와 ChatActivity가 onPause가 되어도
    // onStop은 호출되지 않으므로 chatReceiver가 살아있게 된다.
    override fun onStart() {
        super.onStart()
        ChatWebSocketService.isChatActivityVisible = true
        registerChatReceiver()
    }

    override fun onStop() {
        super.onStop()
        ChatWebSocketService.isChatActivityVisible = false
        unregisterChatReceiver()
    }

    // onResume/onPause에서는 isChatActivityVisible만 갱신 (receiver는 onStart/onStop에서 관리)
    override fun onResume() {
        super.onResume()
        ChatWebSocketService.isChatActivityVisible = true
    }

    override fun onPause() {
        super.onPause()
        // 완전히 화면을 떠나는 것(onStop)이 아니라면 isChatActivityVisible을 false로 바꾸지 않는다.
        // onStop에서 처리하므로 여기서는 아무것도 하지 않는다.
        // (BaseActivity.onPause에서 globalChatReceiver는 여전히 unregister되지만,
        //  ChatActivity 자체의 chatReceiver는 onStop까지 살아있다.)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 혹시 모를 경우를 대비해 Job 취소 (메모리 누수 방지)
        activityScope.coroutineContext[Job.Key]?.cancel()
        // receiver가 아직 등록된 경우 해제 (onStop이 호출 안 된 엣지 케이스)
        unregisterChatReceiver()
    }

    inner class ChatAdapter(private val list: List<ChatMessage>) :
        RecyclerView.Adapter<ChatAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val lSent: LinearLayout = v.findViewById(R.id.layoutSent)
            val lRecv: LinearLayout = v.findViewById(R.id.layoutReceived)
            val tSent: TextView     = v.findViewById(R.id.tvMessageSent)
            val tRecv: TextView     = v.findViewById(R.id.tvMessageReceived)
            val tmSent: TextView    = v.findViewById(R.id.tvTimeSent)
            val tmRecv: TextView    = v.findViewById(R.id.tvTimeReceived)
        }

        override fun onCreateViewHolder(p: ViewGroup, vt: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_chat_message, p, false))

        override fun getItemCount() = list.size

        override fun onBindViewHolder(h: VH, p: Int) {
            val m = list[p]
            if (m.isSent) {
                h.lSent.visibility = View.VISIBLE
                h.lRecv.visibility = View.GONE
                h.tSent.text  = m.text
                h.tmSent.text = m.time
            } else {
                h.lSent.visibility = View.GONE
                h.lRecv.visibility = View.VISIBLE
                h.tRecv.text  = m.text
                h.tmRecv.text = m.time
            }
        }
    }
}
