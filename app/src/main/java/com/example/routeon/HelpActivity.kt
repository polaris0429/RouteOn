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
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class HelpActivity : BaseActivity() {

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

        findViewById<LinearLayout>(R.id.itemCancelTrip).setOnClickListener {
            showCancelReasonDialog()
        }

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

    private fun showCancelReasonDialog() {
        // getString()을 사용하여 xml에 정의된 값을 가져옵니다.
        val reasons = arrayOf(
            getString(R.string.cancel_reason_vehicle_issue),
            getString(R.string.cancel_reason_health_issue),
            getString(R.string.cancel_reason_accident), // "사고 발생"도 리소스화 권장
            getString(R.string.cancel_reason_other)       // "기타" 사유
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.cancel_reason_select) // setTitle에도 리소스 ID 직접 전달 가능
            .setItems(reasons) { _, which ->
                when (which) {
                    3 -> showDirectInputDialog()
                    else -> confirmCancel(reasons[which])
                }
            }
            .setNegativeButton(R.string.close, null) // "닫기"도 다국어 대응
            .show()
    }

    private fun showDirectInputDialog() {
        val input = EditText(this).apply {
            // hint는 String 타입이 필요하므로 getString 사용
            hint = getString(R.string.help_direct_input_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(48, 24, 48, 24)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.help_direct_input_title)
            .setView(input)
            .setPositiveButton(R.string.common_confirm) { _, _ ->
                val reason = input.text.toString().trim()
                if (reason.isNotEmpty()) {
                    confirmCancel(reason)
                } else {
                    // Toast도 리소스 ID를 직접 넣을 수 있습니다.
                    Toast.makeText(this, R.string.help_direct_input_empty_toast, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun confirmCancel(reason: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dispatch_cancel)
            .setMessage(getString(R.string.help_cancel_confirm_message, reason))
            .setPositiveButton(R.string.common_yes) { _, _ ->
                val prefs = getSharedPreferences("RouteOnPrefs", MODE_PRIVATE)
                val token = prefs.getString("access_token", null)
                val tripId = prefs.getString("cancel_trip_id", null)

                if (token == null || tripId == null) {
                    Toast.makeText(this, "취소할 운행 정보를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val conn = URL("${Constants.BASE_URL}/trips/$tripId/status?status=cancelled")
                            .openConnection() as HttpURLConnection
                        conn.requestMethod = "PATCH"
                        conn.setRequestProperty("Authorization", "Bearer $token")
                        conn.connectTimeout = 8000
                        conn.readTimeout = 8000

                        val code = conn.responseCode
                        withContext(Dispatchers.Main) {
                            if (code in 200..204) {
                                prefs.edit().remove("cancel_trip_id").apply()
                                Toast.makeText(this@HelpActivity,
                                    R.string.help_cancel_request_complete, Toast.LENGTH_SHORT).show()
                                finish()
                            } else {
                                Toast.makeText(this@HelpActivity,
                                    "취소 실패 (코드 $code)", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@HelpActivity,
                                "네트워크 오류: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.common_no, null)
            .show()
    }

    private fun showOtherInquiryDialog() {
        // 배열 안에는 String이 들어가야 하므로 getString 사용
        val options = arrayOf(
            getString(R.string.chat_inquiry),
            getString(R.string.phone_support)
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.other_inquiry)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, ChatActivity::class.java))
                    1 -> dialSupportPhone()
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun dialSupportPhone() {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:01081972581"))
        startActivity(intent)
    }
}
