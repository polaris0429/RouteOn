package com.example.routeon

import android.content.Context
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar

class ThemeVibrationSettingsActivity : AppCompatActivity() {

    private var isDarkMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme_vibration_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        isDarkMode = prefs.getBoolean("dark_mode", false)

        val optionDayMode   = findViewById<LinearLayout>(R.id.optionDayMode)
        val optionNightMode = findViewById<LinearLayout>(R.id.optionNightMode)

        // 자동 밝기 상태 먼저 확인 (자동이면 수동 버튼 비활성화)
        val switchAutoBrightness = findViewById<Switch>(R.id.switchAutoBrightness)
        val isAuto = prefs.getBoolean("auto_brightness", false)
        switchAutoBrightness.isChecked = isAuto
        if (isAuto) {
            optionDayMode.alpha = 0.4f;   optionNightMode.alpha = 0.4f
            optionDayMode.isClickable = false; optionNightMode.isClickable = false
        }

        updateModeSelection(isDarkMode, optionDayMode, optionNightMode)

        // ── 주간 모드 ──
        optionDayMode.setOnClickListener {
            isDarkMode = false
            prefs.edit().putBoolean("dark_mode", false).apply()
            // 즉시 앱 전체에 주간 모드 적용
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            updateModeSelection(false, optionDayMode, optionNightMode)
            Toast.makeText(this, "주간 모드로 변경되었습니다.", Toast.LENGTH_SHORT).show()
        }

        // ── 야간 모드 ──
        optionNightMode.setOnClickListener {
            isDarkMode = true
            prefs.edit().putBoolean("dark_mode", true).apply()
            // 즉시 앱 전체에 야간 모드 적용
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            updateModeSelection(true, optionDayMode, optionNightMode)
            Toast.makeText(this, "야간 모드로 변경되었습니다.", Toast.LENGTH_SHORT).show()
        }

        // ── 자동 밝기 (시스템 다크모드 따라가기) ──
        switchAutoBrightness.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_brightness", isChecked).apply()
            if (isChecked) {
                // 시스템 설정을 따라가도록
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                prefs.edit().putBoolean("dark_mode", false).apply()
                Toast.makeText(this, "시스템 설정에 따라 자동 전환됩니다.", Toast.LENGTH_SHORT).show()
            } else {
                // 다시 수동 모드 (저장된 값 복원)
                val dm = prefs.getBoolean("dark_mode", false)
                AppCompatDelegate.setDefaultNightMode(
                    if (dm) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                )
            }
            optionDayMode.alpha       = if (isChecked) 0.4f else 1.0f
            optionNightMode.alpha     = if (isChecked) 0.4f else 1.0f
            optionDayMode.isClickable   = !isChecked
            optionNightMode.isClickable = !isChecked
        }

        // ── 안내 진동 ──
        val switchVibration = findViewById<Switch>(R.id.switchVibration)
        switchVibration.isChecked = prefs.getBoolean("vibration", false)
        switchVibration.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("vibration", isChecked).apply()
            Toast.makeText(this,
                if (isChecked) "진동 안내가 켜졌습니다." else "진동 안내가 꺼졌습니다.",
                Toast.LENGTH_SHORT).show()
        }

        // ── 터치 햅틱 ──
        val switchHaptic = findViewById<Switch>(R.id.switchHaptic)
        switchHaptic.isChecked = prefs.getBoolean("haptic", true)
        switchHaptic.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("haptic", isChecked).apply()
            // 햅틱 피드백은 뷰 단에서 isHapticFeedbackEnabled로 제어됨
            // (앱 전체 버튼에 적용하려면 BaseActivity 등에서 처리)
        }
    }

    private fun updateModeSelection(darkMode: Boolean, dayView: LinearLayout, nightView: LinearLayout) {
        if (darkMode) {
            dayView.setBackgroundResource(R.drawable.theme_option_unselected)
            nightView.setBackgroundResource(R.drawable.theme_option_selected)
        } else {
            dayView.setBackgroundResource(R.drawable.theme_option_selected)
            nightView.setBackgroundResource(R.drawable.theme_option_unselected)
        }
    }
}
