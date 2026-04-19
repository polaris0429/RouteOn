package com.example.routeon

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar

class ThemeVibrationSettingsActivity : AppCompatActivity(), SensorEventListener {

    // 조도 센서
    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null

    // 조도에 따른 다크모드 전환 임계값 (lux)
    private val DARK_THRESHOLD = 20f

    // 너무 잦은 전환 방지: 마지막 전환 후 3초 이상 지나야 다시 전환
    private val switchHandler = Handler(Looper.getMainLooper())
    private var lastSwitchTime = 0L
    private val SWITCH_DEBOUNCE_MS = 3000L

    private var tvLuxValue: TextView? = null
    private var isDarkMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme_vibration_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        isDarkMode = prefs.getBoolean("dark_mode", false)

        // 조도 센서 초기화
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        tvLuxValue = findViewById(R.id.tvLuxValue)

        if (lightSensor == null) {
            tvLuxValue?.text = "현재: 조도 센서 없음"
        }

        val optionDayMode   = findViewById<LinearLayout>(R.id.optionDayMode)
        val optionNightMode = findViewById<LinearLayout>(R.id.optionNightMode)

        // ── 초기 상태 반영 ──
        val isSystemAuto = prefs.getBoolean("auto_brightness", false)
        val isLightSensorAuto = prefs.getBoolean("light_sensor_auto", false)
        val switchAutoBrightness = findViewById<Switch>(R.id.switchAutoBrightness)
        val switchLightSensor    = findViewById<Switch>(R.id.switchLightSensor)

        switchAutoBrightness.isChecked = isSystemAuto
        switchLightSensor.isChecked    = isLightSensorAuto

        // 어느 하나라도 자동이면 수동 버튼 비활성화
        val manualDisabled = isSystemAuto || isLightSensorAuto
        setManualModeEnabled(!manualDisabled, optionDayMode, optionNightMode)
        updateModeSelection(isDarkMode, optionDayMode, optionNightMode)

        // ── 주간 모드 ──
        optionDayMode.setOnClickListener {
            isDarkMode = false
            prefs.edit().putBoolean("dark_mode", false).apply()
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            updateModeSelection(false, optionDayMode, optionNightMode)
            Toast.makeText(this, "주간 모드로 변경되었습니다.", Toast.LENGTH_SHORT).show()
        }

        // ── 야간 모드 ──
        optionNightMode.setOnClickListener {
            isDarkMode = true
            prefs.edit().putBoolean("dark_mode", true).apply()
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            updateModeSelection(true, optionDayMode, optionNightMode)
            Toast.makeText(this, "야간 모드로 변경되었습니다.", Toast.LENGTH_SHORT).show()
        }

        // ── 시스템 설정 따라가기 ──
        switchAutoBrightness.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_brightness", isChecked).apply()
            if (isChecked) {
                // 조도센서 자동도 끄기 (중복 방지)
                switchLightSensor.isChecked = false
                prefs.edit().putBoolean("light_sensor_auto", false).apply()
                stopLightSensor()
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                Toast.makeText(this, "시스템 다크모드 설정을 따릅니다.", Toast.LENGTH_SHORT).show()
            } else {
                val dm = prefs.getBoolean("dark_mode", false)
                AppCompatDelegate.setDefaultNightMode(
                    if (dm) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                )
            }
            setManualModeEnabled(!isChecked && !switchLightSensor.isChecked, optionDayMode, optionNightMode)
        }

        // ── 조도 센서 자동 전환 ──
        switchLightSensor.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("light_sensor_auto", isChecked).apply()
            if (isChecked) {
                if (lightSensor == null) {
                    Toast.makeText(this, "이 기기는 조도 센서를 지원하지 않습니다.", Toast.LENGTH_SHORT).show()
                    switchLightSensor.isChecked = false
                    prefs.edit().putBoolean("light_sensor_auto", false).apply()
                    return@setOnCheckedChangeListener
                }
                // 시스템 자동도 끄기
                switchAutoBrightness.isChecked = false
                prefs.edit().putBoolean("auto_brightness", false).apply()
                startLightSensor()
                Toast.makeText(this, "조도 센서로 자동 전환합니다. (임계값: ${DARK_THRESHOLD.toInt()} lux)", Toast.LENGTH_SHORT).show()
            } else {
                stopLightSensor()
                tvLuxValue?.text = "현재: 측정 중지됨"
                val dm = prefs.getBoolean("dark_mode", false)
                AppCompatDelegate.setDefaultNightMode(
                    if (dm) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                )
            }
            setManualModeEnabled(!isChecked && !switchAutoBrightness.isChecked, optionDayMode, optionNightMode)
        }

        // 화면 진입 시 조도센서 자동이 켜져 있으면 즉시 시작
        if (isLightSensorAuto && lightSensor != null) {
            startLightSensor()
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
        }
    }

    // ── 조도 센서 시작/중지 ──
    private fun startLightSensor() {
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun stopLightSensor() {
        sensorManager.unregisterListener(this)
    }

    // ── SensorEventListener ──
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_LIGHT) return

        val lux = event.values[0]
        val prefs = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)

        // UI 업데이트
        tvLuxValue?.text = "현재: %.1f lux (%s)".format(
            lux, if (lux < DARK_THRESHOLD) "야간 모드" else "주간 모드"
        )

        // 디바운스: 마지막 전환 후 3초 이상 지나야 전환
        val now = System.currentTimeMillis()
        if (now - lastSwitchTime < SWITCH_DEBOUNCE_MS) return

        val shouldBeDark = lux < DARK_THRESHOLD
        val currentlyDark = prefs.getBoolean("dark_mode", false)

        if (shouldBeDark != currentlyDark) {
            lastSwitchTime = now
            prefs.edit().putBoolean("dark_mode", shouldBeDark).apply()
            switchHandler.post {
                AppCompatDelegate.setDefaultNightMode(
                    if (shouldBeDark) AppCompatDelegate.MODE_NIGHT_YES
                    else              AppCompatDelegate.MODE_NIGHT_NO
                )
                // UI도 갱신
                val optionDay   = findViewById<LinearLayout>(R.id.optionDayMode)
                val optionNight = findViewById<LinearLayout>(R.id.optionNightMode)
                if (optionDay != null && optionNight != null) {
                    updateModeSelection(shouldBeDark, optionDay, optionNight)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onPause() {
        super.onPause()
        // 화면에서 나가도 조도센서 자동이 켜져 있으면 MainActivity에서 처리하므로 여기서는 중지
        stopLightSensor()
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("light_sensor_auto", false) && lightSensor != null) {
            startLightSensor()
        }
    }

    // ── 수동 모드 버튼 활성/비활성 ──
    private fun setManualModeEnabled(enabled: Boolean, dayView: LinearLayout, nightView: LinearLayout) {
        dayView.alpha       = if (enabled) 1.0f else 0.4f
        nightView.alpha     = if (enabled) 1.0f else 0.4f
        dayView.isClickable   = enabled
        nightView.isClickable = enabled
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
