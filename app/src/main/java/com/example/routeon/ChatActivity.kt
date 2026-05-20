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

    private val chatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val content = intent.getStringExtra(ChatWebSocketService.EXTRA_MSG_CONTENT) ?: ""
            val msgId = intent.getStringExtra(ChatWebSocketService.EXTRA_MSG_ID) ?: ""
            val createdAt = intent.getStringExtra(ChatWebSocketService.EXTRA_MSG_CREATED_AT) ?: ""

            if (content.isNotEmpty()) {
                if (messages.none { it.id == msgId && msgId.isNotEmpty() }) {
                    addReceivedMessageUI(content, msgId, isoToHHmm(createdAt))
                    conversationId?.let { markRead(it, msgId) }
                }
            } else {
                conversationId?.let { fetchLatestMessageSilent(it) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        setupUI()
        ChatWebSocketService.start(this)

        // ── 1. 캐시된 conversation_id 확인
        val cachedId = getSharedPreferences("ChatPrefs", Context.MODE_PRIVATE)
            .getString("chat_conversation_id", null)

        if (cachedId != null) {
            conversationId = cachedId
            fetchMessageHistory(cachedId)
        } else {
            // ── 2. 없으면 파트너 조회 → 대화방 생성/조회
            initConversation()
        }
    }

    // ── 채팅 파트너(관리자) 조회 후 대화방 생성 ─────────────────────────────
    private fun initConversation() {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: run {
            Toast.makeText(this, "로그인 정보가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // STEP 1: 채팅 가능한 파트너(관리자) 목록 조회
                val partnersConn = (URL("${Constants.BASE_URL}/chat/partners").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $token")
                    connectTimeout = 8000
                    readTimeout = 8000
                }

                if (partnersConn.responseCode != 200) {
                    Log.e("ChatActivity", "❌ /chat/partners 실패: ${partnersConn.responseCode}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ChatActivity, "관리자 정보를 불러올 수 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val partnersArr = JSONArray(partnersConn.inputStream.bufferedReader().readText())
                if (partnersArr.length() == 0) {
                    Log.w("ChatActivity", "⚠️ 채팅 가능한 파트너 없음")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ChatActivity, "채팅 가능한 관리자가 없습니다.", Toast.LENGTH_SHORT).show()
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
                val convConn = (URL("${Constants.BASE_URL}/chat/conversations").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                OutputStreamWriter(convConn.outputStream).use {
                    it.write(JSONObject().apply { put("partner_id", partnerId) }.toString())
                }

                val convCode = convConn.responseCode
                if (convCode !in 200..201) {
                    Log.e("ChatActivity", "❌ /chat/conversations 실패: $convCode")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ChatActivity, "대화방을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val convJson = JSONObject(convConn.inputStream.bufferedReader().readText())
                val convId = convJson.optString("id", "")
                if (convId.isEmpty()) {
                    Log.e("ChatActivity", "❌ conversation_id 없음")
                    return@launch
                }

                Log.d("ChatActivity", "✅ conversation_id: $convId")

                // STEP 3: 저장 및 히스토리 로드
                getSharedPreferences("ChatPrefs", Context.MODE_PRIVATE).edit()
                    .putString("chat_conversation_id", convId)
                    .apply()

                withContext(Dispatchers.Main) {
                    conversationId = convId
                    fetchMessageHistory(convId)
                }

            } catch (e: Exception) {
                Log.e("ChatActivity", "💥 initConversation 오류: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ChatActivity, "채팅 초기화 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupUI() {
        recycler = findViewById(R.id.recyclerChat)
        adapter = ChatAdapter(messages)
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recycler.adapter = adapter

        etMessage = findViewById(R.id.etMessage)
        findViewById<FloatingActionButton>(R.id.btnSend).setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                etMessage.text.clear()
                sendMessage(text)
            }
        }
    }

    private fun addReceivedMessageUI(text: String, id: String, time: String) {
        runOnUiThread {
            messages.add(ChatMessage(id, text, false, time, System.currentTimeMillis()))
            adapter.notifyItemInserted(messages.size - 1)
            recycler.scrollToPosition(messages.size - 1)
        }
    }

    private fun fetchLatestMessageSilent(convId: String) {
        val t = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).getString("access_token", null) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = (URL("${Constants.BASE_URL}/chat/conversations/$convId/messages?limit=1").openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Authorization", "Bearer $t")
                }
                if (conn.responseCode == 200) {
                    val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                    if (arr.length() > 0) {
                        val obj = arr.getJSONObject(0)
                        val id = obj.optString("id", "")
                        val content = obj.optString("content", "")
                        val senderId = obj.optString("sender_id", "")
                        val createdAt = obj.optString("created_at", "")
                        val myId = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).getString("user_id", "") ?: ""
                        withContext(Dispatchers.Main) {
                            if (messages.none { it.id == id && id.isNotEmpty() }) {
                                if (senderId != myId) {
                                    addReceivedMessageUI(content, id, isoToHHmm(createdAt))
                                    markRead(convId, id)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) { }
        }
    }

    private fun fetchMessageHistory(convId: String) {
        val t = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).getString("access_token", null) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = (URL("${Constants.BASE_URL}/chat/conversations/$convId/messages?limit=50").openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Authorization", "Bearer $t")
                }
                if (conn.responseCode == 200) {
                    val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                    val myId = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).getString("user_id", "") ?: ""
                    val history = mutableListOf<ChatMessage>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        history.add(ChatMessage(
                            obj.getString("id"),
                            obj.getString("content"),
                            obj.getString("sender_id") == myId,
                            isoToHHmm(obj.getString("created_at")),
                            0L
                        ))
                    }
                    withContext(Dispatchers.Main) {
                        messages.clear()
                        messages.addAll(history)
                        adapter.notifyDataSetChanged()
                        recycler.scrollToPosition(messages.size - 1)
                    }
                }
            } catch (e: Exception) { }
        }
    }

    private fun sendMessage(text: String) {
        val cid = conversationId ?: run {
            // conversationId가 아직 없으면 대화방 초기화 먼저 시도
            Toast.makeText(this, "대화방 연결 중입니다. 잠시 후 다시 시도해주세요.", Toast.LENGTH_SHORT).show()
            initConversation()
            return
        }
        val t = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).getString("access_token", null) ?: return

        val temp = ChatMessage("", text, true, SimpleDateFormat("HH:mm", Locale.KOREA).format(Date()), System.currentTimeMillis())
        messages.add(temp)
        adapter.notifyItemInserted(messages.size - 1)
        recycler.scrollToPosition(messages.size - 1)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = (URL("${Constants.BASE_URL}/chat/conversations/$cid/messages").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $t")
                    setRequestProperty("Content-Type", "application/json")
                }
                OutputStreamWriter(conn.outputStream).use {
                    it.write(JSONObject().apply { put("content", text) }.toString())
                }
                val code = conn.responseCode
                Log.d("ChatActivity", "📤 메시지 전송: HTTP $code")
            } catch (e: Exception) {
                Log.e("ChatActivity", "❌ 메시지 전송 오류: ${e.message}")
            }
        }
    }

    private fun markRead(cid: String, mid: String) {
        val t = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).getString("access_token", null) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = (URL("${Constants.BASE_URL}/chat/conversations/$cid/read").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $t")
                    setRequestProperty("Content-Type", "application/json")
                }
                OutputStreamWriter(conn.outputStream).use {
                    it.write(JSONObject().apply { put("last_read_message_id", mid) }.toString())
                }
                conn.responseCode
            } catch (e: Exception) { }
        }
    }

    private fun isoToHHmm(iso: String): String {
        return try {
            val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.KOREA).parse(iso)
            SimpleDateFormat("HH:mm", Locale.KOREA).format(date!!)
        } catch (e: Exception) { "" }
    }

    override fun onResume() {
        super.onResume()
        ChatWebSocketService.isChatActivityVisible = true
        LocalBroadcastManager.getInstance(this).registerReceiver(
            chatReceiver, IntentFilter(ChatWebSocketService.ACTION_CHAT_MESSAGE)
        )
    }

    override fun onPause() {
        super.onPause()
        ChatWebSocketService.isChatActivityVisible = false
        LocalBroadcastManager.getInstance(this).unregisterReceiver(chatReceiver)
    }

    inner class ChatAdapter(private val list: List<ChatMessage>) : RecyclerView.Adapter<ChatAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val lSent: LinearLayout = v.findViewById(R.id.layoutSent)
            val lRecv: LinearLayout = v.findViewById(R.id.layoutReceived)
            val tSent: TextView = v.findViewById(R.id.tvMessageSent)
            val tRecv: TextView = v.findViewById(R.id.tvMessageReceived)
            val tmSent: TextView = v.findViewById(R.id.tvTimeSent)
            val tmRecv: TextView = v.findViewById(R.id.tvTimeReceived)
        }
        override fun onCreateViewHolder(p: ViewGroup, vt: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_chat_message, p, false))
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
