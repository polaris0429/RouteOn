package com.example.routeon

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ─── 데이터 모델 ─── */
data class TripRecord(
    val id:            String,
    val destName:      String,
    val status:        String,           // completed / cancelled / in_progress / scheduled
    val distanceKm:    Double,           // optimized_route.total_distance_km
    val durationMin:   Double,           // optimized_route.estimated_duration_min
    val startedAt:     String?,          // ISO-8601 문자열
    val completedAt:   String?,
    val dateLabel:     String            // "YYYY-MM-DD" (그룹 키)
)

sealed class HistoryItem {
    data class Header(val date: String, val dayLabel: String) : HistoryItem()
    data class Record(val trip: TripRecord) : HistoryItem()
}

class TripHistoryActivity : BaseActivity() {

    private val isNightMode: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvSummary: TextView
    private lateinit var spinnerDate: Spinner

    private val allRecords  = mutableListOf<TripRecord>()
    private val dateOptions = mutableListOf<String>() // "전체", "2026-04-08", …
    private val adapter     = HistoryAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_history)
        applySystemBarsColor()

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "운행 기록"
        toolbar.setNavigationOnClickListener { finish() }

        recyclerView = findViewById(R.id.recyclerHistory)
        tvEmpty      = findViewById(R.id.tvEmpty)
        tvSummary    = findViewById(R.id.tvSummary)
        spinnerDate  = findViewById(R.id.spinnerDate)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadHistory()
    }

    override fun onResume() {
        super.onResume()
        applySystemBarsColor()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBarsColor()
    }

    /* ─── API 호출 ─── */
    private fun loadHistory() {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("${Constants.BASE_URL}/trips")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 10000; conn.readTimeout = 10000

                if (conn.responseCode == 200) {
                    val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                    val records = mutableListOf<TripRecord>()

                    for (i in 0 until arr.length()) {
                        val obj    = arr.getJSONObject(i)
                        val status = obj.optString("status", "")

                        // completed / cancelled 만 표시
                        if (status != "completed" && status != "cancelled") continue

                        // optimized_route 에서 거리/시간 꺼내기
                        var distKm  = 0.0
                        var durMin  = 0.0
                        val routeObj = obj.optJSONObject("optimized_route")
                        if (routeObj != null) {
                            distKm = routeObj.optDouble("total_distance_km",    0.0)
                            durMin = routeObj.optDouble("estimated_duration_min", 0.0)
                        }

                        val completedAt = obj.optString("completed_at").takeIf { it.isNotEmpty() }
                        val startedAt   = obj.optString("started_at").takeIf   { it.isNotEmpty() }

                        // 날짜 레이블 (완료일 우선, 없으면 생성일)
                        val rawDate = (completedAt ?: obj.optString("created_at", "")).take(10)

                        records.add(
                            TripRecord(
                                id          = obj.optString("id", ""),
                                destName    = obj.optString("dest_name", "목적지"),
                                status      = status,
                                distanceKm  = distKm,
                                durationMin = durMin,
                                startedAt   = startedAt,
                                completedAt = completedAt,
                                dateLabel   = rawDate
                            )
                        )
                    }

                    // 최신순 정렬
                    records.sortByDescending { it.completedAt ?: it.startedAt ?: it.dateLabel }

                    withContext(Dispatchers.Main) {
                        allRecords.clear()
                        allRecords.addAll(records)
                        setupDateSpinner()
                        applyFilter("전체")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@TripHistoryActivity, "데이터를 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
                        showEmpty()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TripHistoryActivity, "네트워크 오류: ${e.message}", Toast.LENGTH_SHORT).show()
                    showEmpty()
                }
            }
        }
    }

    /* ─── 날짜 스피너 ─── */
    private fun setupDateSpinner() {
        dateOptions.clear()
        dateOptions.add("전체")
        allRecords.map { it.dateLabel }.distinct().sorted().reversed().forEach {
            if (it.isNotEmpty()) dateOptions.add(it)
        }

        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, dateOptions)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDate.adapter = spinnerAdapter

        spinnerDate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                applyFilter(dateOptions[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /* ─── 필터 + 렌더링 ─── */
    private fun applyFilter(selectedDate: String) {
        val filtered = if (selectedDate == "전체") allRecords
                       else allRecords.filter { it.dateLabel == selectedDate }

        if (filtered.isEmpty()) { showEmpty(); return }

        // 요약 통계
        val totalKm  = filtered.sumOf { it.distanceKm }
        val totalMin = filtered.sumOf { it.durationMin }
        val doneCount   = filtered.count { it.status == "completed" }
        val cancelCount = filtered.count { it.status == "cancelled" }
        tvSummary.text = "총 ${filtered.size}건 · ${"%.1f".format(totalKm)}km · ${formatDuration(totalMin)}   완료 $doneCount 취소 $cancelCount"
        tvSummary.visibility = View.VISIBLE

        // 날짜 헤더 + 항목으로 변환
        val items = mutableListOf<HistoryItem>()
        var lastDate = ""
        filtered.forEach { rec ->
            if (rec.dateLabel != lastDate) {
                items.add(HistoryItem.Header(rec.dateLabel, formatDateHeader(rec.dateLabel)))
                lastDate = rec.dateLabel
            }
            items.add(HistoryItem.Record(rec))
        }

        adapter.submitList(items)
        recyclerView.visibility = View.VISIBLE
        tvEmpty.visibility      = View.GONE
    }

    private fun showEmpty() {
        recyclerView.visibility = View.GONE
        tvEmpty.visibility      = View.VISIBLE
        tvSummary.visibility    = View.GONE
    }

    /* ─── 유틸 ─── */
    private fun formatDuration(minutes: Double): String {
        val h = (minutes / 60).toInt()
        val m = (minutes % 60).toInt()
        return if (h > 0) "${h}시간 ${m}분" else "${m}분"
    }

    private fun formatDateHeader(raw: String): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
            val date = sdf.parse(raw) ?: return raw
            val out  = SimpleDateFormat("yyyy년 M월 d일 (E)", Locale.KOREA)
            out.format(date)
        } catch (e: Exception) { raw }
    }

    private fun formatDateTime(raw: String?): String {
        if (raw.isNullOrEmpty()) return "—"
        return try {
            // ISO-8601: "2026-04-23T15:30:00" 또는 "2026-04-23T15:30:00.000000"
            val clean = raw.substringBefore(".").replace("T", " ")
            val sdf   = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
            val date  = sdf.parse(clean) ?: return raw
            SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(date)
        } catch (e: Exception) { raw.take(16).replace("T", " ") }
    }

    private fun applySystemBarsColor() {
        val barColor = if (isNightMode) Color.parseColor("#1E1E1E") else Color.WHITE
        window.statusBarColor     = barColor
        window.navigationBarColor = barColor
        val ic = WindowInsetsControllerCompat(window, window.decorView)
        ic.isAppearanceLightStatusBars     = !isNightMode
        ic.isAppearanceLightNavigationBars = !isNightMode
    }

    /* ══════════════════════════════════════════════════════════════════
     *  RecyclerView Adapter
     * ══════════════════════════════════════════════════════════════════*/
    inner class HistoryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = mutableListOf<HistoryItem>()

        fun submitList(newItems: List<HistoryItem>) {
            items.clear(); items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int) =
            when (items[position]) {
                is HistoryItem.Header -> 0
                is HistoryItem.Record -> 1
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> HeaderVH(inflater.inflate(R.layout.item_history_header, parent, false))
                else -> RecordVH(inflater.inflate(R.layout.item_history_record, parent, false))
            }
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is HistoryItem.Header -> (holder as HeaderVH).bind(item)
                is HistoryItem.Record -> (holder as RecordVH).bind(item.trip)
            }
        }

        inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
            private val tvDate: TextView = v.findViewById(R.id.tvDate)
            fun bind(h: HistoryItem.Header) { tvDate.text = h.dayLabel }
        }

        inner class RecordVH(v: View) : RecyclerView.ViewHolder(v) {
            private val tvDest:      TextView = v.findViewById(R.id.tvDest)
            private val tvStatus:    TextView = v.findViewById(R.id.tvStatus)
            private val tvDistance:  TextView = v.findViewById(R.id.tvDistance)
            private val tvDuration:  TextView = v.findViewById(R.id.tvDuration)
            private val tvCompleted: TextView = v.findViewById(R.id.tvCompleted)

            fun bind(t: TripRecord) {
                tvDest.text     = t.destName
                tvDistance.text = if (t.distanceKm > 0) "${"%.1f".format(t.distanceKm)} km" else "— km"
                tvDuration.text = if (t.durationMin > 0) formatDuration(t.durationMin) else "—"
                tvCompleted.text = formatDateTime(t.completedAt ?: t.startedAt)

                when (t.status) {
                    "completed" -> {
                        tvStatus.text = "완료"
                        tvStatus.setBackgroundResource(R.drawable.bg_status_done)
                        tvStatus.setTextColor(Color.WHITE)
                    }
                    "cancelled" -> {
                        tvStatus.text = "취소"
                        tvStatus.setBackgroundResource(R.drawable.bg_status_cancelled)
                        tvStatus.setTextColor(Color.WHITE)
                    }
                    else -> {
                        tvStatus.text = t.status
                        tvStatus.setBackgroundResource(R.drawable.bg_status_done)
                        tvStatus.setTextColor(Color.WHITE)
                    }
                }
            }
        }
    }
}
