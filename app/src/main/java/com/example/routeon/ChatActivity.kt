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
    val isSent: Boolean,
    val time: String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
)

class ChatActivity : BaseActivity() {

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

        addReceivedMessage(getString(R.string.chat_welcome))

        val etMessage = findViewById<EditText>(R.id.etMessage)
        val btnSend   = findViewById<FloatingActionButton>(R.id.btnSend)

        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            etMessage.text.clear()
            addSentMessage(text)
            recycler.postDelayed({
                addReceivedMessage(getString(R.string.chat_auto_reply))
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
