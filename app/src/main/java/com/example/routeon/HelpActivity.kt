package com.example.routeon

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Log
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class HelpActivity : BaseActivity() {

    private var cancelTripId: String? = null

    private val isNightMode: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        applySystemBarsColor()

        // 배차 거절 버튼에서 Intent로 tripId 수신
        cancelTripId = intent.getStringExtra("cancel_trip_id")

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

    // =========================================================================
    // 취소 사유 선택 다이얼로그
    // =========================================================================

    private fun showCancelReasonDialog() {
        val reasons = arrayOf(
            getString(R.string.cancel_reason_vehicle_issue),
            getString(R.string.cancel_reason_health_issue),
            getString(R.string.cancel_reason_accident),
            getString(R.string.cancel_reason_other)
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.cancel_reason_select)
            .setItems(reasons) { _, which ->
                when (which) {
                    3 -> showDirectInputDialog()        // "기타" 선택 → 직접 입력
                    else -> confirmCancelRequest(reasons[which])
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showDirectInputDialog() {
        val input = EditText(this).apply {
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
                    confirmCancelRequest(reason)
                } else {
                    Toast.makeText(this, R.string.help_direct_input_empty_toast, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    // =========================================================================
    // 취소 요청 확인 → POST /trips/{id}/cancel-request {"reason":"..."}
    //
    // v1.0.76: 기사 앱은 즉시 취소(PATCH /status) 대신 취소 요청을 서버에 전송하고
    // 관리자 승인을 기다린다. 승인 시 서버가 WS trip.cancelled 를 브로드캐스트한다.
    // =========================================================================

    private fun confirmCancelRequest(reason: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dispatch_cancel)
            .setMessage(getString(R.string.help_cancel_confirm_message, reason))
            .setPositiveButton(R.string.common_yes) { _, _ ->
                val token = getSharedPreferences("RouteOnPrefs", MODE_PRIVATE)
                    .getString("access_token", null)

                if (token.isNullOrEmpty()) {
                    Toast.makeText(this, "로그인 정보가 없습니다.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (!cancelTripId.isNullOrEmpty()) {
                    // 배차 거절 버튼에서 Intent로 받은 tripId → 즉시 cancel-request 전송
                    sendCancelRequest(token, cancelTripId!!, reason)
                } else {
                    // 일반 도움말 진입: 현재 활성 운행 조회 후 cancel-request 전송
                    fetchActiveTripAndSendRequest(token, reason)
                }
            }
            .setNegativeButton(R.string.common_no, null)
            .show()
    }

    /**
     * POST /trips/{tripId}/cancel-request
     * Body: {"reason": "..."}
     *
     * 성공(200/201/202): 서버가 관리자에게 WS trip.cancel_requested 브로드캐스트
     * → 관리자가 승인하면 WS trip.cancelled 수신 → MainActivity가 처리
     */
    private fun sendCancelRequest(token: String, tripId: String, reason: String) {
        Log.d("CancelRequest", "▶ POST /trips/$tripId/cancel-request reason='$reason'")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("${Constants.BASE_URL}/trips/$tripId/cancel-request")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 8000
                conn.readTimeout    = 8000
                conn.doOutput       = true

                val body = JSONObject().apply { put("reason", reason) }.toString()
                OutputStreamWriter(conn.outputStream).use { it.write(body) }

                val code = conn.responseCode
                Log.d("CancelRequest", "◀ HTTP $code")

                withContext(Dispatchers.Main) {
                    when (code) {
                        in 200..204 -> {
                            cancelTripId = null
                            // 관리자 승인 전까지는 실제 취소가 아님 → 안내 메시지로 구분
                            Toast.makeText(
                                this@HelpActivity,
                                getString(R.string.help_cancel_request_complete),
                                Toast.LENGTH_LONG
                            ).show()
                            finish()
                        }
                        409 -> {
                            // 이미 취소 요청 중인 경우
                            Toast.makeText(
                                this@HelpActivity,
                                "이미 취소 요청이 접수되어 있습니다. 관리자 승인을 기다려 주세요.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        404 -> {
                            Toast.makeText(
                                this@HelpActivity,
                                "운행 정보를 찾을 수 없습니다.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        else -> {
                            val errBody = try {
                                conn.errorStream?.bufferedReader()?.readText() ?: ""
                            } catch (_: Exception) { "" }
                            Log.e("CancelRequest", "❌ 오류 응답: $errBody")
                            Toast.makeText(
                                this@HelpActivity,
                                "요청 실패 (코드 $code)",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CancelRequest", "❌ 네트워크 오류: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@HelpActivity,
                        "네트워크 오류: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * tripId 없이 일반 도움말에서 진입한 경우:
     * GET /trips?status=in_progress, scheduled 순으로 현재 활성 운행 조회 후
     * cancel-request 전송
     */
    private fun fetchActiveTripAndSendRequest(token: String, reason: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                var foundTripId: String? = null
                for (status in listOf("in_progress", "scheduled")) {
                    if (!foundTripId.isNullOrEmpty()) break
                    val conn = URL("${Constants.BASE_URL}/trips?status=$status")
                        .openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Authorization", "Bearer $token")
                    conn.connectTimeout = 8000
                    conn.readTimeout    = 8000
                    if (conn.responseCode == 200) {
                        val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                        if (arr.length() > 0) {
                            foundTripId = arr.getJSONObject(0).optString("id", "")
                                .takeIf { it.isNotEmpty() }
                        }
                    }
                }

                if (!foundTripId.isNullOrEmpty()) {
                    sendCancelRequest(token, foundTripId, reason)
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@HelpActivity,
                            "취소 요청할 진행 중인 운행이 없습니다.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("CancelRequest", "❌ 운행 조회 오류: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@HelpActivity,
                        "네트워크 오류: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // =========================================================================
    // 기타 문의
    // =========================================================================

    private fun showOtherInquiryDialog() {
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
