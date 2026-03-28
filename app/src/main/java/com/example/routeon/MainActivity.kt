package com.example.routeon

import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.routeon.databinding.ActivityMainBinding
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.ui.view.KNNaviView // 💡 경로를 ui.view로 정확히 맞췄습니다!

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 💡 1. applicationContext 대신 application을 넣어서 에러 해결!
        KNSDK.install(application, "$filesDir/knsdk")

        // 💡 2. 초기화 (네이티브 앱 키 입력)
        KNSDK.initializeWithAppKey(
            aAppKey = "b57bc6d46e97f480deecdd3a8e4cd754",
            aClientVersion = "1.0",
            aAppUserId = "test_user",  // 임의의 유저 키
            aLangType = com.kakaomobility.knsdk.KNLanguageType.KNLanguageType_KOREAN, // 💡 바로 이 부분입니다!
            aCompletion = { error ->
                if (error != null) {
                    Log.e("KNSDK", "🚨 초기화 실패: ${error.msg}")
                } else {
                    Log.d("KNSDK", "✅ 카카오 내비 SDK 초기화 완벽 성공!")

                    // 💡 초기화에 성공하면 안전운행 모드를 띄웁니다!
                    startSafeDriving()
                }
            }
        )
    }

    // 💡 3. 안전운행 모드 실행 함수
    private fun startSafeDriving() {
        runOnUiThread {
            val naviView = binding.naviView
            val guidance = KNSDK.sharedGuidance()

            if (guidance != null) {
                naviView.mapComponent.mapView.isVisibleTraffic = true

                naviView.initWithGuidance(
                    guidance,
                    null, // 목적지 null -> 안전운행(자유주행) 모드
                    com.kakaomobility.knsdk.KNRoutePriority.KNRoutePriority_Recommand,
                    com.kakaomobility.knsdk.KNRouteAvoidOption.KNRouteAvoidOption_None.value
                )
            } else {
                Log.e("KNSDK", "🚨 주행 엔진(Guidance)을 불러올 수 없습니다.")
            }
        }
    }
}