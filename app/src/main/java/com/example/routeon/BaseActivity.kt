package com.example.routeon

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 모든 Activity 공통 부모.
 * 1. 앱 전역에서 웹소켓 서비스를 유지하고 글로벌 메시지 수신 알림을 처리합니다.
 * 2. 개발자 모드가 켜지면 드래그 가능한 동그란 스패너 FAB + 슬라이드 메뉴가 표시됩니다.
 */
abstract class BaseActivity : AppCompatActivity() {

    // ─── 개발자 모드 콜백 인터페이스 ─────────────────────────────────────────
    /**
     * 개발자 메뉴의 "😴 휴식 모드" 버튼이 눌렸을 때 오버레이를 표시하는 인터페이스.
     * 현재는 MainActivity만 구현한다.
     */
    interface DevRestModeCallback {
        fun triggerRestModeDemo()
    }

    /**
     * 개발자 메뉴의 "🗺️ 데모" 에서 시나리오가 선택됐을 때 호출되는 인터페이스.
     * MainActivity가 구현해 실제 네비 시작 + 시뮬레이터 재생을 처리한다.
     */
    interface DemoCallback {
        fun onDemoScenarioSelected(scenario: DemoScenarioPlayer.DemoScenario)
    }
    // ─────────────────────────────────────────────────────────────────────────

    private var devFab: ImageButton? = null
    private var devMenuContainer: LinearLayout? = null
    private var menuVisible = false

    // 글로벌 채팅 수신 리시버: 채팅방 밖에 있을 때 메시지가 오면 알림(Toast)을 띄움
    private val globalChatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 채팅방(ChatActivity)이 켜져 있으면 여기서 알림을 띄우지 않음
            if (ChatWebSocketService.isChatActivityVisible) return

            val content = intent.getStringExtra(ChatWebSocketService.EXTRA_MSG_CONTENT) ?: ""
            if (content.isNotEmpty()) {
                Toast.makeText(this@BaseActivity, "💬 새 메시지: $content", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ─── 생명주기 ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        // 앱이 포그라운드에 있는 동안 웹소켓 서비스 상시 가동
        ChatWebSocketService.start(this)

        LocalBroadcastManager.getInstance(this).registerReceiver(
            globalChatReceiver,
            IntentFilter(ChatWebSocketService.ACTION_CHAT_MESSAGE)
        )

        // 개발자 모드 FAB 동기화 (다른 화면에서 활성화 후 돌아온 경우)
        syncDevFab()
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(globalChatReceiver)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        syncDevFab()
    }

    // ─── 개발자 FAB 표시/제거 ────────────────────────────────────────────────

    private fun syncDevFab() {
        if (DeveloperModeManager.isEnabled(this)) {
            if (devFab == null) attachDevFab()
        } else {
            detachDevFab()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDevFab() {
        val decorView = window.decorView as? ViewGroup ?: return
        val density   = resources.displayMetrics.density

        val fabSize   = (56 * density).toInt()
        val margin    = (16 * density).toInt()

        val btn = ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@BaseActivity, R.drawable.ic_wrench))
            setBackgroundResource(R.drawable.bg_dev_fab)
            contentDescription = "개발자 메뉴"
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setPadding(
                (14 * density).toInt(), (14 * density).toInt(),
                (14 * density).toInt(), (14 * density).toInt()
            )
            elevation = 12 * density
        }

        val fabParams = FrameLayout.LayoutParams(fabSize, fabSize).apply {
            gravity      = Gravity.BOTTOM or Gravity.END
            bottomMargin = margin + getNavBarHeight()
            rightMargin  = margin
        }

        val menuLayout = buildMenuLayout(density)
        menuLayout.visibility = View.GONE
        menuLayout.elevation  = 10 * density

        var lastRawX      = 0f
        var lastRawY      = 0f
        var isDragging    = false
        var clickStart    = 0L

        btn.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX; lastRawY = event.rawY
                    isDragging = false;  clickStart = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastRawX
                    val dy = event.rawY - lastRawY
                    if (!isDragging && (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8)) {
                        isDragging = true
                        if (menuVisible) dismissMenu(menuLayout)
                    }
                    if (isDragging) {
                        val lp   = view.layoutParams as FrameLayout.LayoutParams
                        val scrW = resources.displayMetrics.widthPixels
                        val scrH = resources.displayMetrics.heightPixels
                        lp.rightMargin  = (scrW - event.rawX - view.width  / 2).toInt().coerceIn(0, scrW - view.width)
                        lp.bottomMargin = (scrH - event.rawY - view.height / 2).toInt().coerceIn(0, scrH - view.height)
                        view.layoutParams = lp
                        updateMenuPosition(menuLayout, lp.rightMargin, lp.bottomMargin, fabSize, density)
                        lastRawX = event.rawX; lastRawY = event.rawY
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging && System.currentTimeMillis() - clickStart < 300) {
                        if (menuVisible) dismissMenu(menuLayout)
                        else            showMenu(menuLayout, (btn.layoutParams as FrameLayout.LayoutParams), fabSize, density)
                    }
                    true
                }
                else -> false
            }
        }

        decorView.addView(btn, fabParams)
        decorView.addView(menuLayout)
        devFab           = btn
        devMenuContainer = menuLayout
    }

    private fun buildMenuLayout(density: Float): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.END
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val items = listOf(
            Triple("🗺️", "데모",          ::onDevMenu_Demo),
            Triple("📦", "배차 생성",      ::onDevMenu_CreateTrip),
            Triple("😴", "휴식 모드",      ::onDevMenu_RestMode),
            Triple("🔓", "개발자 모드 해제", ::onDevMenu_Disable)
        )

        items.forEach { (emoji, label, action) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL or Gravity.END
                setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            }

            val tv = TextView(this).apply {
                text = "$emoji  $label"
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(
                    (14 * density).toInt(), (8 * density).toInt(),
                    (14 * density).toInt(), (8 * density).toInt()
                )
                setBackgroundResource(R.drawable.bg_dev_menu_item)
                elevation = 8 * density
                setOnClickListener {
                    dismissMenu(devMenuContainer ?: return@setOnClickListener)
                    action()
                }
            }
            row.addView(tv)
            layout.addView(row)
        }
        return layout
    }

    private fun updateMenuPosition(
        menu: LinearLayout,
        fabRightMargin: Int, fabBottomMargin: Int,
        fabSize: Int, density: Float
    ) {
        val gap = (8 * density).toInt()
        val lp = menu.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.BOTTOM or Gravity.END
        lp.rightMargin  = fabRightMargin
        lp.bottomMargin = fabBottomMargin + fabSize + gap
        menu.layoutParams = lp
    }

    private fun showMenu(
        menu: LinearLayout,
        fabLp: FrameLayout.LayoutParams,
        fabSize: Int, density: Float
    ) {
        updateMenuPosition(menu, fabLp.rightMargin, fabLp.bottomMargin, fabSize, density)
        menu.visibility = View.VISIBLE
        menu.startAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_in).also {
            it.duration = 150
        })
        menuVisible = true
    }

    private fun dismissMenu(menu: LinearLayout) {
        menu.startAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_out).also {
            it.duration = 120
        })
        menu.visibility = View.GONE
        menuVisible = false
    }

    private fun detachDevFab() {
        devMenuContainer?.let { (window.decorView as? ViewGroup)?.removeView(it) }
        devFab?.let          { (window.decorView as? ViewGroup)?.removeView(it) }
        devFab = null; devMenuContainer = null; menuVisible = false
    }

    protected fun refreshDevFab() { detachDevFab(); syncDevFab() }

    private fun getNavBarHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    // =========================================================================
    // 개발자 메뉴 액션
    // =========================================================================

    private fun onDevMenu_Demo() {
        if (this !is DemoCallback) {
            AlertDialog.Builder(this)
                .setTitle("🗺️ 데모 시나리오")
                .setMessage("데모 시나리오는 메인 네비게이션 화면에서만 실행할 수 있습니다.\n\n메인 화면으로 이동 후 🔧 버튼을 다시 눌러주세요.")
                .setPositiveButton("확인", null).show()
            return
        }
        showDemoScenarioPicker()
    }

    /**
     * 내장 시나리오 3 개 + 저장된 GPX 파일 목록을 다이얼로그로 표시한다.
     * 사용자가 항목을 선택하면 [DemoCallback.onDemoScenarioSelected] 를 호출한다.
     */
    private fun showDemoScenarioPicker() {
        val builtin  = DemoScenarioPlayer.builtinScenarios()
        val recorder = GpxRecorder(this)
        val gpxFiles = recorder.listSavedFiles()

        // 항목 라벨 구성 (내장 + 파일)
        val labels = mutableListOf<String>()
        builtin.forEach { labels.add(it.name) }
        gpxFiles.forEach { labels.add("📂 ${it.nameWithoutExtension}") }

        if (labels.isEmpty()) {
            Toast.makeText(this, "시나리오가 없습니다. 내장 시나리오 오류를 확인하세요.", Toast.LENGTH_SHORT).show()
            return
        }

        // 속도 배율 라디오 (1x / 3x / 5x)
        val density = resources.displayMetrics.density
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20*density).toInt(), (8*density).toInt(), (20*density).toInt(), 0)
        }
        val speedLabel = android.widget.TextView(this).apply {
            text = "⚡ 재생 속도"
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
        }
        val speedRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        var speedMultiplier = 3
        val speeds = listOf(1 to "1x", 3 to "3x", 5 to "5x")
        val radioGroup = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
        speeds.forEach { (v, label) ->
            val rb = android.widget.RadioButton(this).apply {
                text = label; tag = v
                isChecked = (v == speedMultiplier)
            }
            rb.setOnCheckedChangeListener { _, checked -> if (checked) speedMultiplier = v }
            radioGroup.addView(rb)
        }
        layout.addView(speedLabel)
        layout.addView(radioGroup)
        layout.addView(android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (8*density).toInt()
            )
        })

        AlertDialog.Builder(this)
            .setTitle("🗺️ 데모 시나리오 선택")
            .setView(layout)
            .setItems(labels.toTypedArray()) { _, idx ->
                val scenario: DemoScenarioPlayer.DemoScenario
                if (idx < builtin.size) {
                    scenario = builtin[idx].copy()
                } else {
                    val file      = gpxFiles[idx - builtin.size]
                    // trkpt → 이동 경로 / wpt → 경유지(stops)
                    val trackPts  = recorder.parseGpxTrack(file)
                    val stops     = recorder.parseGpxStops(file)
                    if (trackPts.size < 2) {
                        Toast.makeText(this, "GPX 파일을 읽을 수 없습니다.", Toast.LENGTH_SHORT).show()
                        return@setItems
                    }
                    scenario = DemoScenarioPlayer.DemoScenario(
                        id          = "gpx_${file.nameWithoutExtension}",
                        name        = "📂 ${file.nameWithoutExtension}",
                        description = "저장된 GPX 경로 (${trackPts.size}포인트)",
                        stops       = stops,
                        trackPoints = trackPts,
                        isFromFile  = true,
                        sourceFile  = file
                    )
                }
                // 속도 저장 → MainActivity 에서 읽음
                getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
                    .edit().putInt("demo_speed_multiplier", speedMultiplier).apply()
                // 시작 확인 다이얼로그
                val selectedScenario = scenario
                AlertDialog.Builder(this)
                    .setTitle(selectedScenario.name)
                    .setMessage(
                        "${selectedScenario.description}\n\n" +
                        "속도: ${speedMultiplier}x 로 재생합니다.\n\n" +
                        "⚠ 기기 개발자 옵션에서\n" +
                        "'모의 위치 허용 앱 → RouteOn' 을 설정해야 합니다."
                    )
                    .setPositiveButton("▶ 시작") { _, _ ->
                        (this as DemoCallback).onDemoScenarioSelected(selectedScenario)
                    }
                    .setNegativeButton("취소", null).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun onDevMenu_CreateTrip() {
        val density = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (20 * density).toInt(), (16 * density).toInt(),
                (20 * density).toInt(), (8 * density).toInt()
            )
        }

        val etDestName = EditText(this).apply {
            hint = "목적지 이름 (예: 부산 물류센터)"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val etAddress = EditText(this).apply {
            hint = "주소 (예: 부산시 해운대구 센텀1로 10)"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setPadding(paddingLeft, (8 * density).toInt(), paddingRight, paddingTop)
        }

        layout.addView(etDestName)
        layout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (8 * density).toInt()
            )
        })
        layout.addView(etAddress)

        AlertDialog.Builder(this)
            .setTitle("📦 배차 생성")
            .setView(layout)
            .setPositiveButton("생성") { _, _ ->
                val name    = etDestName.text.toString().trim()
                val address = etAddress.text.toString().trim()
                if (name.isEmpty() || address.isEmpty()) {
                    Toast.makeText(this, "이름과 주소를 모두 입력해주세요.", Toast.LENGTH_SHORT).show()
                } else {
                    createTripFromDev(name, address)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun createTripFromDev(destName: String, address: String) {
        val token  = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: run {
            Toast.makeText(this, "로그인이 필요합니다.", Toast.LENGTH_SHORT).show(); return
        }
        val userId = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("user_id", null) ?: run {
            Toast.makeText(this, "사용자 ID를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show(); return
        }

        Toast.makeText(this, "주소 변환 중…", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val coordConn = URL("${Constants.BASE_URL}/address/coord?query=${java.net.URLEncoder.encode(address, "UTF-8")}")
                    .openConnection() as HttpURLConnection
                coordConn.requestMethod = "GET"
                coordConn.setRequestProperty("Authorization", "Bearer $token")
                coordConn.connectTimeout = 8000; coordConn.readTimeout = 8000

                if (coordConn.responseCode != 200) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@BaseActivity, "주소를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                    }; return@launch
                }

                val coordJson = JSONObject(coordConn.inputStream.bufferedReader().readText())
                val lat = coordJson.optDouble("lat", 0.0)
                val lon = coordJson.optDouble("lon", coordJson.optDouble("lng", 0.0))

                if (lat == 0.0 && lon == 0.0) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@BaseActivity, "좌표 변환 실패. 주소를 확인해주세요.", Toast.LENGTH_SHORT).show()
                    }; return@launch
                }

                val tripConn = URL("${Constants.BASE_URL}/trips").openConnection() as HttpURLConnection
                tripConn.requestMethod = "POST"
                tripConn.setRequestProperty("Content-Type", "application/json")
                tripConn.setRequestProperty("Authorization", "Bearer $token")
                tripConn.connectTimeout = 8000; tripConn.readTimeout = 8000
                tripConn.doOutput = true

                val body = JSONObject().apply {
                    put("driver_id", userId)
                    put("dest_name", destName)
                    put("dest_lat",  lat)
                    put("dest_lon",  lon)
                }
                OutputStreamWriter(tripConn.outputStream).use { it.write(body.toString()) }

                val code = tripConn.responseCode
                withContext(Dispatchers.Main) {
                    if (code == 201 || code == 200) {
                        Toast.makeText(this@BaseActivity, "✅ 배차가 생성되었습니다!\n$destName (${"%.4f".format(lat)}, ${"%.4f".format(lon)})", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@BaseActivity, "배차 생성 실패 (코드: $code)", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BaseActivity, "네트워크 오류: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 개발자 메뉴 — 😴 휴식 모드 데모
     *
     * MainActivity가 DevRestModeCallback 을 구현하고 있으면 오버레이를 직접 표시하고,
     * 다른 화면에서 눌렀다면 메인 화면으로 이동하라는 안내를 보여준다.
     *
     * 개발자 모드에서는 타이머가 15분 대신 10초로 표시되어 빠른 데모가 가능하다.
     */
    private fun onDevMenu_RestMode() {
        if (this is DevRestModeCallback) {
            // 현재 Activity(MainActivity)에서 직접 오버레이 트리거
            AlertDialog.Builder(this)
                .setTitle("😴 휴식 모드 데모")
                .setMessage(
                    "휴게소 도착 화면을 데모합니다.\n\n" +
                    "⏱ 타이머: 10초 (실제: 15분)\n" +
                    "🔘 '건너뛰기' 버튼으로 즉시 종료 가능\n\n" +
                    "지금 실행하시겠습니까?"
                )
                .setPositiveButton("▶ 실행") { _, _ ->
                    (this as DevRestModeCallback).triggerRestModeDemo()
                }
                .setNegativeButton("취소", null)
                .show()
        } else {
            // 다른 화면(설정, 채팅 등)에서 눌렀을 때 안내
            AlertDialog.Builder(this)
                .setTitle("😴 휴식 모드 데모")
                .setMessage(
                    "휴식 모드 데모는 메인 네비게이션 화면에서만 실행할 수 있습니다.\n\n" +
                    "메인 화면으로 이동 후 개발자 FAB(🔧)을 다시 눌러주세요."
                )
                .setPositiveButton("확인", null)
                .show()
        }
    }

    private fun onDevMenu_Disable() {
        AlertDialog.Builder(this)
            .setTitle("개발자 모드 해제")
            .setMessage("개발자 모드를 비활성화하시겠습니까?\n\n이 버튼이 모든 화면에서 사라집니다.")
            .setPositiveButton("해제") { _, _ ->
                DeveloperModeManager.disable(this)
                detachDevFab()
                Toast.makeText(this, getString(R.string.settings_dev_mode_disabled), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
