package com.example.routeon

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
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
 * 개발자 모드가 켜지면 드래그 가능한 동그란 스패너 FAB + 슬라이드 메뉴가 모든 화면에 표시됩니다.
 */
abstract class BaseActivity : AppCompatActivity() {

    private var devFab: ImageButton? = null
    private var devMenuContainer: LinearLayout? = null   // FAB 위에 떠있는 메뉴 컨테이너
    private var menuVisible = false

    // ─── 생명주기 ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        syncDevFab()
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

        // ── 동그란 FAB ─────────────────────────────────────────────────────
        val btn = ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@BaseActivity, R.drawable.ic_wrench))
            setBackgroundResource(R.drawable.bg_dev_fab)           // 동그란 반투명 배경
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

        // ── 메뉴 컨테이너 (FAB 왼쪽 위에 절대 위치로 붙임) ─────────────────
        val menuLayout = buildMenuLayout(density)
        menuLayout.visibility = View.GONE
        menuLayout.elevation  = 10 * density

        // ── 드래그 & 클릭 ──────────────────────────────────────────────────
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
                        // 메뉴도 같이 이동
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
        decorView.addView(menuLayout)      // decorView에 같이 추가
        devFab           = btn
        devMenuContainer = menuLayout
    }

    // ─── 메뉴 레이아웃 빌드 ──────────────────────────────────────────────────
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

            // 텍스트 라벨 (말풍선 배경)
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

    // ─── 메뉴 위치 계산 (FAB 왼쪽 위) ────────────────────────────────────────
    private fun updateMenuPosition(
        menu: LinearLayout,
        fabRightMargin: Int, fabBottomMargin: Int,
        fabSize: Int, density: Float
    ) {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val gap     = (8 * density).toInt()

        val lp = menu.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.BOTTOM or Gravity.END

        // FAB의 오른쪽 끝 기준으로 메뉴를 오른쪽 정렬
        lp.rightMargin  = fabRightMargin
        // FAB 위로 gap만큼 띄움
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

    /** 하위 클래스에서 개발자 모드 활성화 직후 FAB를 즉시 표시할 때 호출 */
    protected fun refreshDevFab() { detachDevFab(); syncDevFab() }

    // ─── 내비게이션 바 높이 ─────────────────────────────────────────────────
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
                // 1. 주소 → 좌표
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

                // 2. 운행(Trip) 생성
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
