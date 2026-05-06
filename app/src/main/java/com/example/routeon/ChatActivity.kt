package com.example.routeon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowInsetsControllerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =============================================================================
// 데이터 클래스
// =============================================================================

data class ChatMessage(
    val id: String = "",
    val text: String,
    val isSent: Boolean,
    val time: String = nowHHmm(),
    val timestampMs: Long = System.currentTimeMillis()
)

private fun nowHHmm() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

// =============================================================================
// ChatActivity
// =============================================================================

class ChatActivity : BaseActivity() {

    // ── UI ────────────────────────────────────────────────────────────────────
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: FloatingActionButton

    // ── 상태 ──────────────────────────────────────────────────────────────────
    private var conversationId: String? = null
    private var partnerId: String? = null
    private var lastReadMessageId: String? = null
    private var oldestLoadedMessageId: String? = null

    // ── SharedPreferences ────────────────────────────────────────────────────
    private val mainPrefs: SharedPreferences by lazy {
        getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
    }
    private val chatPrefs: SharedPreferences by lazy {
        getSharedPreferences("ChatPrefs", Context.MODE_PRIVATE)
    }
    private val token    get() = mainPrefs.getString("access_token", null)
    private val myUserId get() = mainPrefs.getString("user_id", "")

    private val isNightMode: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    // ── LocalBroadcast 수신기 ─────────────────────────────────────────────────
    private val chatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val content   = intent.getStringExtra(ChatWebSocketService.EXTRA_MSG_CONTENT) ?: return
            val msgId     = intent.getStringExtra(ChatWebSocketService.EXTRA_MSG_ID) ?: ""
            val convId    = intent.getStringExtra(ChatWebSocketService.EXTRA_MSG_CONV_ID) ?: ""
            val createdAt = intent.getStringExtra(ChatWebSocketService.EXTRA_MSG_CREATED_AT) ?: ""

            // 현재 열린 대화방 메시지만 표시
            if (convId.isNotEmpty() && convId != conversationId) return

            val timeStr = isoToHHmm(createdAt)
            addReceivedMessageUI(content, msgId, timeStr)
            chatPrefs.edit().putLong(PREF_LAST_MSG_TIME, System.currentTimeMillis()).apply()

            // 읽음 처리
            val t = token; val cid = conversationId
            if (t != null && cid != null && msgId.isNotEmpty()) markRead(cid, msgId, t)
        }
    }

    // ── 상수 ──────────────────────────────────────────────────────────────────
    companion object {
        private const val TAG = "ChatActivity"
        private const val PREF_WELCOME_SHOWN = "chat_welcome_shown"
        private const val PREF_CONV_ID       = "chat_conversation_id"
        private const val PREF_PARTNER_ID    = "chat_partner_id"
        private const val PREF_LAST_MSG_TIME = "chat_last_message_time_ms"
        private const val MSG_PAGE_LIMIT     = 50
        private const val AUTO_REPLY_MS      = 24L * 60 * 60 * 1000
    }

    // =========================================================================
    // 생명주기
    // =========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        applySystemBarsColor()
        setupToolbar()
        setupRecycler()
        setupInput()

        val cachedConvId  = chatPrefs.getString(PREF_CONV_ID, null)
        val cachedPartner = chatPrefs.getString(PREF_PARTNER_ID, null)

        if (cachedConvId != null) {
            conversationId = cachedConvId
            partnerId      = cachedPartner
            fetchMessageHistory(cachedConvId, reset = true)
        } else {
            fetchPartnersAndOpenConversation()
        }
    }

    override fun onResume() {
        super.onResume()
        applySystemBarsColor()
        // 포그라운드 진입 → 서비스에게 알림음 억제 요청
        ChatWebSocketService.isChatActivityVisible = true
        // LocalBroadcast 수신 등록
        LocalBroadcastManager.getInstance(this).registerReceiver(
            chatReceiver,
            IntentFilter(ChatWebSocketService.ACTION_CHAT_MESSAGE)
        )
    }

    override fun onPause() {
        super.onPause()
        // 백그라운드 진입 → 알림음 허용
        ChatWebSocketService.isChatActivityVisible = false
        LocalBroadcastManager.getInstance(this).unregisterReceiver(chatReceiver)
    }

    // =========================================================================
    // UI 초기화
    // =========================================================================

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecycler() {
        recycler = findViewById(R.id.recyclerChat)
        adapter  = ChatAdapter(messages)
        recycler.layoutManager = LinearLayoutManager(this).also { it.stackFromEnd = true }
        recycler.adapter = adapter
    }

    private fun setupInput() {
        etMessage = findViewById(R.id.etMessage)
        btnSend   = findViewById(R.id.btnSend)
        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            if (text.length > 2000) {
                etMessage.error = "2,000자 이하로 입력해 주세요."
                return@setOnClickListener
            }
            etMessage.text.clear()
            sendMessage(text)
        }
    }

    private fun applySystemBarsColor() {
        val color = if (isNightMode) Color.BLACK else Color.WHITE
        window.statusBarColor     = color
        window.navigationBarColor = color
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars     = !isNightMode
            isAppearanceLightNavigationBars = !isNightMode
        }
    }

    // =========================================================================
    // STEP 1 — 파트너 조회 → 대화방 열기
    // GET /chat/partners
    // =========================================================================

    private fun fetchPartnersAndOpenConversation() {
        val t = token ?: run { showWelcomeIfNeeded(); return }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = openGet("${Constants.BASE_URL}/chat/partners", t)
                if (conn.responseCode == 200) {
                    val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                    if (arr.length() > 0) {
                        val pid = arr.getJSONObject(0).optString("id", "")
                        if (pid.isNotEmpty()) {
                            partnerId = pid
                            chatPrefs.edit().putString(PREF_PARTNER_ID, pid).apply()
                            openConversationWith(pid, t)
                            return@launch
                        }
                    }
                }
                withContext(Dispatchers.Main) { showWelcomeIfNeeded() }
            } catch (e: Exception) {
                Log.e(TAG, "fetchPartners: ${e.message}")
                withContext(Dispatchers.Main) { showWelcomeIfNeeded() }
            }
        }
    }

    // =========================================================================
    // STEP 2 — 대화방 생성/조회
    // POST /chat/conversations  body: {"partner_id": "..."}
    // =========================================================================

    private fun openConversationWith(pid: String, t: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("${Constants.BASE_URL}/chat/conversations")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $t")
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use {
                    it.write(JSONObject().apply { put("partner_id", pid) }.toString())
                }
                val code = conn.responseCode
                if (code in 200..201) {
                    val convId = JSONObject(conn.inputStream.bufferedReader().readText())
                        .optString("id", "")
                    if (convId.isNotEmpty()) {
                        conversationId = convId
                        chatPrefs.edit().putString(PREF_CONV_ID, convId).apply()
                        withContext(Dispatchers.Main) { fetchMessageHistory(convId, reset = true) }
                        return@launch
                    }
                }
                withContext(Dispatchers.Main) { showWelcomeIfNeeded() }
            } catch (e: Exception) {
                Log.e(TAG, "openConversation: ${e.message}")
                withContext(Dispatchers.Main) { showWelcomeIfNeeded() }
            }
        }
    }

    // =========================================================================
    // STEP 3 — 메시지 히스토리
    // GET /chat/conversations/{id}/messages?limit=50[&before_message_id=...]
    // =========================================================================

    private fun fetchMessageHistory(convId: String, reset: Boolean) {
        val t = token ?: run { if (reset) showWelcomeIfNeeded(); return }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = buildString {
                    append("${Constants.BASE_URL}/chat/conversations/$convId/messages")
                    append("?limit=$MSG_PAGE_LIMIT")
                    if (!reset && oldestLoadedMessageId != null)
                        append("&before_message_id=$oldestLoadedMessageId")
                }
                val conn = openGet(url, t)
                if (conn.responseCode == 200) {
                    val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                    withContext(Dispatchers.Main) {
                        if (reset) renderHistoryReset(arr) else renderHistoryPrepend(arr)
                    }
                    getLastMessageId(arr)?.let { markRead(convId, it, t) }
                } else {
                    withContext(Dispatchers.Main) { if (reset) showWelcomeIfNeeded() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchHistory: ${e.message}")
                withContext(Dispatchers.Main) { if (reset) showWelcomeIfNeeded() }
            }
        }
    }

    private fun renderHistoryReset(arr: JSONArray) {
        messages.clear()
        var lastMsgMs = 0L
        for (i in 0 until arr.length()) {
            parseMessage(arr.getJSONObject(i))?.let { msg ->
                messages.add(msg)
                if (msg.timestampMs > lastMsgMs) lastMsgMs = msg.timestampMs
            }
        }
        oldestLoadedMessageId = if (arr.length() > 0) arr.getJSONObject(0).optString("id") else null

        if (messages.isEmpty()) showWelcomeIfNeeded()
        else {
            adapter.notifyDataSetChanged()
            recycler.scrollToPosition(messages.size - 1)
            checkAndTriggerAutoReply(lastMsgMs)
        }
    }

    private fun renderHistoryPrepend(arr: JSONArray) {
        if (arr.length() == 0) return
        val prepend = mutableListOf<ChatMessage>()
        for (i in 0 until arr.length()) parseMessage(arr.getJSONObject(i))?.let { prepend.add(it) }
        oldestLoadedMessageId = arr.getJSONObject(0).optString("id")
        messages.addAll(0, prepend)
        adapter.notifyItemRangeInserted(0, prepend.size)
        (recycler.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(prepend.size, 0)
    }

    private fun parseMessage(obj: JSONObject): ChatMessage? {
        val content = obj.optString("content", "").trim()
        if (content.isEmpty()) return null
        return ChatMessage(
            id          = obj.optString("id", ""),
            text        = content,
            isSent      = obj.optString("sender_id", "") == myUserId,
            time        = isoToHHmm(obj.optString("created_at", "")),
            timestampMs = isoToMs(obj.optString("created_at", ""))
        )
    }

    private fun getLastMessageId(arr: JSONArray): String? {
        if (arr.length() == 0) return null
        return arr.getJSONObject(arr.length() - 1).optString("id").ifEmpty { null }
    }

    // =========================================================================
    // 메시지 전송
    // POST /chat/conversations/{id}/messages  body: {"content": "..."}
    // =========================================================================

    private fun sendMessage(text: String) {
        val nowMs = System.currentTimeMillis()
        addSentMessageUI(text, nowMs)
        chatPrefs.edit().putLong(PREF_LAST_MSG_TIME, nowMs).apply()

        val convId = conversationId ?: return
        val t      = token ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("${Constants.BASE_URL}/chat/conversations/$convId/messages")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $t")
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use {
                    it.write(JSONObject().apply { put("content", text) }.toString())
                }
                val code = conn.responseCode
                if (code in 200..201) {
                    val msgId = JSONObject(conn.inputStream.bufferedReader().readText())
                        .optString("id", "")
                    if (msgId.isNotEmpty()) markRead(convId, msgId, t)
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage: ${e.message}")
            }
        }
    }

    // =========================================================================
    // 읽음 처리
    // POST /chat/conversations/{id}/read  body: {"last_read_message_id": "..."}
    // =========================================================================

    private fun markRead(convId: String, lastMsgId: String, t: String) {
        if (lastMsgId == lastReadMessageId) return
        lastReadMessageId = lastMsgId
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("${Constants.BASE_URL}/chat/conversations/$convId/read")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $t")
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use {
                    it.write(JSONObject().apply {
                        put("last_read_message_id", lastMsgId)
                    }.toString())
                }
                conn.responseCode
            } catch (e: Exception) { /* silent */ }
        }
    }

    // =========================================================================
    // 환영 메시지 / 24시간 자동응답
    // =========================================================================

    private fun showWelcomeIfNeeded() {
        if (!chatPrefs.getBoolean(PREF_WELCOME_SHOWN, false)) {
            chatPrefs.edit()
                .putBoolean(PREF_WELCOME_SHOWN, true)
                .putLong(PREF_LAST_MSG_TIME, System.currentTimeMillis())
                .apply()
            addReceivedMessageUI(getString(R.string.chat_welcome))
        }
    }

    private fun checkAndTriggerAutoReply(lastMsgFromServerMs: Long) {
        val cachedMs = chatPrefs.getLong(PREF_LAST_MSG_TIME, 0L)
        val lastMs   = maxOf(lastMsgFromServerMs, cachedMs)
        if (lastMs == 0L) return
        if (System.currentTimeMillis() - lastMs >= AUTO_REPLY_MS) {
            recycler.postDelayed({
                addReceivedMessageUI(getString(R.string.chat_auto_reply))
                chatPrefs.edit().putLong(PREF_LAST_MSG_TIME, System.currentTimeMillis()).apply()
            }, 500)
        }
    }

    // =========================================================================
    // UI 헬퍼
    // =========================================================================

    private fun addSentMessageUI(text: String, nowMs: Long = System.currentTimeMillis()) {
        messages.add(ChatMessage(
            text        = text,
            isSent      = true,
            time        = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nowMs)),
            timestampMs = nowMs
        ))
        adapter.notifyItemInserted(messages.size - 1)
        recycler.scrollToPosition(messages.size - 1)
    }

    private fun addReceivedMessageUI(
        text: String,
        id: String = "",
        timeStr: String = nowHHmm()
    ) {
        messages.add(ChatMessage(
            id          = id,
            text        = text,
            isSent      = false,
            time        = timeStr,
            timestampMs = System.currentTimeMillis()
        ))
        adapter.notifyItemInserted(messages.size - 1)
        recycler.scrollToPosition(messages.size - 1)
    }

    // =========================================================================
    // 네트워크 유틸
    // =========================================================================

    private fun openGet(url: String, t: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $t")
            connectTimeout = 8000; readTimeout = 8000
        }

    // =========================================================================
    // 시간 유틸
    // =========================================================================

    private val ISO_FORMATS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss"
    )

    private fun isoToHHmm(iso: String): String {
        if (iso.isEmpty()) return nowHHmm()
        for (fmt in ISO_FORMATS) {
            try {
                val date = SimpleDateFormat(fmt, Locale.getDefault()).parse(iso) ?: continue
                return SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
            } catch (_: Exception) { }
        }
        return nowHHmm()
    }

    private fun isoToMs(iso: String): Long {
        if (iso.isEmpty()) return 0L
        for (fmt in ISO_FORMATS) {
            try { return SimpleDateFormat(fmt, Locale.getDefault()).parse(iso)?.time ?: continue }
            catch (_: Exception) { }
        }
        return 0L
    }

    // =========================================================================
    // RecyclerView Adapter
    // =========================================================================

    inner class ChatAdapter(private val list: MutableList<ChatMessage>)
        : RecyclerView.Adapter<ChatAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val layoutSent: LinearLayout     = view.findViewById(R.id.layoutSent)
            val layoutReceived: LinearLayout = view.findViewById(R.id.layoutReceived)
            val tvSent: TextView             = view.findViewById(R.id.tvMessageSent)
            val tvReceived: TextView         = view.findViewById(R.id.tvMessageReceived)
            val tvTimeSent: TextView         = view.findViewById(R.id.tvTimeSent)
            val tvTimeReceived: TextView     = view.findViewById(R.id.tvTimeReceived)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_message, parent, false))

        override fun getItemCount() = list.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val msg = list[position]
            if (msg.isSent) {
                holder.layoutSent.visibility     = View.VISIBLE
                holder.layoutReceived.visibility = View.GONE
                holder.tvSent.text               = msg.text
                holder.tvTimeSent.text           = msg.time
            } else {
                holder.layoutSent.visibility     = View.GONE
                holder.layoutReceived.visibility = View.VISIBLE
                holder.tvReceived.text           = msg.text
                holder.tvTimeReceived.text       = msg.time
            }
        }
    }
}
