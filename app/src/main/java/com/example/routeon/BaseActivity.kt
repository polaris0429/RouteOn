package com.example.routeon

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 모든 Activity의 공통 부모.
 * 개발자 모드가 활성화된 경우, 모든 화면 위에 드래그 가능한 스패너 플로팅 버튼을 표시합니다.
 */
abstract class BaseActivity : AppCompatActivity() {

    private var devFab: ImageButton? = null

    // ─── 생명 주기 ────────────────────────────────────────────────────────────

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

    // ─── 개발자 FAB 표시 / 제거 ───────────────────────────────────────────────

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

        val btn = ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@BaseActivity, R.drawable.ic_wrench))
            setBackgroundColor(Color.parseColor("#CC1976D2"))  // 반투명 파랑
            contentDescription = "개발자 모드"
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setPadding(24, 24, 24, 24)
        }

        val size = (56 * resources.displayMetrics.density).toInt()
        val margin = (16 * resources.displayMetrics.density).toInt()

        val params = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = margin + getNavBarHeight()
            rightMargin = margin
        }

        // 드래그 처리
        var lastRawX = 0f
        var lastRawY = 0f
        var isDragging = false
        var clickStartTime = 0L

        btn.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    isDragging = false
                    clickStartTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastRawX
                    val dy = event.rawY - lastRawY
                    if (!isDragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val lp = view.layoutParams as FrameLayout.LayoutParams

                        // RIGHT/BOTTOM margin 기반으로 위치 계산
                        val screenW = resources.displayMetrics.widthPixels
                        val screenH = resources.displayMetrics.heightPixels

                        val newRight = (screenW - event.rawX - view.width / 2).toInt()
                            .coerceIn(0, screenW - view.width)
                        val newBottom = (screenH - event.rawY - view.height / 2).toInt()
                            .coerceIn(0, screenH - view.height)

                        lp.rightMargin = newRight
                        lp.bottomMargin = newBottom
                        // gravity를 BOTTOM|END 유지하면서 margin으로 이동
                        view.layoutParams = lp
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - clickStartTime
                    if (!isDragging && elapsed < 300) {
                        // 탭: 개발자 모드 토스트
                        Toast.makeText(
                            this@BaseActivity,
                            "🔧 개발자 모드 활성화됨",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    true
                }
                else -> false
            }
        }

        decorView.addView(btn, params)
        devFab = btn
    }

    private fun detachDevFab() {
        val fab = devFab ?: return
        (window.decorView as? ViewGroup)?.removeView(fab)
        devFab = null
    }

    // 내비게이션 바 높이 (화면 하단 여백)
    private fun getNavBarHeight(): Int {
        val resId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    // ─── 하위 클래스에서 FAB를 수동으로 갱신할 때 사용 ─────────────────────────
    protected fun refreshDevFab() {
        detachDevFab()
        syncDevFab()
    }
}
