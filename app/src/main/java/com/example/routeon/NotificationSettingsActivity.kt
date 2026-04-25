package com.example.routeon

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowInsetsControllerCompat

class NotificationSettingsActivity : AppCompatActivity() {

    private val isNightMode: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_settings)
        applySystemBarsColor()

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)

        val switchAppNotification   = findViewById<Switch>(R.id.switchAppNotification)
        val switchTrafficNotification = findViewById<Switch>(R.id.switchTrafficNotification)
        val switchSpeedCamera       = findViewById<Switch>(R.id.switchSpeedCamera)
        val switchVoiceGuide        = findViewById<Switch>(R.id.switchVoiceGuide)
        val seekbarVolume           = findViewById<SeekBar>(R.id.seekbarVolume)
        val tvVolumeValue           = findViewById<TextView>(R.id.tvVolumeValue)

        // 저장된 값 복원
        switchAppNotification.isChecked    = prefs.getBoolean("notif_app", true)
        switchTrafficNotification.isChecked = prefs.getBoolean("notif_traffic", true)
        switchSpeedCamera.isChecked        = prefs.getBoolean("notif_speed_cam", true)
        switchVoiceGuide.isChecked         = prefs.getBoolean("voice_guide", true)
        val savedVolume = prefs.getInt("voice_volume", 100)
        seekbarVolume.progress = savedVolume
        tvVolumeValue.text = "$savedVolume%"

        switchAppNotification.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("notif_app", isChecked).apply()
        }
        switchTrafficNotification.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("notif_traffic", isChecked).apply()
        }
        switchSpeedCamera.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("notif_speed_cam", isChecked).apply()
        }

        // 음성 안내 ON/OFF → 슬라이더 활성화
        switchVoiceGuide.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("voice_guide", isChecked).apply()
            seekbarVolume.isEnabled = isChecked
            seekbarVolume.alpha = if (isChecked) 1.0f else 0.4f
        }

        // 볼륨 슬라이더 (KNNaviView.sndVolume = progress / 100f)
        seekbarVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvVolumeValue.text = "$progress%"
                if (fromUser) prefs.edit().putInt("voice_volume", progress).apply()
                // KNNaviView.sndVolume = progress / 100f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    override fun onResume() {
        super.onResume()
        applySystemBarsColor()
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
}
