package com.example.routeon

// 💡 새로 추가된 차량 정보 및 연료 타입 임포트

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.routeon.databinding.ActivityMainBinding
import com.kakaomobility.knsdk.KNCarFuel
import com.kakaomobility.knsdk.KNCarType
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
import com.kakaomobility.knsdk.ui.view.KNNaviView

class MainActivity : AppCompatActivity(),
    KNGuidance_GuideStateDelegate,
    KNGuidance_LocationGuideDelegate,
    KNGuidance_RouteGuideDelegate,
    KNGuidance_SafetyGuideDelegate,
    KNGuidance_VoiceGuideDelegate,
    KNGuidance_CitsGuideDelegate {

    private lateinit var binding: ActivityMainBinding
    private lateinit var naviView: KNNaviView
    private val LOCATION_PERMISSION_REQUEST_CODE = 1000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SDK 필수 폴더 셋팅
        KNSDK.install(application, "$filesDir/knsdk")

        // 화면 그리기
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // UI 이벤트 셋팅
        setupBottomNavigation()
        setupSettingsUI()
        setupRunListUI()

        // 권한 확인 및 내비게이션 초기화 시작
        checkLocationPermission()
    }

    // 1. 하단 메뉴 전환 로직 (화면 끄고 켜기)
    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_map -> {
                    binding.runListView.visibility = View.GONE
                    binding.settingsView.visibility = View.GONE
                    true
                }
                R.id.nav_list -> {
                    binding.runListView.visibility = View.VISIBLE
                    binding.settingsView.visibility = View.GONE
                    true
                }
                R.id.nav_settings -> {
                    binding.runListView.visibility = View.GONE
                    binding.settingsView.visibility = View.VISIBLE
                    true
                }
                else -> false
            }
        }
    }

    // 2. 운행목록에서 안내 버튼 누를 때 로직
    private fun setupRunListUI() {
        binding.btnGo1.setOnClickListener {
            // 버튼을 누르면 목표 좌표를 생성하고 길찾기 시작!
            val goal = KNPOI("카카오 판교아지트", 321286, 532843, "경기 성남시 분당구 판교역로 166")
            startNavigation(goal)

            // 길찾기 시작과 동시에 하단 메뉴를 '내비'로 강제 이동시킵니다.
            binding.bottomNav.selectedItemId = R.id.nav_map
        }
    }

    // 3. 설정 탭 옵션 변경 시 내비게이션 엔진에 즉각 반영하는 로직
    private fun setupSettingsUI() {
        // 다크 모드(야간 맵) 토글
        binding.switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            if (::naviView.isInitialized) {
                naviView.useDarkMode = isChecked
            }
        }

        // 연료 타입 변경
        binding.rgFuel.setOnCheckedChangeListener { _, checkedId ->
            if (!::naviView.isInitialized) return@setOnCheckedChangeListener
            naviView.fuelType = when (checkedId) {
                R.id.rb_fuel_gasoline -> KNCarFuel.KNCarFuel_Gasoline
                R.id.rb_fuel_diesel -> KNCarFuel.KNCarFuel_Diesel
                R.id.rb_fuel_electric -> KNCarFuel.KNCarFuel_Electric
                else -> KNCarFuel.KNCarFuel_Gasoline
            }
        }

        // 차종 변경
        binding.rgCarType.setOnCheckedChangeListener { _, checkedId ->
            if (!::naviView.isInitialized) return@setOnCheckedChangeListener
            naviView.carType = when (checkedId) {
                R.id.rb_car_type_1 -> KNCarType.KNCarType_1
                R.id.rb_car_type_4 -> KNCarType.KNCarType_4
                R.id.rb_car_type_bike -> KNCarType.KNCarType_Bike
                else -> KNCarType.KNCarType_1
            }
        }
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
                if (error == null) {
                    runOnUiThread {
                        naviView = KNNaviView(this@MainActivity)
                        binding.naviContainer.addView(naviView)

                        // 앱을 켜면 일단 목적지 없이 '안전운행 모드' 지도를 띄워둡니다.
                        startSafeDriving()
                    }
                }
            }
        )
    }

    // 델리게이트 연결을 간편하게 해주는 도우미 함수
    private fun setupDelegates(guidance: KNGuidance) {
        guidance.guideStateDelegate = this
        guidance.locationGuideDelegate = this
        guidance.routeGuideDelegate = this
        guidance.safetyGuideDelegate = this
        guidance.voiceGuideDelegate = this
        guidance.citsGuideDelegate = this
        naviView.mapComponent.mapView.isVisibleTraffic = true
    }

    // 초기 상태: 목적지 없는 안전운행 모드
    private fun startSafeDriving() {
        val guidance = KNSDK.sharedGuidance()
        guidance?.apply {
            setupDelegates(this)
            naviView.initWithGuidance(
                this,
                null,
                com.kakaomobility.knsdk.KNRoutePriority.KNRoutePriority_Recommand,
                com.kakaomobility.knsdk.KNRouteAvoidOption.KNRouteAvoidOption_None.value
            )
        }
    }

    // 안내 시작 버튼 클릭 시 호출: 특정 목적지로 길찾기 모드
    private fun startNavigation(goal: KNPOI) {
        val start = KNPOI("현재위치", 314328, 544280, "") // 강남역 임시 기준

        KNSDK.makeTripWithStart(start, goal, null) { aError, aTrip ->
            if (aTrip != null) {
                val curRoutePriority = com.kakaomobility.knsdk.KNRoutePriority.KNRoutePriority_Recommand
                val curAvoidOptions = com.kakaomobility.knsdk.KNRouteAvoidOption.KNRouteAvoidOption_None.value

                aTrip.routeWithPriority(curRoutePriority, curAvoidOptions) { error, _ ->
                    if (error == null) {
                        runOnUiThread {
                            val guidance = KNSDK.sharedGuidance()
                            guidance?.apply {
                                setupDelegates(this)
                                naviView.initWithGuidance(
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
    // 💡 필수 델리게이트 인터페이스 구현부
    // =========================================================================
    override fun guidanceGuideStarted(aGuidance: KNGuidance) { if (::naviView.isInitialized) naviView.guidanceGuideStarted(aGuidance) }
    override fun guidanceCheckingRouteChange(aGuidance: KNGuidance) { if (::naviView.isInitialized) naviView.guidanceCheckingRouteChange(aGuidance) }
    override fun guidanceRouteUnchanged(aGuidance: KNGuidance) { if (::naviView.isInitialized) naviView.guidanceRouteUnchanged(aGuidance) }
    override fun guidanceRouteUnchangedWithError(aGuidnace: KNGuidance, aError: KNError) { if (::naviView.isInitialized) naviView.guidanceRouteUnchangedWithError(aGuidnace, aError) }
    override fun guidanceOutOfRoute(aGuidance: KNGuidance) { if (::naviView.isInitialized) naviView.guidanceOutOfRoute(aGuidance) }
    override fun guidanceRouteChanged(aGuidance: KNGuidance, aFromRoute: KNRoute, aFromLocation: KNLocation, aToRoute: KNRoute, aToLocation: KNLocation, aChangeReason: KNGuideRouteChangeReason) {}
    override fun guidanceGuideEnded(aGuidance: KNGuidance) { if (::naviView.isInitialized) naviView.guidanceGuideEnded(aGuidance) }
    override fun guidanceDidUpdateRoutes(aGuidance: KNGuidance, aRoutes: List<KNRoute>, aMultiRouteInfo: KNMultiRouteInfo?) { if (::naviView.isInitialized) naviView.guidanceDidUpdateRoutes(aGuidance, aRoutes, aMultiRouteInfo) }
    override fun guidanceDidUpdateIndoorRoute(aGuidance: KNGuidance, aRoute: KNRoute?) {}
    override fun guidanceDidUpdateLocation(aGuidance: KNGuidance, aLocationGuide: KNGuide_Location) { if (::naviView.isInitialized) naviView.guidanceDidUpdateLocation(aGuidance, aLocationGuide) }
    override fun guidanceDidUpdateRouteGuide(aGuidance: KNGuidance, aRouteGuide: KNGuide_Route) { if (::naviView.isInitialized) naviView.guidanceDidUpdateRouteGuide(aGuidance, aRouteGuide) }
    override fun guidanceDidUpdateSafetyGuide(aGuidance: KNGuidance, aSafetyGuide: KNGuide_Safety?) { if (::naviView.isInitialized) naviView.guidanceDidUpdateSafetyGuide(aGuidance, aSafetyGuide) }
    override fun guidanceDidUpdateAroundSafeties(aGuidance: KNGuidance, aSafeties: List<KNSafety>?) { if (::naviView.isInitialized) naviView.guidanceDidUpdateAroundSafeties(aGuidance, aSafeties) }
    override fun shouldPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice, aNewData: MutableList<ByteArray>): Boolean { return if (::naviView.isInitialized) naviView.shouldPlayVoiceGuide(aGuidance, aVoiceGuide, aNewData) else false }
    override fun willPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice) { if (::naviView.isInitialized) naviView.willPlayVoiceGuide(aGuidance, aVoiceGuide) }
    override fun didFinishPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice) { if (::naviView.isInitialized) naviView.didFinishPlayVoiceGuide(aGuidance, aVoiceGuide) }
    override fun didUpdateCitsGuide(aGuidance: KNGuidance, aCitsGuide: KNGuide_Cits) { if (::naviView.isInitialized) naviView.didUpdateCitsGuide(aGuidance, aCitsGuide) }
}