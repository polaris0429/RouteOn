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

            // 💡 핵심 1: 백엔드가 텍스트를 웹소켓으로 같이 줬다면 바로 화면에 표시
            if (content.isNotEmpty()) {
                if (messages.none { it.id == msgId && msgId.isNotEmpty() }) {
                    addReceivedMessageUI(content, msgId, isoToHHmm(createdAt))
                    conversationId?.let { markRead(it, msgId) }
                }
            }
            // 💡 핵심 2: 백엔드가 텍스트 없이 "채팅 왔어!" 라는 이벤트(신호)만 줬을 경우 REST API로 즉시 최신 1개를 가져옴
            else {
                conversationId?.let { fetchLatestMessageSilent(it) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        setupUI()

        val cachedId = getSharedPreferences("ChatPrefs", Context.MODE_PRIVATE).getString("chat_conversation_id", null)
        if (cachedId != null) {
            conversationId = cachedId
            fetchMessageHistory(cachedId)
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

    // 서버가 빈 껍데기만 보냈을 때 조용히 최신 메시지 1개만 가져와서 채워넣는 이중 방어 로직
    private fun fetchLatestMessageSilent(convId: String) {
        val t = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).all["access_token"]?.toString() ?: return
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

                        val myId = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).all["user_id"]?.toString() ?: ""

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
        val t = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).all["access_token"]?.toString() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = (URL("${Constants.BASE_URL}/chat/conversations/$convId/messages?limit=50").openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Authorization", "Bearer $t")
                }
                if (conn.responseCode == 200) {
                    val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                    val history = mutableListOf<ChatMessage>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        history.add(ChatMessage(obj.getString("id"), obj.getString("content"), obj.getString("sender_id") == getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).all["user_id"]?.toString(), isoToHHmm(obj.getString("created_at")), 0L))
                    }
                    withContext(Dispatchers.Main) {
                        messages.clear(); messages.addAll(history)
                        adapter.notifyDataSetChanged()
                        recycler.scrollToPosition(messages.size - 1)
                    }
                }
            } catch (e: Exception) { }
        }
    }

    private fun sendMessage(text: String) {
        val cid = conversationId ?: return
        val t = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).all["access_token"]?.toString() ?: return

        val temp = ChatMessage("", text, true, SimpleDateFormat("HH:mm", Locale.KOREA).format(Date()), System.currentTimeMillis())
        messages.add(temp)
        adapter.notifyItemInserted(messages.size - 1)
        recycler.scrollToPosition(messages.size - 1)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = (URL("${Constants.BASE_URL}/chat/conversations/$cid/messages").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true
                    setRequestProperty("Authorization", "Bearer $t")
                    setRequestProperty("Content-Type", "application/json")
                }
                OutputStreamWriter(conn.outputStream).use { it.write(JSONObject().apply { put("content", text) }.toString()) }
                conn.responseCode
            } catch (e: Exception) { }
        }
    }

    private fun markRead(cid: String, mid: String) {
        val t = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).all["access_token"]?.toString() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = (URL("${Constants.BASE_URL}/chat/conversations/$cid/read").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true
                    setRequestProperty("Authorization", "Bearer $t")
                    setRequestProperty("Content-Type", "application/json")
                }
                OutputStreamWriter(conn.outputStream).use { it.write(JSONObject().apply { put("last_read_message_id", mid) }.toString()) }
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
        LocalBroadcastManager.getInstance(this).registerReceiver(chatReceiver, IntentFilter(ChatWebSocketService.ACTION_CHAT_MESSAGE))
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