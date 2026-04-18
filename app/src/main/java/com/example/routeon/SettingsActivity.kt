package com.example.routeon

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // 사용자 정보 표시
        val sharedPref = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        val username = sharedPref.getString("username", "사용자") ?: "사용자"
        val tvUserName = findViewById<TextView>(R.id.tvUserName)
        tvUserName.text = username

        // 내 정보 영역 클릭 → 정보 변경 다이얼로그
        val profileSection = findViewById<LinearLayout>(R.id.profileSection)
        profileSection.setOnClickListener { showEditSelectionDialog() }

        // 언어
        val menuLanguage = findViewById<LinearLayout>(R.id.menuLanguage)
        menuLanguage.setOnClickListener {
            startActivity(Intent(this, LanguageSettingsActivity::class.java))
        }

        // 알림
        val menuNotification = findViewById<LinearLayout>(R.id.menuNotification)
        menuNotification.setOnClickListener {
            startActivity(Intent(this, NotificationSettingsActivity::class.java))
        }

        // 화면 테마 · 진동
        val menuThemeVibration = findViewById<LinearLayout>(R.id.menuThemeVibration)
        menuThemeVibration.setOnClickListener {
            startActivity(Intent(this, ThemeVibrationSettingsActivity::class.java))
        }

        // 차량 설정
        val menuVehicle = findViewById<LinearLayout>(R.id.menuVehicle)
        menuVehicle.setOnClickListener {
            startActivity(Intent(this, VehicleSettingsActivity::class.java))
        }

        // 로그아웃
        val menuLogout = findViewById<LinearLayout>(R.id.menuLogout)
        menuLogout?.setOnClickListener { showLogoutDialog() }
    }

    // ── 정보 수정 선택 다이얼로그 ──
    private fun showEditSelectionDialog() {
        val options = arrayOf("휴대폰 번호 변경", "비밀번호 변경")
        AlertDialog.Builder(this)
            .setTitle("내 정보 변경")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditPhoneDialog()
                    1 -> showEditPasswordDialog()
                }
            }
            .show()
    }

    private fun showEditPhoneDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }
        val etPhone = EditText(this).apply {
            hint = "새 휴대폰 번호 (예: 010-1234-5678)"
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine()
        }
        layout.addView(etPhone)
        AlertDialog.Builder(this)
            .setTitle("휴대폰 번호 변경")
            .setView(layout)
            .setPositiveButton("변경하기") { _, _ ->
                val newPhone = etPhone.text.toString().trim()
                if (newPhone.isNotEmpty()) {
                    val json = JSONObject().apply { put("phone", newPhone) }
                    updateMyInfoOnServer(json) {
                        Toast.makeText(this, "변경 완료!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showEditPasswordDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }
        val etCurrentPwd = EditText(this).apply {
            hint = "현재 비밀번호"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val etNewPwd = EditText(this).apply {
            hint = "새 비밀번호"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val etNewPwdConfirm = EditText(this).apply {
            hint = "새 비밀번호 확인"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        layout.addView(etCurrentPwd)
        layout.addView(etNewPwd)
        layout.addView(etNewPwdConfirm)

        AlertDialog.Builder(this)
            .setTitle("비밀번호 변경")
            .setView(layout)
            .setPositiveButton("변경하기") { _, _ ->
                val current = etCurrentPwd.text.toString()
                val newPwd  = etNewPwd.text.toString()
                val confirm = etNewPwdConfirm.text.toString()
                if (current.isEmpty() || newPwd.isEmpty() || confirm.isEmpty()) return@setPositiveButton
                if (newPwd != confirm) {
                    Toast.makeText(this, "새 비밀번호 불일치", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val json = JSONObject().apply {
                    put("current_password", current)
                    put("new_password", newPwd)
                }
                updateMyInfoOnServer(json) {
                    Toast.makeText(this, "변경 완료!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun updateMyInfoOnServer(jsonParam: JSONObject, onSuccess: () -> Unit) {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("http://swc.ddns.net:8000/auth/me")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use { it.write(jsonParam.toString()) }
                if (conn.responseCode == 200 || conn.responseCode == 204) {
                    withContext(Dispatchers.Main) { onSuccess() }
                }
            } catch (e: Exception) { }
        }
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle("로그아웃")
            .setMessage("정말 로그아웃 하시겠습니까?")
            .setPositiveButton("로그아웃") { _, _ ->
                getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).edit { clear() }
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
