package com.example.routeon

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(
    val text: String,
    val isSent: Boolean,          // true = 내가 보낸 메시지, false = 수신
    val time: String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
)

class ChatActivity : AppCompatActivity() {

    private val isNightMode: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private lateinit var recycler: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        applySystemBarsColor()

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        recycler = findViewById(R.id.recyclerChat)
        adapter = ChatAdapter(messages)
        recycler.layoutManager = LinearLayoutManager(this).also { it.stackFromEnd = true }
        recycler.adapter = adapter

        // 자동 환영 메시지
        addReceivedMessage("안녕하세요! 고객센터입니다. 무엇을 도와드릴까요? 😊")

        val etMessage = findViewById<EditText>(R.id.etMessage)
        val btnSend   = findViewById<FloatingActionButton>(R.id.btnSend)

        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            etMessage.text.clear()
            addSentMessage(text)
            // 자동 응답 (실제 상담사 연결 전 임시)
            recycler.postDelayed({
                addReceivedMessage("감사합니다. 담당자가 곧 연결됩니다. 잠시만 기다려 주세요.")
            }, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        applySystemBarsColor()
    }

    private fun applySystemBarsColor() {
        val barColor = if (isNightMode) Color.BLACK else Color.WHITE
        window.statusBarColor     = barColor
        window.navigationBarColor = barColor
        val ic = WindowInsetsControllerCompat(window, window.decorView)
        ic.isAppearanceLightStatusBars     = !isNightMode
        ic.isAppearanceLightNavigationBars = !isNightMode
    }

    private fun addSentMessage(text: String) {
        messages.add(ChatMessage(text, isSent = true))
        adapter.notifyItemInserted(messages.size - 1)
        recycler.scrollToPosition(messages.size - 1)
    }

    private fun addReceivedMessage(text: String) {
        messages.add(ChatMessage(text, isSent = false))
        adapter.notifyItemInserted(messages.size - 1)
        recycler.scrollToPosition(messages.size - 1)
    }

    // ── RecyclerView Adapter ──
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
                holder.tvSent.text     = msg.text
                holder.tvTimeSent.text = msg.time
            } else {
                holder.layoutSent.visibility     = View.GONE
                holder.layoutReceived.visibility = View.VISIBLE
                holder.tvReceived.text     = msg.text
                holder.tvTimeReceived.text = msg.time
            }
        }
    }
}
