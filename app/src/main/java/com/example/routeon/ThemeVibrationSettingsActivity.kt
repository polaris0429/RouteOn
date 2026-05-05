package com.example.routeon

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
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
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowInsetsControllerCompat

class ThemeVibrationSettingsActivity : BaseActivity(), SensorEventListener {

    private val isNightMode: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private val DARK_THRESHOLD = 20f
    private val switchHandler = Handler(Looper.getMainLooper())
    private var lastSwitchTime = 0L
    private val SWITCH_DEBOUNCE_MS = 3000L
    private var tvLuxValue: TextView? = null
    private var isDarkMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme_vibration_settings)
        applySystemBarsColor()

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        isDarkMode = prefs.getBoolean("dark_mode", false)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        tvLuxValue = findViewById(R.id.tvLuxValue)

        if (lightSensor == null) tvLuxValue?.text = "현재: 조도 센서 없음"

        val optionDayMode   = findViewById<LinearLayout>(R.id.optionDayMode)
        val optionNightMode = findViewById<LinearLayout>(R.id.optionNightMode)

        val isSystemAuto = prefs.getBoolean("auto_brightness", false)
        val isLightSensorAuto = prefs.getBoolean("light_sensor_auto", false)
        val switchAutoBrightness = findViewById<Switch>(R.id.switchAutoBrightness)
        val switchLightSensor    = findViewById<Switch>(R.id.switchLightSensor)

        switchAutoBrightness.isChecked = isSystemAuto
        switchLightSensor.isChecked    = isLightSensorAuto

        val manualDisabled = isSystemAuto || isLightSensorAuto
        setManualModeEnabled(!manualDisabled, optionDayMode, optionNightMode)
        updateModeSelection(isDarkMode, optionDayMode, optionNightMode)

        optionDayMode.setOnClickListener {
            isDarkMode = false
            prefs.edit().putBoolean("dark_mode", false).apply()
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            updateModeSelection(false, optionDayMode, optionNightMode)
            Toast.makeText(this, "주간 모드로 변경되었습니다.", Toast.LENGTH_SHORT).show()
        }

        optionNightMode.setOnClickListener {
            isDarkMode = true
            prefs.edit().putBoolean("dark_mode", true).apply()
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            updateModeSelection(true, optionDayMode, optionNightMode)
            Toast.makeText(this, "야간 모드로 변경되었습니다.", Toast.LENGTH_SHORT).show()
        }

        switchAutoBrightness.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_brightness", isChecked).apply()
            if (isChecked) {
                switchLightSensor.isChecked = false
                prefs.edit().putBoolean("light_sensor_auto", false).apply()
                stopLightSensor()
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                Toast.makeText(this, "시스템 다크모드 설정을 따릅니다.", Toast.LENGTH_SHORT).show()
            } else {
                val dm = prefs.getBoolean("dark_mode", false)
                AppCompatDelegate.setDefaultNightMode(if (dm) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            }
            setManualModeEnabled(!isChecked && !switchLightSensor.isChecked, optionDayMode, optionNightMode)
        }

        switchLightSensor.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("light_sensor_auto", isChecked).apply()
            if (isChecked) {
                if (lightSensor == null) {
                    Toast.makeText(this, "이 기기는 조도 센서를 지원하지 않습니다.", Toast.LENGTH_SHORT).show()
                    switchLightSensor.isChecked = false
                    prefs.edit().putBoolean("light_sensor_auto", false).apply()
                    return@setOnCheckedChangeListener
                }
                switchAutoBrightness.isChecked = false
                prefs.edit().putBoolean("auto_brightness", false).apply()
                startLightSensor()
                Toast.makeText(this, "조도 센서로 자동 전환합니다. (임계값: ${DARK_THRESHOLD.toInt()} lux)", Toast.LENGTH_SHORT).show()
            } else {
                stopLightSensor()
                tvLuxValue?.text = "현재: 측정 중지됨"
                val dm = prefs.getBoolean("dark_mode", false)
                AppCompatDelegate.setDefaultNightMode(if (dm) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            }
            setManualModeEnabled(!isChecked && !switchAutoBrightness.isChecked, optionDayMode, optionNightMode)
        }

        if (isLightSensorAuto && lightSensor != null) startLightSensor()

        val switchVibration = findViewById<Switch>(R.id.switchVibration)
        switchVibration.isChecked = prefs.getBoolean("vibration", false)
        switchVibration.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("vibration", isChecked).apply()
            Toast.makeText(this, if (isChecked) "진동 안내가 켜졌습니다." else "진동 안내가 꺼졌습니다.", Toast.LENGTH_SHORT).show()
        }

        val switchHaptic = findViewById<Switch>(R.id.switchHaptic)
        switchHaptic.isChecked = prefs.getBoolean("haptic", true)
        switchHaptic.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("haptic", isChecked).apply()
        }
    }

    private fun startLightSensor() {
        lightSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    private fun stopLightSensor() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_LIGHT) return
        val lux = event.values[0]
        val prefs = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        tvLuxValue?.text = "현재: %.1f lux (%s)".format(lux, if (lux < DARK_THRESHOLD) "야간 모드" else "주간 모드")
        val now = System.currentTimeMillis()
        if (now - lastSwitchTime < SWITCH_DEBOUNCE_MS) return
        val shouldBeDark = lux < DARK_THRESHOLD
        val currentlyDark = prefs.getBoolean("dark_mode", false)
        if (shouldBeDark != currentlyDark) {
            lastSwitchTime = now
            prefs.edit().putBoolean("dark_mode", shouldBeDark).apply()
            switchHandler.post {
                AppCompatDelegate.setDefaultNightMode(if (shouldBeDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
                val optionDay   = findViewById<LinearLayout>(R.id.optionDayMode)
                val optionNight = findViewById<LinearLayout>(R.id.optionNightMode)
                if (optionDay != null && optionNight != null) updateModeSelection(shouldBeDark, optionDay, optionNight)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onPause() {
        super.onPause()
        stopLightSensor()
    }

    override fun onResume() {
        super.onResume()
        applySystemBarsColor()
        val prefs = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("light_sensor_auto", false) && lightSensor != null) startLightSensor()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBarsColor()
    }

    private fun applySystemBarsColor() {
        val barColor = if (isNightMode) Color.parseColor("#1E1E1E") else Color.WHITE
        window.statusBarColor     = barColor
        window.navigationBarColor = barColor
        val ic = WindowInsetsControllerCompat(window, window.decorView)
        ic.isAppearanceLightStatusBars     = !isNightMode
        ic.isAppearanceLightNavigationBars = !isNightMode
    }

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
