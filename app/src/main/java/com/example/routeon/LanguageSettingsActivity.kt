package com.example.routeon

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowInsetsControllerCompat

class LanguageSettingsActivity : BaseActivity() {

    private val isNightMode: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    private val languageMap = listOf(
        Triple("ko", R.id.langKorean,   R.id.radioKorean),
        Triple("en", R.id.langEnglish,  R.id.radioEnglish),
        Triple("ja", R.id.langJapanese, R.id.radioJapanese),
        Triple("zh", R.id.langChinese,  R.id.radioChinese)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language_settings)
        applySystemBarsColor()

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val prefs     = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        val savedLang = prefs.getString("language", "ko") ?: "ko"

        languageMap.forEach { (code, _, radioId) ->
            findViewById<RadioButton>(radioId).isChecked = (code == savedLang)
        }

        languageMap.forEach { (code, layoutId, radioId) ->
            findViewById<LinearLayout>(layoutId).setOnClickListener {
                val prev = prefs.getString("language", "ko") ?: "ko"
                if (code == prev) return@setOnClickListener

                prefs.edit().putString("language", code).apply()

                languageMap.forEach { (_, _, rid) ->
                    findViewById<RadioButton>(rid).isChecked = (rid == radioId)
                }

                val localeList = LocaleListCompat.forLanguageTags(code)
                AppCompatDelegate.setApplicationLocales(localeList)

                Toast.makeText(this, getString(R.string.language_changed_toast), Toast.LENGTH_SHORT).show()

                restartToMain()
            }
        }
    }

    private fun restartToMain() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        overridePendingTransition(0, 0)
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
