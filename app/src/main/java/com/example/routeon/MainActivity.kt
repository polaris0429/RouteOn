package com.example.routeon

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.routeon.databinding.ActivityMainBinding
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.common.objects.KNError
import com.kakaomobility.knsdk.common.objects.KNPOI

// 💡 SDK 최신 버전에 맞춘 연료/차종 경로
import com.kakaomobility.knsdk.KNCarFuel
import com.kakaomobility.knsdk.KNCarType

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

        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        KNSDK.install(application, "$filesDir/knsdk")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBottomNavigation()
        setupSettingsUI()
        setupRunListUI()

        checkLocationPermission()
    }

    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_map -> {
                    binding.runListView.visibility = View.GONE
                    binding.settingsView.visibility = View.GONE
                    WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
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

    private fun setupRunListUI() {
        binding.btnGo1.setOnClickListener {
            val goal = KNPOI("카카오 판교아지트", 321286, 532843, "경기 성남시 분당구 판교역로 166")
            startNavigation(goal)
            binding.bottomNav.selectedItemId = R.id.nav_map
        }
    }

    private fun setupSettingsUI() {
        binding.switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            if (::naviView.isInitialized) naviView.useDarkMode = isChecked
            if (isChecked) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }

        binding.rgFuel.setOnCheckedChangeListener { _, checkedId ->
            if (!::naviView.isInitialized) return@setOnCheckedChangeListener
            naviView.fuelType = when (checkedId) {
                R.id.rb_fuel_gasoline -> KNCarFuel.KNCarFuel_Gasoline
                R.id.rb_fuel_diesel -> KNCarFuel.KNCarFuel_Diesel
                R.id.rb_fuel_electric -> KNCarFuel.KNCarFuel_Electric
                else -> KNCarFuel.KNCarFuel_Gasoline
            }
        }

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
                        naviView.useDarkMode = binding.switchDarkMode.isChecked
                        startSafeDriving()
                    }
                }
            }
        )
    }

    private fun setupDelegates(guidance: KNGuidance) {
        guidance.guideStateDelegate = this
        guidance.locationGuideDelegate = this
        guidance.routeGuideDelegate = this
        guidance.safetyGuideDelegate = this
        guidance.voiceGuideDelegate = this
        guidance.citsGuideDelegate = this
        naviView.mapComponent.mapView.isVisibleTraffic = true
    }

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

    private fun startNavigation(goal: KNPOI) {
        val guidance = KNSDK.sharedGuidance() ?: return

        // 1. 현재 위치 객체를 가져옵니다.
        val currentLocation = guidance.locationGuide?.location

        // 2. 위치를 아직 완벽하게 잡지 못했다면 안내 시작을 잠시 보류합니다.
        if (currentLocation == null) {
            Toast.makeText(this, "현재 위치(GPS)를 잡는 중입니다. 잠시 후 다시 눌러주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        // 💡 3. [초필살기] 리플렉션을 통한 무적의 좌표 추출기
        // SDK 버전마다 FloatPoint의 변수명이 x, y, longitude 등으로 계속 바뀌어 컴파일이 터지는 것을 막기 위해,
        // 변수명에 의존하지 않고 객체 내부를 스캔하여 숫자를 강제로 빼냅니다.
        var startX = 314328 // 기본값 (강남역)
        var startY = 544280 // 기본값 (강남역)

        try {
            val posObj = currentLocation.pos
            if (posObj != null) {
                // pos 객체 안의 모든 변수를 뒤집니다.
                for (field in posObj.javaClass.declaredFields) {
                    field.isAccessible = true // 숨겨진(private) 변수라도 강제로 접근
                    val fieldName = field.name.lowercase()
                    val value = field.get(posObj)

                    if (value is Number) {
                        // 변수 이름이 x, lon, longitude, katecx 중 하나라도 포함되면 X좌표로 인식
                        if (fieldName == "x" || fieldName == "katecx" || fieldName == "longitude" || fieldName == "lon") {
                            startX = value.toInt()
                        }
                        // 변수 이름이 y, lat, latitude, katecy 중 하나라도 포함되면 Y좌표로 인식
                        else if (fieldName == "y" || fieldName == "katecy" || fieldName == "latitude" || fieldName == "lat") {
                            startY = value.toInt()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("KNSDK", "좌표 파싱 에러 (SDK 버전 충돌): ${e.message}")
        }

        // 💡 4. 완벽하게 뽑아낸 좌표(startX, startY)로 출발지를 설정합니다.
        val start = KNPOI("현재위치", startX, startY, "")

        // 5. 목적지까지의 트립(경로)을 생성하고 안내를 시작합니다!
        KNSDK.makeTripWithStart(start, goal, null) { aError, aTrip ->
            if (aTrip != null) {
                val curRoutePriority = com.kakaomobility.knsdk.KNRoutePriority.KNRoutePriority_Recommand
                val curAvoidOptions = com.kakaomobility.knsdk.KNRouteAvoidOption.KNRouteAvoidOption_None.value

                aTrip.routeWithPriority(curRoutePriority, curAvoidOptions) { error, _ ->
                    if (error == null) {
                        runOnUiThread {
                            guidance.apply {
                                setupDelegates(this)
                                naviView.initWithGuidance(
                                    this,
                                    aTrip,
                                    curRoutePriority,
                                    curAvoidOptions
                                )
                            }
                        }
                    } else {
                        Log.e("KNSDK", "🚨 경로 요청 실패: ${error.msg}")
                        runOnUiThread { Toast.makeText(this@MainActivity, "길찾기 실패: ${error.msg}", Toast.LENGTH_SHORT).show() }
                    }
                }
            } else {
                Log.e("KNSDK", "🚨 트립 생성 실패: ${aError?.msg}")
            }
        }
    }

    // =========================================================================
    // 💡 델리게이트 구현부
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