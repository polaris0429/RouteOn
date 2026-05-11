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
        Toast.makeText(this, "🗺️ 데모 모드는 추후 GPS 스푸핑으로 구현 예정입니다.", Toast.LENGTH_LONG).show()
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

    private fun onDevMenu_RestMode() {
        AlertDialog.Builder(this)
            .setTitle("😴 휴식 모드 (테스트)")
            .setMessage("휴식 모드를 활성화합니다.\n\n실제 GPS 전송이 중지되고 '휴식 중' 상태로 표시됩니다.\n\n(현재 테스트 구현 — 실제 기능은 추후 연동)")
            .setPositiveButton("활성화") { _, _ ->
                Toast.makeText(this, "😴 휴식 모드가 활성화되었습니다. (테스트)", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("취소", null)
            .show()
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