package com.example.routeon

import android.content.Context
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

class LanguageSettingsActivity : AppCompatActivity() {

    private val languages = listOf(
        "ko" to R.id.langKorean,
        "en" to R.id.langEnglish,
        "ja" to R.id.langJapanese,
        "zh" to R.id.langChinese
    )

    private val radioIds = listOf(
        "ko" to R.id.radioKorean,
        "en" to R.id.radioEnglish,
        "ja" to R.id.radioJapanese,
        "zh" to R.id.radioChinese
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val prefs    = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        val savedLang = prefs.getString("language", "ko") ?: "ko"

        radioIds.forEach { (code, radioId) ->
            findViewById<RadioButton>(radioId).isChecked = (code == savedLang)
        }

        languages.forEach { (code, layoutId) ->
            val radioId = radioIds.first { it.first == code }.second
            findViewById<LinearLayout>(layoutId).setOnClickListener {
                prefs.edit().putString("language", code).apply()
                radioIds.forEach { (_, rid) ->
                    findViewById<RadioButton>(rid).isChecked = (rid == radioId)
                }
                // AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))
            }
        }
    }
}
