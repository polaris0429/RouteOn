package com.example.routeon

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.routeon.databinding.ActivityMainBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.common.objects.KNError
import com.kakaomobility.knsdk.common.objects.KNPOI
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity(),
    KNGuidance_GuideStateDelegate,
    KNGuidance_LocationGuideDelegate,
    KNGuidance_RouteGuideDelegate,
    KNGuidance_SafetyGuideDelegate,
    KNGuidance_VoiceGuideDelegate,
    KNGuidance_CitsGuideDelegate {

    private lateinit var binding: ActivityMainBinding
    private lateinit var naviView: KNNaviView
    private val locationPermissionRequestCode = 1000

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

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

        // 💡 운행 목록 초기화 및 서버에서 불러오기
        setupRunListUI()

        checkLocationPermission()
    }

    // =========================================================================
    // 💡 백엔드 연동: 운행 목록 (GET /deliveries) 동적 생성 로직
    // =========================================================================

    private fun setupRunListUI() {
        val btnRefresh = findViewById<Button>(R.id.btn_refresh_list)
        btnRefresh.setOnClickListener {
            Toast.makeText(this, "운행 목록을 갱신합니다...", Toast.LENGTH_SHORT).show()
            fetchDeliveries()
        }

        // 화면이 켜질 때 최초 1회 불러오기
        fetchDeliveries()
    }

    private fun fetchDeliveries() {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).getString("access_token", null) ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("http://swc.ddns.net:8000/deliveries")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 5000

                if (conn.responseCode == 200) {
                    val responseData = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonArray = JSONArray(responseData)
                    withContext(Dispatchers.Main) {
                        renderRunList(jsonArray)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderRunList(jsonArray: JSONArray) {
        val container = findViewById<LinearLayout>(R.id.run_list_container)
        container.removeAllViews() // 기존 목록 초기화

        if (jsonArray.length() == 0) {
            val emptyTv = TextView(this).apply {
                text = "현재 배정된 배송지가 없습니다."
                setPadding(20, 20, 20, 20)
                textSize = 16f
            }
            container.addView(emptyTv)
            return
        }

        // 받아온 JSON 배열을 하나씩 돌면서 UI 블록(LinearLayout)을 만들어 붙입니다.
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val address = obj.optString("address", "주소 없음")
            val status = obj.optString("status", "대기")
            val lat = obj.optDouble("lat", 0.0)
            val lng = obj.optDouble("lng", 0.0)

            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(40, 40, 40, 40)
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.bottomMargin = 24
                layoutParams = params
                setBackgroundResource(android.R.drawable.btn_default) // 버튼 스타일 배경
            }

            val textLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleTv = TextView(this).apply {
                text = "${i + 1}. $address"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.BLACK)
            }

            val statusTv = TextView(this).apply {
                val statusKr = when(status) {
                    "pending" -> "대기중"
                    "in_progress" -> "배송 진행중"
                    "done" -> "완료됨"
                    else -> status
                }
                text = "상태: $statusKr"
                textSize = 14f
                setTextColor(android.graphics.Color.DKGRAY)
                setPadding(0, 8, 0, 0)
            }

            textLayout.addView(titleTv)
            textLayout.addView(statusTv)

            val btn = Button(this).apply {
                text = "안내 시작"
                setOnClickListener {
                    // 💡 위경도를 카카오 좌표로 변환 후 안내 시작
                    startNavigationWithWGS84(address, lat, lng)
                }
            }

            itemLayout.addView(textLayout)
            itemLayout.addView(btn)
            container.addView(itemLayout)
        }
    }

    // 💡 [핵심] 일반 위도경도(WGS84)를 카카오 내비 전용 좌표(KATEC)로 변환하는 함수
    private fun startNavigationWithWGS84(name: String, lat: Double, lng: Double) {
        Toast.makeText(this, "경로 좌표를 변환 중입니다...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 카카오 로컬 REST API를 호출하여 좌표계 변환 수행 (앱키 재사용)
                val url = URL("https://dapi.kakao.com/v2/local/geo/transcoord.json?x=$lng&y=$lat&input_coord=WGS84&output_coord=KATEC")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "KakaoAK b57bc6d46e97f480deecdd3a8e4cd754")

                if (conn.responseCode == 200) {
                    val res = conn.inputStream.bufferedReader().readText()
                    val docs = JSONObject(res).getJSONArray("documents")
                    if (docs.length() > 0) {
                        val katecX = docs.getJSONObject(0).getDouble("x").toInt()
                        val katecY = docs.getJSONObject(0).getDouble("y").toInt()

                        withContext(Dispatchers.Main) {
                            val goal = KNPOI(name, katecX, katecY, name)
                            startNavigation(goal)
                            binding.bottomNav.selectedItemId = R.id.nav_map // 지도로 화면 넘기기
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "카카오 좌표 변환 실패", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // =========================================================================
    // 💡 내 정보 수정 (PATCH /auth/me) 서버 연동
    // =========================================================================

    private fun updateMyInfoOnServer(jsonParam: JSONObject, onSuccess: () -> Unit) {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).getString("access_token", null) ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("http://swc.ddns.net:8000/auth/me")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH" // 백엔드 팀원분이 만든 PATCH 규격 사용
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.doOutput = true

                OutputStreamWriter(conn.outputStream).use { it.write(jsonParam.toString()) }

                val responseCode = conn.responseCode
                withContext(Dispatchers.Main) {
                    if (responseCode == 200 || responseCode == 204) {
                        onSuccess()
                    } else {
                        Toast.makeText(this@MainActivity, "정보 수정 실패 (오류 코드: $responseCode)", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "서버 연결 오류", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showEditPhoneDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }
        val etPhone = EditText(this).apply {
            hint = "새 휴대폰 번호 (예: 010-1234-5678)"
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine()
            setPadding(20, 30, 20, 30)
        }
        layout.addView(etPhone)
        AlertDialog.Builder(this)
            .setTitle("휴대폰 번호 변경")
            .setView(layout)
            .setPositiveButton("변경하기") { _, _ ->
                val newPhone = etPhone.text.toString().trim()
                if (newPhone.isNotEmpty()) {
                    val json = JSONObject().apply { put("phone", newPhone) }
                    updateMyInfoOnServer(json) {
                        binding.tvMyPhone.text = "연락처: $newPhone"
                        Toast.makeText(this, "휴대폰 번호가 안전하게 변경되었습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showEditPasswordDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }
        val etCurrentPwd = EditText(this).apply {
            hint = "현재 비밀번호"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            setPadding(20, 30, 20, 30)
        }
        val etNewPwd = EditText(this).apply {
            hint = "새 비밀번호"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            setPadding(20, 30, 20, 30)
        }
        val etNewPwdConfirm = EditText(this).apply {
            hint = "새 비밀번호 확인"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            setPadding(20, 30, 20, 30)
        }
        layout.addView(etCurrentPwd)
        layout.addView(etNewPwd)
        layout.addView(etNewPwdConfirm)

        AlertDialog.Builder(this)
            .setTitle("비밀번호 변경")
            .setView(layout)
            .setPositiveButton("변경하기") { _, _ ->
                val current = etCurrentPwd.text.toString()
                val newPwd = etNewPwd.text.toString()
                val confirm = etNewPwdConfirm.text.toString()

                if (current.isEmpty() || newPwd.isEmpty() || confirm.isEmpty()) return@setPositiveButton
                if (newPwd != confirm) {
                    Toast.makeText(this, "새 비밀번호가 일치하지 않습니다.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val json = JSONObject().apply {
                    put("current_password", current)
                    put("new_password", newPwd)
                }
                updateMyInfoOnServer(json) {
                    Toast.makeText(this, "비밀번호가 성공적으로 변경되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }


    // =========================================================================
    // 기존 기능 유지 (GPS 전송, UI 설정 등)
    // =========================================================================

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(3000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    sendLocationToServer(location.latitude, location.longitude, location.speed)
                }
            }
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private fun sendLocationToServer(lat: Double, lng: Double, speed: Float) {
        val sharedPref = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        val token = sharedPref.getString("access_token", null) ?: return
        val userId = sharedPref.getString("user_id", null) ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("http://swc.ddns.net:8000/location-logs")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.doOutput = true

                val jsonParam = JSONObject().apply {
                    put("user_id", userId)
                    put("lat", lat)
                    put("lon", lng)
                    put("speed", speed)
                }

                OutputStreamWriter(conn.outputStream).use { it.write(jsonParam.toString()) }
                conn.responseCode // 통신 실행
            } catch (e: Exception) {
                Log.e("LocationUpdate", "위치 전송 에러: ${e.message}")
            }
        }
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                locationPermissionRequestCode
            )
        } else {
            initKakaoNaviSDK()
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == locationPermissionRequestCode && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initKakaoNaviSDK()
            startLocationUpdates()
        } else {
            Toast.makeText(this, "위치 권한이 필요합니다.", Toast.LENGTH_LONG).show()
            finish()
        }
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

    @SuppressLint("SetTextI18n")
    private fun setupSettingsUI() {
        val sharedPref = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        val savedUsername = sharedPref.getString("username", "알 수 없음") ?: "알 수 없음"
        binding.tvMyId.text = "아이디: $savedUsername"
        binding.tvMyPhone.text = "연락처: 조회 필요"

        binding.switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            if (::naviView.isInitialized) naviView.useDarkMode = isChecked
            if (isChecked) AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
        binding.rgFuel.setOnCheckedChangeListener { _, checkedId ->
            if (!::naviView.isInitialized) return@setOnCheckedChangeListener
            naviView.fuelType = when (checkedId) {
                R.id.rb_fuel_diesel -> KNCarFuel.KNCarFuel_Diesel
                R.id.rb_fuel_electric -> KNCarFuel.KNCarFuel_Electric
                else -> KNCarFuel.KNCarFuel_Gasoline
            }
        }
        binding.rgCarType.setOnCheckedChangeListener { _, checkedId ->
            if (!::naviView.isInitialized) return@setOnCheckedChangeListener
            naviView.carType = when (checkedId) {
                R.id.rb_car_type_4 -> KNCarType.KNCarType_4
                R.id.rb_car_type_bike -> KNCarType.KNCarType_Bike
                else -> KNCarType.KNCarType_1
            }
        }
        binding.btnEditInfo.setOnClickListener { showEditSelectionDialog() }
        binding.btnLogout.setOnClickListener { showLogoutDialog() }
    }

    private fun showEditSelectionDialog() {
        val options = arrayOf("휴대폰 번호 변경", "비밀번호 변경")
        AlertDialog.Builder(this)
            .setTitle("내 정보 변경")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditPhoneDialog()
                    1 -> showEditPasswordDialog()
                }
            }
            .show()
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle("로그아웃")
            .setMessage("정말 로그아웃 하시겠습니까?")
            .setPositiveButton("로그아웃") { _, _ ->
                getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).edit { clear() }
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::fusedLocationClient.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    // =========================================================================
    // 카카오 내비게이션 기능
    // =========================================================================

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
                this, null,
                com.kakaomobility.knsdk.KNRoutePriority.KNRoutePriority_Recommand,
                com.kakaomobility.knsdk.KNRouteAvoidOption.KNRouteAvoidOption_None.value
            )
        }
    }

    private fun startNavigation(goal: KNPOI) {
        val guidance = KNSDK.sharedGuidance() ?: return
        guidance.stop()

        val currentLocation = guidance.locationGuide?.location
        if (currentLocation == null) {
            Toast.makeText(this, "GPS 위치를 확인하는 중입니다. 잠시 후 다시 시도해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        val startX = currentLocation.pos.x.toInt()
        val startY = currentLocation.pos.y.toInt()
        val start = KNPOI("", startX, startY, "")

        KNSDK.makeTripWithStart(start, goal, null) { aError, aTrip ->
            if (aTrip != null) {
                val curRoutePriority = com.kakaomobility.knsdk.KNRoutePriority.KNRoutePriority_Recommand
                val curAvoidOptions = com.kakaomobility.knsdk.KNRouteAvoidOption.KNRouteAvoidOption_None.value

                aTrip.routeWithPriority(curRoutePriority, curAvoidOptions) { error, _ ->
                    if (error == null) {
                        runOnUiThread {
                            binding.naviContainer.removeAllViews()
                            naviView = KNNaviView(this@MainActivity)
                            binding.naviContainer.addView(naviView)

                            naviView.useDarkMode = binding.switchDarkMode.isChecked
                            naviView.fuelType = when (binding.rgFuel.checkedRadioButtonId) {
                                R.id.rb_fuel_diesel -> KNCarFuel.KNCarFuel_Diesel
                                R.id.rb_fuel_electric -> KNCarFuel.KNCarFuel_Electric
                                else -> KNCarFuel.KNCarFuel_Gasoline
                            }
                            naviView.carType = when (binding.rgCarType.checkedRadioButtonId) {
                                R.id.rb_car_type_4 -> KNCarType.KNCarType_4
                                R.id.rb_car_type_bike -> KNCarType.KNCarType_Bike
                                else -> KNCarType.KNCarType_1
                            }

                            guidance.apply {
                                setupDelegates(this)
                                naviView.initWithGuidance(this, aTrip, curRoutePriority, curAvoidOptions)
                            }
                        }
                    } else Log.e("KNSDK", "🚨 경로 요청 실패: ${error.msg}")
                }
            } else Log.e("KNSDK", "🚨 트립 생성 실패: ${aError?.msg}")
        }
    }

    override fun guidanceGuideEnded(aGuidance: KNGuidance) {
        if (::naviView.isInitialized) naviView.guidanceGuideEnded(aGuidance)
        runOnUiThread {
            Toast.makeText(this@MainActivity, "안내가 종료되었습니다.", Toast.LENGTH_SHORT).show()
            binding.naviContainer.removeAllViews()
            naviView = KNNaviView(this@MainActivity)
            binding.naviContainer.addView(naviView)
            startSafeDriving()
        }
    }

    override fun guidanceGuideStarted(aGuidance: KNGuidance) { if (::naviView.isInitialized) naviView.guidanceGuideStarted(aGuidance) }
    override fun guidanceCheckingRouteChange(aGuidance: KNGuidance) { if (::naviView.isInitialized) naviView.guidanceCheckingRouteChange(aGuidance) }
    override fun guidanceRouteUnchanged(aGuidance: KNGuidance) { if (::naviView.isInitialized) naviView.guidanceRouteUnchanged(aGuidance) }
    override fun guidanceRouteUnchangedWithError(aGuidnace: KNGuidance, aError: KNError) { if (::naviView.isInitialized) naviView.guidanceRouteUnchangedWithError(aGuidnace, aError) }
    override fun guidanceOutOfRoute(aGuidance: KNGuidance) { if (::naviView.isInitialized) naviView.guidanceOutOfRoute(aGuidance) }
    override fun guidanceRouteChanged(aGuidance: KNGuidance, aFromRoute: KNRoute, aFromLocation: KNLocation, aToRoute: KNRoute, aToLocation: KNLocation, aChangeReason: KNGuideRouteChangeReason) {}
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