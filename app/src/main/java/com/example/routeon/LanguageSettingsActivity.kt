package com.example.routeon

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowInsetsControllerCompat

class LanguageSettingsActivity : AppCompatActivity() {

    private val isNightMode: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    // 언어코드 → (행 레이아웃 id, 라디오 id, 표시 이름)
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

        // 저장된 언어 체크 표시
        languageMap.forEach { (code, _, radioId) ->
            findViewById<RadioButton>(radioId).isChecked = (code == savedLang)
        }

        // 각 행 클릭 이벤트
        languageMap.forEach { (code, layoutId, radioId) ->
            findViewById<LinearLayout>(layoutId).setOnClickListener {
                val prev = prefs.getString("language", "ko") ?: "ko"
                if (code == prev) return@setOnClickListener // 같은 언어면 무시

                // 1. SharedPreferences 저장
                prefs.edit().putString("language", code).apply()

                // 2. 라디오 버튼 업데이트
                languageMap.forEach { (_, _, rid) ->
                    findViewById<RadioButton>(rid).isChecked = (rid == radioId)
                }

                // 3. AppCompatDelegate로 즉시 언어 변경 (앱 재시작 없이 적용)
                val localeList = LocaleListCompat.forLanguageTags(code)
                AppCompatDelegate.setApplicationLocales(localeList)

                Toast.makeText(this, "언어가 변경되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }
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
