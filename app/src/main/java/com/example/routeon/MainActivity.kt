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
import com.kakaomobility.knsdk.guidance.knguidance.KNGuideRouteChangeReason
import com.kakaomobility.knsdk.guidance.knguidance.citsguide.KNGuide_Cits
import com.kakaomobility.knsdk.guidance.knguidance.common.KNLocation
import com.kakaomobility.knsdk.guidance.knguidance.locationguide.KNGuide_Location
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.KNGuide_Route
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.objects.KNMultiRouteInfo
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.KNGuide_Safety
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety
import com.kakaomobility.knsdk.guidance.knguidance.voiceguide.KNGuide_Voice
import com.kakaomobility.knsdk.trip.kntrip.knroute.KNRoute
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

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

        // 💡 1. KNSDK 설치를 가장 먼저 실행합니다.
        KNSDK.install(application, "$filesDir/knsdk")

        // 💡 2. 화면(뷰 바인딩)을 '먼저' 그립니다. (크기 계산 보장)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 💡 3. 화면이 다 그려진 후(post) 전체 화면(Immersive Mode) 모드를 적용합니다.
        binding.root.post {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, binding.root)

            // 상단 상태바, 하단 네비게이션 바 모두 숨김 처리
            controller.hide(WindowInsetsCompat.Type.systemBars())

            // 스와이프하면 잠깐 나타났다가 다시 숨겨지는 설정
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // 💡 4. 모든 UI 셋팅이 끝난 후 권한 확인 및 내비 시작!
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
                        binding.naviView.post {
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
    }

    // =========================================================================
    // 💡 델리게이트 구현부 (화면에 주행 정보를 실시간으로 그려주는 역할)
    // =========================================================================

    override fun guidanceGuideStarted(aGuidance: KNGuidance) {
        binding.naviView.guidanceGuideStarted(aGuidance)
    }

    override fun guidanceCheckingRouteChange(aGuidance: KNGuidance) {
        binding.naviView.guidanceCheckingRouteChange(aGuidance)
    }

    override fun guidanceRouteUnchanged(aGuidance: KNGuidance) {
        binding.naviView.guidanceRouteUnchanged(aGuidance)
    }

    override fun guidanceRouteUnchangedWithError(aGuidnace: KNGuidance, aError: KNError) {
        binding.naviView.guidanceRouteUnchangedWithError(aGuidnace, aError)
    }

    override fun guidanceOutOfRoute(aGuidance: KNGuidance) {
        binding.naviView.guidanceOutOfRoute(aGuidance)
    }

    // 이 함수는 naviView 연동 지원을 하지 않으므로 비워둡니다.
    override fun guidanceRouteChanged(aGuidance: KNGuidance, aFromRoute: KNRoute, aFromLocation: KNLocation, aToRoute: KNRoute, aToLocation: KNLocation, aChangeReason: KNGuideRouteChangeReason) {
    }

    override fun guidanceGuideEnded(aGuidance: KNGuidance) {
        binding.naviView.guidanceGuideEnded(aGuidance)
    }

    override fun guidanceDidUpdateRoutes(aGuidance: KNGuidance, aRoutes: List<KNRoute>, aMultiRouteInfo: KNMultiRouteInfo?) {
        binding.naviView.guidanceDidUpdateRoutes(aGuidance, aRoutes, aMultiRouteInfo)
    }

    // 이 함수는 naviView 연동 지원을 하지 않으므로 비워둡니다.
    override fun guidanceDidUpdateIndoorRoute(aGuidance: KNGuidance, aRoute: KNRoute?) {
    }

    override fun guidanceDidUpdateLocation(aGuidance: KNGuidance, aLocationGuide: KNGuide_Location) {
        binding.naviView.guidanceDidUpdateLocation(aGuidance, aLocationGuide)
    }

    override fun guidanceDidUpdateRouteGuide(aGuidance: KNGuidance, aRouteGuide: KNGuide_Route) {
        binding.naviView.guidanceDidUpdateRouteGuide(aGuidance, aRouteGuide)
    }

    override fun guidanceDidUpdateSafetyGuide(aGuidance: KNGuidance, aSafetyGuide: KNGuide_Safety?) {
        binding.naviView.guidanceDidUpdateSafetyGuide(aGuidance, aSafetyGuide)
    }

    override fun guidanceDidUpdateAroundSafeties(aGuidance: KNGuidance, aSafeties: List<KNSafety>?) {
        binding.naviView.guidanceDidUpdateAroundSafeties(aGuidance, aSafeties)
    }

    // 💡 에러의 원인이었던 함수! return 키워드를 넣어 값을 반환합니다.
    override fun shouldPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice, aNewData: MutableList<ByteArray>): Boolean {
        return binding.naviView.shouldPlayVoiceGuide(aGuidance, aVoiceGuide, aNewData)
    }

    override fun willPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice) {
        binding.naviView.willPlayVoiceGuide(aGuidance, aVoiceGuide)
    }

    override fun didFinishPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice) {
        binding.naviView.didFinishPlayVoiceGuide(aGuidance, aVoiceGuide)
    }

    override fun didUpdateCitsGuide(aGuidance: KNGuidance, aCitsGuide: KNGuide_Cits) {
        binding.naviView.didUpdateCitsGuide(aGuidance, aCitsGuide)
    }
}