package com.example.routeon

import android.content.Context
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
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

        updateModeSelection(isDarkMode, optionDayMode, optionNightMode)

        optionDayMode.setOnClickListener {
            isDarkMode = false
            prefs.edit().putBoolean("dark_mode", false).apply()
            updateModeSelection(false, optionDayMode, optionNightMode)
        }

        optionNightMode.setOnClickListener {
            isDarkMode = true
            prefs.edit().putBoolean("dark_mode", true).apply()
            updateModeSelection(true, optionDayMode, optionNightMode)
        }

        val switchAutoBrightness = findViewById<Switch>(R.id.switchAutoBrightness)
        switchAutoBrightness.isChecked = prefs.getBoolean("auto_brightness", false)
        switchAutoBrightness.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_brightness", isChecked).apply()
            optionDayMode.alpha       = if (isChecked) 0.4f else 1.0f
            optionNightMode.alpha     = if (isChecked) 0.4f else 1.0f
            optionDayMode.isClickable   = !isChecked
            optionNightMode.isClickable = !isChecked
        }

        val switchVibration = findViewById<Switch>(R.id.switchVibration)
        switchVibration.isChecked = prefs.getBoolean("vibration", false)
        switchVibration.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("vibration", isChecked).apply()
        }

        val switchHaptic = findViewById<Switch>(R.id.switchHaptic)
        switchHaptic.isChecked = prefs.getBoolean("haptic", true)
        switchHaptic.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("haptic", isChecked).apply()
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
