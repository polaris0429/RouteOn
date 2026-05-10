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
import java.util.*

// =============================================================================
// 데이터 클래스
// =============================================================================
data class ChatMessage(
    val id: String = "",
    val text: String,
    val isSent: Boolean,
    val time: String,
    val timestampMs: Long
)

// =============================================================================
// ChatActivity
// =============================================================================
class ChatActivity : BaseActivity() {

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: FloatingActionButton

    private var conversationId: String? = null
    private var partnerId: String? = null
    private var oldestLoadedMessageId: String? = null
    private var isHistoryLoading = false

    private val mainPrefs: SharedPreferences by lazy { getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE) }
    private val chatPrefs: SharedPreferences by lazy { getSharedPreferences("ChatPrefs", Context.MODE_PRIVATE) }

    private val token    get() = mainPrefs.getString("access_token", null)
    private val myUserId get() = mainPrefs.getString("user_id", "")

    private val isNightMode: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // 실시간 메시지 수신 리시버
    private val chatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val content   = intent.getStringExtra(ChatWebSocketService.EXTRA_MSG_CONTENT) ?: return
            val msgId     = intent.getStringExtra(ChatWebSocketService.EXTRA_MSG_ID) ?: ""
            val convId    = intent.getStringExtra(ChatWebSocketService.EXTRA_MSG_CONV_ID) ?: ""
            val senderId  = intent.getStringExtra(ChatWebSocketService.EXTRA_MSG_SENDER_ID) ?: ""
            val createdAt = intent.getStringExtra(ChatWebSocketService.EXTRA_MSG_CREATED_AT) ?: ""

            // 현재 보고 있는 대화방의 메시지인 경우 UI 업데이트
            if (convId == conversationId) {
                val isMe = senderId == myUserId
                if (!isMe) { // 상대방이 보낸 것이라면
                    addReceivedMessageUI(content, msgId, isoToHHmm(createdAt))
                    markRead(convId, msgId) // 읽음 처리 알림 전송
                }
            }
        }
    }

    companion object {
        private const val TAG = "ChatActivity"
        private const val PREF_CONV_ID = "chat_conversation_id"
        private const val MSG_PAGE_LIMIT = 50
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        setupUI()

        // 1. 기존 대화방 ID가 있는지 확인
        val cachedId = chatPrefs.getString(PREF_CONV_ID, null)
        if (cachedId != null) {
            conversationId = cachedId
            fetchMessageHistory(cachedId, isFirstLoad = true)
        } else {
            // 2. 대화방이 없으면 파트너를 찾아 대화방 생성 (POST /chat/conversations)
            fetchPartnersAndOpenConversation()
        }
    }

    private fun setupUI() {
        applySystemBarsColor()

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        recycler = findViewById(R.id.recyclerChat)
        adapter  = ChatAdapter(messages)
        val lm = LinearLayoutManager(this).apply { stackFromEnd = true }
        recycler.layoutManager = lm
        recycler.adapter = adapter

        // 상단 스크롤 시 과거 내역 더 불러오기
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!recyclerView.canScrollVertically(-1) && !isHistoryLoading && oldestLoadedMessageId != null) {
                    conversationId?.let { fetchMessageHistory(it, isFirstLoad = false) }
                }
            }
        })

        etMessage = findViewById(R.id.etMessage)
        btnSend   = findViewById(R.id.btnSend)
        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                etMessage.text.clear()
                sendMessage(text)
            }
        }
    }

    // STEP 1: 파트너 조회 (기사는 관리자, 관리자는 기사 목록)
    private fun fetchPartnersAndOpenConversation() {
        val t = token ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = openConn("${Constants.BASE_URL}/chat/partners", "GET", t)
                if (conn.responseCode == 200) {
                    val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                    if (arr.length() > 0) {
                        // 가장 첫 번째 파트너와 대화 시도
                        val pid = arr.getJSONObject(0).getString("id")
                        openConversationWith(pid, t)
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "fetchPartners: ${e.message}") }
        }
    }

    // STEP 2: 대화방 생성 또는 기존방 ID 조회
    private fun openConversationWith(pid: String, t: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = openConn("${Constants.BASE_URL}/chat/conversations", "POST", t, true)
                OutputStreamWriter(conn.outputStream).use {
                    it.write(JSONObject().apply { put("partner_id", pid) }.toString())
                }
                if (conn.responseCode in 200..201) {
                    val convId = JSONObject(conn.inputStream.bufferedReader().readText()).getString("id")
                    conversationId = convId
                    chatPrefs.edit().putString(PREF_CONV_ID, convId).apply()
                    fetchMessageHistory(convId, isFirstLoad = true)
                }
            } catch (e: Exception) { Log.e(TAG, "openConversation: ${e.message}") }
        }
    }

    // STEP 3: 메시지 히스토리 가져오기 (오름차순 응답 처리)
    private fun fetchMessageHistory(convId: String, isFirstLoad: Boolean) {
        val t = token ?: return
        if (isHistoryLoading) return
        isHistoryLoading = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = buildString {
                    append("${Constants.BASE_URL}/chat/conversations/$convId/messages?limit=$MSG_PAGE_LIMIT")
                    if (!isFirstLoad && oldestLoadedMessageId != null) {
                        append("&before_message_id=$oldestLoadedMessageId")
                    }
                }
                val conn = openConn(url, "GET", t)
                if (conn.responseCode == 200) {
                    val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                    val newMessages = mutableListOf<ChatMessage>()
                    for (i in 0 until arr.length()) {
                        parseMessage(arr.getJSONObject(i))?.let { newMessages.add(it) }
                    }

                    withContext(Dispatchers.Main) {
                        if (isFirstLoad) {
                            messages.clear()
                            messages.addAll(newMessages)
                            adapter.notifyDataSetChanged()
                            recycler.scrollToPosition(messages.size - 1)
                        } else {
                            if (newMessages.isNotEmpty()) {
                                messages.addAll(0, newMessages)
                                adapter.notifyItemRangeInserted(0, newMessages.size)
                            }
                        }
                        // 페이징용 ID 업데이트 (배열의 첫 번째가 가장 과거)
                        if (newMessages.isNotEmpty()) oldestLoadedMessageId = newMessages[0].id

                        // 마지막 메시지 읽음 처리
                        if (messages.isNotEmpty()) markRead(convId, messages.last().id)
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "fetchHistory: ${e.message}") }
            finally { isHistoryLoading = false }
        }
    }

    // 메시지 전송 (POST /chat/conversations/{id}/messages)
    private fun sendMessage(text: String) {
        val cid = conversationId ?: return
        val t = token ?: return

        // 1. UI 선반영 (Optimistic UI)
        val tempMsg = ChatMessage("", text, true, nowHHmm(), System.currentTimeMillis())
        messages.add(tempMsg)
        adapter.notifyItemInserted(messages.size - 1)
        recycler.scrollToPosition(messages.size - 1)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = openConn("${Constants.BASE_URL}/chat/conversations/$cid/messages", "POST", t, true)
                OutputStreamWriter(conn.outputStream).use {
                    it.write(JSONObject().apply { put("content", text) }.toString())
                }
                if (conn.responseCode in 200..201) {
                    val res = JSONObject(conn.inputStream.bufferedReader().readText())
                    // 실제 생성된 ID로 워터마크 갱신 가능
                    markRead(cid, res.optString("id"))
                }
            } catch (e: Exception) { Log.e(TAG, "sendMessage: ${e.message}") }
        }
    }

    // 읽음 처리 (POST /chat/conversations/{id}/read)
    private fun markRead(cid: String, mid: String) {
        if (mid.isEmpty()) return
        val t = token ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = openConn("${Constants.BASE_URL}/chat/conversations/$cid/read", "POST", t, true)
                OutputStreamWriter(conn.outputStream).use {
                    it.write(JSONObject().apply { put("last_read_message_id", mid) }.toString())
                }
                conn.responseCode // 실행 확인
            } catch (e: Exception) { /* silent */ }
        }
    }

    // =========================================================================
    // 유틸리티
    // =========================================================================

    private fun parseMessage(obj: JSONObject): ChatMessage? {
        val content = obj.optString("content", "")
        if (content.isBlank()) return null
        val senderId = obj.optString("sender_id")
        val createdAt = obj.optString("created_at")
        return ChatMessage(
            id = obj.optString("id"),
            text = content,
            isSent = senderId == myUserId,
            time = isoToHHmm(createdAt),
            timestampMs = isoToMs(createdAt)
        )
    }

    private fun openConn(url: String, method: String, t: String, doOut: Boolean = false): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $t")
            if (doOut) {
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            connectTimeout = 8000
            readTimeout = 8000
        }
    }

    private fun addReceivedMessageUI(text: String, id: String, time: String) {
        runOnUiThread {
            messages.add(ChatMessage(id, text, false, time, System.currentTimeMillis()))
            adapter.notifyItemInserted(messages.size - 1)
            recycler.smoothScrollToPosition(messages.size - 1)
        }
    }

    private fun applySystemBarsColor() {
        val color = if (isNightMode) Color.BLACK else Color.WHITE
        window.statusBarColor = color
        window.navigationBarColor = color
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNightMode
            isAppearanceLightNavigationBars = !isNightMode
        }
    }

    private fun nowHHmm() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private fun isoToHHmm(iso: String): String {
        return try {
            val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.KOREA).parse(iso)
            SimpleDateFormat("HH:mm", Locale.KOREA).format(date!!)
        } catch (e: Exception) { nowHHmm() }
    }

    private fun isoToMs(iso: String): Long {
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.KOREA).parse(iso)?.time ?: 0L
        } catch (e: Exception) { 0L }
    }

    override fun onResume() {
        super.onResume()
        ChatWebSocketService.isChatActivityVisible = true
        LocalBroadcastManager.getInstance(this).registerReceiver(chatReceiver, IntentFilter(ChatWebSocketService.ACTION_CHAT_MESSAGE))
    }

    override fun onPause() {
        super.onPause()
        ChatWebSocketService.isChatActivityVisible = false
        LocalBroadcastManager.getInstance(this).unregisterReceiver(chatReceiver)
    }

    // =========================================================================
    // Adapter
    // =========================================================================
    inner class ChatAdapter(private val list: List<ChatMessage>) : RecyclerView.Adapter<ChatAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val lSent: LinearLayout = v.findViewById(R.id.layoutSent)
            val lRecv: LinearLayout = v.findViewById(R.id.layoutReceived)
            val tSent: TextView = v.findViewById(R.id.tvMessageSent)
            val tRecv: TextView = v.findViewById(R.id.tvMessageReceived)
            val tmSent: TextView = v.findViewById(R.id.tvTimeSent)
            val tmRecv: TextView = v.findViewById(R.id.tvTimeReceived)
        }
        override fun onCreateViewHolder(p: ViewGroup, vt: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_chat_message, p, false))
        override fun getItemCount() = list.size
        override fun onBindViewHolder(h: VH, p: Int) {
            val m = list[p]
            if (m.isSent) {
                h.lSent.visibility = View.VISIBLE; h.lRecv.visibility = View.GONE
                h.tSent.text = m.text; h.tmSent.text = m.time
            } else {
                h.lSent.visibility = View.GONE; h.lRecv.visibility = View.VISIBLE
                h.tRecv.text = m.text; h.tmRecv.text = m.time
            }
        }
    }
}