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
import com.kakaomobility.knsdk.KNLanguageType
import com.kakaomobility.knsdk.KNRouteAvoidOption
import com.kakaomobility.knsdk.KNRoutePriority
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

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupBottomNavigation()
        setupSettingsUI()
        setupRunListUI()
        checkLocationPermission()
    }

    private fun setupRunListUI() {
        val btnRefresh = findViewById<Button>(R.id.btn_refresh_list)
        btnRefresh.setOnClickListener {
            Toast.makeText(this, "운행 목록을 갱신합니다...", Toast.LENGTH_SHORT).show()
            fetchTrips()
        }
        fetchTrips()
    }

    private fun fetchTrips() {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).getString("access_token", null) ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("http://swc.ddns.net:8000/trips")
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
                Log.e("FetchTrips", "목록 갱신 실패: ${e.message}")
            }
        }
    }

    @SuppressLint("SetTextI18n", "MissingPermission")
    private fun renderRunList(jsonArray: JSONArray) {
        val container = findViewById<LinearLayout>(R.id.run_list_container)
        container.removeAllViews()

        if (jsonArray.length() == 0) {
            val emptyTv = TextView(this).apply {
                text = "현재 배정된 배차(Trip)가 없습니다."
                setPadding(20, 20, 20, 20)
                textSize = 16f
            }
            container.addView(emptyTv)
            return
        }

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val tripId = obj.optString("id", "")
            val destName = obj.optString("dest_name", obj.optString("address", "목적지 없음"))
            val lat = obj.optDouble("dest_lat", obj.optDouble("lat", 0.0))
            val lng = obj.optDouble("dest_lng", obj.optDouble("lng", 0.0))
            val status = obj.optString("status", "대기")

            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(40, 40, 40, 40)
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.bottomMargin = 24
                layoutParams = params
                setBackgroundResource(android.R.drawable.btn_default)
            }

            val textLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleTv = TextView(this).apply {
                text = "${i + 1}. $destName"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.BLACK)
            }

            val statusTv = TextView(this).apply {
                text = "상태: $status"
                textSize = 14f
                setTextColor(android.graphics.Color.DKGRAY)
                setPadding(0, 8, 0, 0)
            }

            textLayout.addView(titleTv)
            textLayout.addView(statusTv)

            val btn = Button(this).apply {
                text = "안내 시작"
                setOnClickListener {
                    fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                        if (location != null) {
                            optimizeAndStartNavi(tripId, destName, lat, lng, location.latitude, location.longitude)
                        } else {
                            Toast.makeText(this@MainActivity, "현재 위치 확인 불가. 일반 안내를 시작합니다.", Toast.LENGTH_SHORT).show()
                            startNavigationWithWGS84(destName, lat, lng)
                        }
                    }.addOnFailureListener {
                        startNavigationWithWGS84(destName, lat, lng)
                    }
                }
            }

            itemLayout.addView(textLayout)
            itemLayout.addView(btn)
            container.addView(itemLayout)
        }
    }

    private suspend fun convertWGS84ToKATEC(lat: Double, lng: Double): Pair<Int, Int>? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://dapi.kakao.com/v2/local/geo/transcoord.json?x=$lng&y=$lat&input_coord=WGS84&output_coord=KATEC")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "KakaoAK b57bc6d46e97f480deecdd3a8e4cd754")

                if (conn.responseCode == 200) {
                    val res = conn.inputStream.bufferedReader().readText()
                    val docs = JSONObject(res).getJSONArray("documents")
                    if (docs.length() > 0) {
                        val x = docs.getJSONObject(0).getDouble("x").toInt()
                        val y = docs.getJSONObject(0).getDouble("y").toInt()
                        return@withContext Pair(x, y)
                    }
                }
            } catch (e: Exception) {
                Log.e("ConvertCoord", "좌표 변환 에러: ${e.message}")
            }
            return@withContext null
        }
    }

    private fun optimizeAndStartNavi(tripId: String, destName: String, destLat: Double, destLng: Double, currentLat: Double, currentLng: Double) {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).getString("access_token", null) ?: return
        Toast.makeText(this, "서버에서 경로 최적화 및 휴게소를 계산 중입니다...", Toast.LENGTH_LONG).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("http://swc.ddns.net:8000/optimize")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.doOutput = true

                val jsonParam = JSONObject().apply {
                    put("trip_id", tripId)
                    put("origin_name", "현재 위치")
                    put("origin_lat", currentLat)
                    put("origin_lng", currentLng)
                    put("initial_drive_sec", 0)
                    put("is_emergency", false)
                }

                OutputStreamWriter(conn.outputStream).use { it.write(jsonParam.toString()) }

                if (conn.responseCode == 200 || conn.responseCode == 201) {
                    val responseData = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(responseData)

                    val optimizedArray = jsonResponse.optJSONArray("optimized_route")
                        ?: jsonResponse.optJSONArray("waypoints")

                    if (optimizedArray != null && optimizedArray.length() > 0) {
                        // 💡 [수정됨] vias는 MutableList<KNPOI> 여야 함
                        val viaList = mutableListOf<KNPOI>()
                        var finalDestName = destName
                        var finalDestLat = destLat
                        var finalDestLng = destLng

                        for (i in 0 until optimizedArray.length() - 1) {
                            val pt = optimizedArray.getJSONObject(i)
                            val name = pt.optString("name", "경유지 ${i + 1}")
                            val lat = pt.optDouble("lat", 0.0)
                            val lng = pt.optDouble("lng", pt.optDouble("lon", 0.0))

                            val katec = convertWGS84ToKATEC(lat, lng)
                            if (katec != null) {
                                viaList.add(KNPOI(name, katec.first, katec.second, ""))
                            }
                        }

                        val lastPt = optimizedArray.getJSONObject(optimizedArray.length() - 1)
                        finalDestName = lastPt.optString("name", destName)
                        finalDestLat = lastPt.optDouble("lat", destLat)
                        finalDestLng = lastPt.optDouble("lng", lastPt.optDouble("lon", destLng))

                        val startKatec = convertWGS84ToKATEC(currentLat, currentLng)
                        val goalKatec = convertWGS84ToKATEC(finalDestLat, finalDestLng)

                        if (startKatec != null && goalKatec != null) {
                            val startPoi = KNPOI("현재 위치", startKatec.first, startKatec.second, "")
                            val goalPoi = KNPOI(finalDestName, goalKatec.first, goalKatec.second, "")

                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "최적화 완료! 다중 경유지 안내를 시작합니다.", Toast.LENGTH_SHORT).show()
                                startNavigationWithWaypoints(startPoi, goalPoi, viaList)
                            }
                        } else {
                            withContext(Dispatchers.Main) { startNavigationWithWGS84(destName, destLat, destLng) }
                        }
                    } else {
                        withContext(Dispatchers.Main) { startNavigationWithWGS84(destName, destLat, destLng) }
                    }
                } else {
                    withContext(Dispatchers.Main) { startNavigationWithWGS84(destName, destLat, destLng) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { startNavigationWithWGS84(destName, destLat, destLng) }
            }
        }
    }

    // 💡 [수정됨] vias 파라미터를 MutableList<KNPOI> 타입으로 명시
    private fun startNavigationWithWaypoints(start: KNPOI, goal: KNPOI, vias: MutableList<KNPOI>) {
        val guidance = KNSDK.sharedGuidance() ?: return
        guidance.stop()

        KNSDK.makeTripWithStart(start, goal, vias) { aError, aTrip ->
            if (aTrip != null) {
                val curRoutePriority = KNRoutePriority.KNRoutePriority_Recommand
                val curAvoidOptions = KNRouteAvoidOption.KNRouteAvoidOption_None.value

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
                            binding.bottomNav.selectedItemId = R.id.nav_map
                        }
                    } else Log.e("KNSDK", "🚨 경로 요청 실패: ${error.msg}")
                }
            } else Log.e("KNSDK", "🚨 트립 생성 실패: ${aError?.msg}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNavigationWithWGS84(name: String, lat: Double, lng: Double) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
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
                            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                                if (loc != null) {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val startKatec = convertWGS84ToKATEC(loc.latitude, loc.longitude)
                                        if (startKatec != null) {
                                            val startPoi = KNPOI("현재 위치", startKatec.first, startKatec.second, "")
                                            val goalPoi = KNPOI(name, katecX, katecY, "")
                                            withContext(Dispatchers.Main) {
                                                // 💡 [수정됨] 빈 리스트 전달 시 mutableListOf() 사용
                                                startNavigationWithWaypoints(startPoi, goalPoi, mutableListOf())
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("StartNavi", "일반 안내 실패: ${e.message}")
            }
        }
    }

    private fun updateMyInfoOnServer(jsonParam: JSONObject, onSuccess: () -> Unit) {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).getString("access_token", null) ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("http://swc.ddns.net:8000/auth/me")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.doOutput = true

                OutputStreamWriter(conn.outputStream).use { it.write(jsonParam.toString()) }

                val responseCode = conn.responseCode
                withContext(Dispatchers.Main) {
                    if (responseCode == 200 || responseCode == 204) {
                        onSuccess()
                    } else {
                        Toast.makeText(this@MainActivity, "정보 수정 실패", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "서버 연결 오류", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    // 💡 [수정됨] 누락되었던 팝업 선택 메서드 복구
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
                        Toast.makeText(this, "휴대폰 번호 변경 완료!", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this, "새 비밀번호 불일치", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val json = JSONObject().apply {
                    put("current_password", current)
                    put("new_password", newPwd)
                }
                updateMyInfoOnServer(json) {
                    Toast.makeText(this, "비밀번호 변경 완료!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 30000)
            .setMinUpdateIntervalMillis(30000)
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
                conn.responseCode
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

    private fun initKakaoNaviSDK() {
        KNSDK.initializeWithAppKey(
            aAppKey = "b57bc6d46e97f480deecdd3a8e4cd754",
            aClientVersion = "1.0",
            aAppUserId = "test_user",
            aLangType = KNLanguageType.KNLanguageType_KOREAN,
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
                KNRoutePriority.KNRoutePriority_Recommand,
                KNRouteAvoidOption.KNRouteAvoidOption_None.value
            )
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