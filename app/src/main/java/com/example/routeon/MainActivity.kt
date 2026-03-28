package com.example.routeon

// 💡 델리게이트 임포트 (Alt+Enter로 자동완성 될 것입니다)
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.routeon.databinding.ActivityMainBinding
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.common.objects.KNError
import com.kakaomobility.knsdk.common.objects.KNPOI
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_CitsGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_GuideStateDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_LocationGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_RouteGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_SafetyGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_VoiceGuideDelegate

class MainActivity : AppCompatActivity(),
    KNGuidance_GuideStateDelegate,
    KNGuidance_LocationGuideDelegate,
    KNGuidance_RouteGuideDelegate,
    KNGuidance_SafetyGuideDelegate,
    KNGuidance_VoiceGuideDelegate,
    KNGuidance_CitsGuideDelegate {

    private lateinit var binding: ActivityMainBinding
    private val LOCATION_PERMISSION_REQUEST_CODE = 1000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KNSDK.install(application, "$filesDir/knsdk")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkLocationPermission()
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        } else {
            initKakaoNaviSDK()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initKakaoNaviSDK()
        } else {
            Toast.makeText(this, "위치 권한이 필요합니다.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun initKakaoNaviSDK() {
        KNSDK.initializeWithAppKey(
            aAppKey = "b57bc6d46e97f480deecdd3a8e4cd754",
            aClientVersion = "1.0",
            aAppUserId = "test_user",
            aLangType = com.kakaomobility.knsdk.KNLanguageType.KNLanguageType_KOREAN,
            aCompletion = { error ->
                if (error != null) {
                    Log.e("KNSDK", "🚨 초기화 실패: ${error.msg}")
                } else {
                    Log.d("KNSDK", "✅ 카카오 SDK 인증 성공! 길안내를 시작합니다.")
                    startNavigation()
                }
            }
        )
    }

    private fun startNavigation() {
        val start = KNPOI("강남역", 314328, 544280, "서울 강남구 강남대로 396")
        val goal = KNPOI("카카오 판교아지트", 321286, 532843, "경기 성남시 분당구 판교역로 166")

        KNSDK.makeTripWithStart(start, goal, null) { aError, aTrip ->
            if (aError != null || aTrip == null) {
                Log.e("KNSDK", "🚨 경로 생성 실패: ${aError?.msg}")
                return@makeTripWithStart
            }

            val curRoutePriority = com.kakaomobility.knsdk.KNRoutePriority.KNRoutePriority_Recommand
            val curAvoidOptions = com.kakaomobility.knsdk.KNRouteAvoidOption.KNRouteAvoidOption_None.value

            aTrip.routeWithPriority(curRoutePriority, curAvoidOptions) { error, _ ->
                if (error != null) {
                    Log.e("KNSDK", "🚨 경로 요청 실패: ${error.msg}")
                } else {
                    runOnUiThread {
                        val guidance = KNSDK.sharedGuidance()
                        guidance?.apply {
                            guideStateDelegate = this@MainActivity
                            locationGuideDelegate = this@MainActivity
                            routeGuideDelegate = this@MainActivity
                            safetyGuideDelegate = this@MainActivity
                            voiceGuideDelegate = this@MainActivity
                            citsGuideDelegate = this@MainActivity

                            binding.naviView.mapComponent.mapView.isVisibleTraffic = true

                            binding.naviView.initWithGuidance(
                                this,
                                aTrip,
                                curRoutePriority,
                                curAvoidOptions
                            )
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // 💡 5. 최신 SDK(1.12.7) 규격에 완벽하게 맞춘 델리게이트 구현부
    // =========================================================================

    override fun guidanceGuideStarted(aGuidance: KNGuidance) { binding.naviView.guidanceGuideStarted(aGuidance) }
    override fun guidanceCheckingRouteChange(aGuidance: KNGuidance) { binding.naviView.guidanceCheckingRouteChange(aGuidance) }
    override fun guidanceRouteUnchanged(aGuidance: KNGuidance) { binding.naviView.guidanceRouteUnchanged(aGuidance) }
    override fun guidanceRouteUnchangedWithError(aGuidance: KNGuidance, aError: KNError) { binding.naviView.guidanceRouteUnchangedWithError(aGuidance, aError) }
    override fun guidanceOutOfRoute(aGuidance: KNGuidance) { binding.naviView.guidanceOutOfRoute(aGuidance) }
    override fun guidanceGuideEnded(aGuidance: KNGuidance) { binding.naviView.guidanceGuideEnded(aGuidance) }

    // 파라미터가 최신화된 함수들 (naviView 내부 함수와 파라미터가 안 맞으면 에러나므로 주석처리 등 안전장치 적용)
    override fun guidanceRouteChanged(aGuidance: KNGuidance, aFromRoute: com.kakaomobility.knsdk.common.objects.KNRoute, aFromLocation: com.kakaomobility.knsdk.common.objects.KNLocation, aToRoute: com.kakaomobility.knsdk.common.objects.KNRoute, aToLocation: com.kakaomobility.knsdk.common.objects.KNLocation, aChangeReason: com.kakaomobility.knsdk.guidance.KNGuideRouteChangeReason) {
        // UI 반영
    }

    override fun guidanceDidUpdateRoutes(aGuidance: KNGuidance, aRoutes: List<com.kakaomobility.knsdk.common.objects.KNRoute>, aMultiRouteInfo: com.kakaomobility.knsdk.guidance.routeinfo.KNMultiRouteInfo?) {
        // UI 반영
    }

    override fun guidanceDidUpdateLocation(aGuidance: KNGuidance, aLocationGuide: com.kakaomobility.knsdk.guidance.routeinfo.KNGuide_Location) {
        binding.naviView.guidanceDidUpdateLocation(aGuidance, aLocationGuide)
    }

    override fun guidanceDidUpdateRouteGuide(aGuidance: KNGuidance, aRouteGuide: com.kakaomobility.knsdk.guidance.routeinfo.KNGuide_Route) {
        binding.naviView.guidanceDidUpdateRouteGuide(aGuidance, aRouteGuide)
    }

    override fun guidanceDidUpdateSafetyGuide(aGuidance: KNGuidance, aSafetyGuide: com.kakaomobility.knsdk.guidance.routeinfo.KNGuide_Safety?) {
        binding.naviView.guidanceDidUpdateSafetyGuide(aGuidance, aSafetyGuide)
    }

    override fun guidanceDidUpdateAroundSafeties(aGuidance: KNGuidance, aSafeties: List<com.kakaomobility.knsdk.guidance.routeinfo.KNSafety>?) {
        binding.naviView.guidanceDidUpdateAroundSafeties(aGuidance, aSafeties)
    }

    override fun shouldPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: com.kakaomobility.knsdk.guidance.routeinfo.KNGuide_Voice, aNewData: MutableList<ByteArray>): Boolean {
        return binding.naviView.shouldPlayVoiceGuide(aGuidance, aVoiceGuide, aNewData)
    }

    override fun willPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: com.kakaomobility.knsdk.guidance.routeinfo.KNGuide_Voice) {
        binding.naviView.willPlayVoiceGuide(aGuidance, aVoiceGuide)
    }

    override fun didFinishPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: com.kakaomobility.knsdk.guidance.routeinfo.KNGuide_Voice) {
        binding.naviView.didFinishPlayVoiceGuide(aGuidance, aVoiceGuide)
    }

    override fun didUpdateCitsGuide(aGuidance: KNGuidance, aCitsGuide: com.kakaomobility.knsdk.guidance.routeinfo.KNGuide_Cits) {
        binding.naviView.didUpdateCitsGuide(aGuidance, aCitsGuide)
    }

    // 💡 최신 버전에 새롭게 추가된 필수 함수
    override fun guidanceDidUpdateIndoorRoute(aGuidance: KNGuidance, aRoute: com.kakaomobility.knsdk.common.objects.KNRoute?) {
        // 실내 경로 업데이트 시 호출됨
    }
}