package com.example.routeon

import android.content.Context
import android.content.Intent
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

    // 언어코드 → (행 레이아웃 id, 라디오 id)
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
                if (code == prev) return@setOnClickListener

                // 1. SharedPreferences 저장
                prefs.edit().putString("language", code).apply()

                // 2. 라디오 버튼 즉시 업데이트
                languageMap.forEach { (_, _, rid) ->
                    findViewById<RadioButton>(rid).isChecked = (rid == radioId)
                }

                // 3. AppCompatDelegate로 로케일 변경
                val localeList = LocaleListCompat.forLanguageTags(code)
                AppCompatDelegate.setApplicationLocales(localeList)

                // 4. Toast는 변경 전 언어로 표시 (변경 직후라 아직 이전 locale)
                Toast.makeText(this, getString(R.string.language_changed_toast), Toast.LENGTH_SHORT).show()

                // 5. MainActivity부터 스택 전체를 재시작해 모든 화면에 새 언어 적용
                restartToMain()
            }
        }
    }

    /**
     * MainActivity를 루트로 새 태스크를 시작해 모든 Activity를 새 locale로 재생성합니다.
     * FLAG_ACTIVITY_CLEAR_TASK : 기존 백스택 전체 제거
     * FLAG_ACTIVITY_NEW_TASK   : 새 태스크로 시작 (CLEAR_TASK와 함께 사용 필수)
     */
    private fun restartToMain() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        // 전환 애니메이션 없이 교체해 재시작처럼 보이게
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
