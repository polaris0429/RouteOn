package com.example.routeon

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.content.edit
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : BaseActivity() {

    // ── 개발자 모드 활성화용 탭 카운터 ──────────────────────────────────────────
    private var devTapCount = 0
    private val DEV_TAP_TARGET = 8

    private val isNightMode: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applySystemBarsColor()

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.settings_title)
        toolbar.setNavigationOnClickListener { finish() }

        // 사용자 이름 표시
        val sharedPref = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        val username = sharedPref.getString("username", getString(R.string.settings_title))
            ?: getString(R.string.settings_title)
        findViewById<TextView>(R.id.tvUserName).text = username

        // 앱 버전 표시
        setupAppVersionRow()

        // 현재 언어 값 표시
        updateLanguageLabel()

        // 내 정보 클릭
        findViewById<LinearLayout>(R.id.profileSection).setOnClickListener {
            showEditSelectionDialog()
        }

        // 언어
        findViewById<LinearLayout>(R.id.menuLanguage).setOnClickListener {
            startActivity(Intent(this, LanguageSettingsActivity::class.java))
        }

        // 알림
        findViewById<LinearLayout>(R.id.menuNotification).setOnClickListener {
            startActivity(Intent(this, NotificationSettingsActivity::class.java))
        }

        // 화면 테마 · 진동
        findViewById<LinearLayout>(R.id.menuThemeVibration).setOnClickListener {
            startActivity(Intent(this, ThemeVibrationSettingsActivity::class.java))
        }

        // 차량 설정
        findViewById<LinearLayout>(R.id.menuVehicle).setOnClickListener {
            startActivity(Intent(this, VehicleSettingsActivity::class.java))
        }

        // 로그아웃
        findViewById<LinearLayout>(R.id.menuLogout)?.setOnClickListener {
            showLogoutDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        applySystemBarsColor()
        updateLanguageLabel()
        // 개발자 모드 토글 후 복귀 시 버전 행 상태 반영
        updateDevModeVersionLabel()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBarsColor()
    }

    // ── 앱 버전 행 설정 ──────────────────────────────────────────────────────
    private fun setupAppVersionRow() {
        val tvVersion = findViewById<TextView>(R.id.tvAppVersion)

        // versionName 읽기
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "1.0"
        }
        updateDevModeVersionLabel(versionName)

        // 8회 탭 → 개발자 모드 활성화
        findViewById<LinearLayout>(R.id.menuAppVersion).setOnClickListener {
            if (DeveloperModeManager.isEnabled(this)) {
                Toast.makeText(this, getString(R.string.settings_dev_mode_already), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            devTapCount++
            val remaining = DEV_TAP_TARGET - devTapCount

            when {
                devTapCount >= DEV_TAP_TARGET -> {
                    // 활성화!
                    devTapCount = 0
                    DeveloperModeManager.enable(this)
                    updateDevModeVersionLabel(versionName)
                    refreshDevFab()   // BaseActivity의 FAB 즉시 표시
                    Toast.makeText(
                        this,
                        getString(R.string.settings_dev_mode_enabled),
                        Toast.LENGTH_LONG
                    ).show()
                }
                remaining <= 5 -> {
                    // 남은 횟수 안내 (마지막 5회부터)
                    Toast.makeText(
                        this,
                        getString(R.string.settings_dev_mode_steps_remaining, remaining),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun updateDevModeVersionLabel(versionName: String? = null) {
        val tvVersion = findViewById<TextView>(R.id.tvAppVersion) ?: return
        val name = versionName ?: try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) { "1.0" }

        tvVersion.text = if (DeveloperModeManager.isEnabled(this)) {
            "$name 🔧"
        } else {
            name
        }
    }

    // ── 언어 레이블 ──────────────────────────────────────────────────────────
    private fun updateLanguageLabel() {
        val prefs = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        val code  = prefs.getString("language", "ko") ?: "ko"
        val label = when (code) {
            "en" -> getString(R.string.lang_english)
            "ja" -> getString(R.string.lang_japanese)
            "zh" -> getString(R.string.lang_chinese)
            else -> getString(R.string.lang_korean)
        }
        findViewById<TextView>(R.id.tvLanguageValue).text = label
    }

    // ── 정보 수정 다이얼로그 ──────────────────────────────────────────────────
    private fun showEditSelectionDialog() {
        val options = arrayOf(
            getString(R.string.settings_edit_phone),
            getString(R.string.settings_edit_password)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_edit_info_title))
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
            hint = getString(R.string.settings_edit_phone_hint)
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine()
        }
        layout.addView(etPhone)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_edit_phone_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.settings_edit_change)) { _, _ ->
                val newPhone = etPhone.text.toString().trim()
                if (newPhone.isNotEmpty()) {
                    val json = JSONObject().apply { put("phone", newPhone) }
                    updateMyInfoOnServer(json) {
                        Toast.makeText(this, getString(R.string.settings_edit_success), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.settings_cancel), null)
            .show()
    }

    private fun showEditPasswordDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }
        val etCurrentPwd = EditText(this).apply {
            hint = getString(R.string.settings_edit_password_current)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val etNewPwd = EditText(this).apply {
            hint = getString(R.string.settings_edit_password_new)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val etNewPwdConfirm = EditText(this).apply {
            hint = getString(R.string.settings_edit_password_confirm)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        layout.addView(etCurrentPwd)
        layout.addView(etNewPwd)
        layout.addView(etNewPwdConfirm)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_edit_password_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.settings_edit_change)) { _, _ ->
                val current = etCurrentPwd.text.toString()
                val newPwd  = etNewPwd.text.toString()
                val confirm = etNewPwdConfirm.text.toString()
                if (current.isEmpty() || newPwd.isEmpty() || confirm.isEmpty()) return@setPositiveButton
                if (newPwd != confirm) {
                    Toast.makeText(this, getString(R.string.settings_edit_password_mismatch), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val json = JSONObject().apply {
                    put("current_password", current)
                    put("new_password", newPwd)
                }
                updateMyInfoOnServer(json) {
                    Toast.makeText(this, getString(R.string.settings_edit_success), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.settings_cancel), null)
            .show()
    }

    private fun updateMyInfoOnServer(jsonParam: JSONObject, onSuccess: () -> Unit) {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("${Constants.BASE_URL}/auth/me")
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

    private fun applySystemBarsColor() {
        val barColor = if (isNightMode) Color.parseColor("#1E1E1E") else Color.WHITE
        window.statusBarColor     = barColor
        window.navigationBarColor = barColor
        val ic = WindowInsetsControllerCompat(window, window.decorView)
        ic.isAppearanceLightStatusBars     = !isNightMode
        ic.isAppearanceLightNavigationBars = !isNightMode
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_logout_title))
            .setMessage(getString(R.string.settings_logout_message))
            .setPositiveButton(getString(R.string.settings_logout_confirm)) { _, _ ->
                // ★ Fix: 로그아웃 시 RouteOnPrefs + ChatPrefs 모두 초기화
                getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).edit { clear() }
                getSharedPreferences("ChatPrefs", Context.MODE_PRIVATE).edit { clear() }
                ChatWebSocketService.stop(this)
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            .setNegativeButton(getString(R.string.settings_cancel), null)
            .show()
    }
}
