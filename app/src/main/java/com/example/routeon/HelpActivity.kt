package com.example.routeon

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowInsetsControllerCompat

class HelpActivity : AppCompatActivity() {

    private val isNightMode: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        applySystemBarsColor()

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // 배차 취소
        findViewById<LinearLayout>(R.id.itemCancelTrip).setOnClickListener {
            showCancelReasonDialog()
        }

        // 기타 문의
        findViewById<LinearLayout>(R.id.itemOtherInquiry).setOnClickListener {
            showOtherInquiryDialog()
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

    // ── 배차 취소 사유 선택 ──
    private fun showCancelReasonDialog() {
        val reasons = arrayOf("차량 고장", "건강상 문제", "사고 발생", "기타 (사유 직접 입력)")
        AlertDialog.Builder(this)
            .setTitle("취소 사유를 선택해 주세요")
            .setItems(reasons) { _, which ->
                when (which) {
                    3 -> showDirectInputDialog()          // 기타 → 직접 입력
                    else -> confirmCancel(reasons[which]) // 나머지 → 바로 확인
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun showDirectInputDialog() {
        val input = EditText(this).apply {
            hint = "취소 사유를 입력해 주세요"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("취소 사유 입력")
            .setView(input)
            .setPositiveButton("확인") { _, _ ->
                val reason = input.text.toString().trim()
                if (reason.isNotEmpty()) confirmCancel(reason)
                else Toast.makeText(this, "사유를 입력해 주세요.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmCancel(reason: String) {
        AlertDialog.Builder(this)
            .setTitle("배차 취소")
            .setMessage("취소 사유: $reason\n\n배차를 취소하시겠습니까?")
            .setPositiveButton("예") { _, _ ->
                // TODO: 실제 취소 API 연동 (MainActivity의 updateTripStatus 참고)
                Toast.makeText(this, "취소 요청이 접수되었습니다.", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("아니오", null)
            .show()
    }

    // ── 기타 문의 ──
    private fun showOtherInquiryDialog() {
        val options = arrayOf("채팅 문의", "전화 상담")
        AlertDialog.Builder(this)
            .setTitle("기타 문의")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, ChatActivity::class.java))
                    1 -> dialSupportPhone()
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun dialSupportPhone() {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:01081972581"))
        startActivity(intent)
    }
}
