package com.example.routeon

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import com.google.android.material.bottomsheet.BottomSheetBehavior
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class RouteStop(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val type: String
)

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

    private val httpClient = OkHttpClient()
    private var webSocket: WebSocket? = null

    private var currentNaviTripId: String? = null
    private val currentStops = mutableListOf<RouteStop>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        KNSDK.install(application, "$filesDir/knsdk")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // 바텀 시트 설정
        val bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheet)
        val displayMetrics = resources.displayMetrics
        bottomSheetBehavior.expandedOffset = (displayMetrics.heightPixels * 0.05).toInt()
        bottomSheetBehavior.isFitToContents = false

        // 설정 버튼 → SettingsActivity로 이동
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        setupRunListUI()
        checkLocationPermission()
        connectWebSocket()
    }

    override fun onResume() {
        super.onResume()
        fetchTrips()
    }

    // =========================================================================
    // 배송 완료 / 취소 상태 업데이트 로직
    // =========================================================================
    private fun updateTripStatus(tripId: String, status: String) {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("http://swc.ddns.net:8000/trips/$tripId/status?status=$status")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"
                conn.setRequestProperty("Authorization", "Bearer $token")

                val responseCode = conn.responseCode
                if (responseCode == 200 || responseCode == 204) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "운행이 ${if (status == "completed") "완료" else "취소"}되었습니다.",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (status == "completed" || status == "cancelled") {
                            KNSDK.sharedGuidance()?.stop()
                            binding.btnCompleteTrip.visibility = View.GONE
                            currentNaviTripId = null
                            currentStops.clear()
                            fetchTrips()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "상태 업데이트 실패 ($responseCode)", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) { }
        }
    }

    private fun completeDelivery(deliveryId: String, name: String) {
        if (deliveryId.isEmpty()) {
            Toast.makeText(this, "배송지 ID가 없어 완료할 수 없습니다.", Toast.LENGTH_SHORT).show()
            currentStops.removeAll { it.name == name }
            binding.btnCompleteTrip.visibility = View.GONE
            return
        }

        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("http://swc.ddns.net:8000/deliveries/$deliveryId/complete")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"
                conn.setRequestProperty("Authorization", "Bearer $token")

                val responseCode = conn.responseCode
                if (responseCode == 200 || responseCode == 204) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "📦 '$name' 배송이 완료 처리되었습니다!",
                            Toast.LENGTH_SHORT
                        ).show()
                        currentStops.removeAll { it.id == deliveryId }
                        binding.btnCompleteTrip.visibility = View.GONE
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "배송 수동 완료 실패", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) { }
        }
    }

    private fun checkProximityToStops(currentLat: Double, currentLng: Double) {
        var nearbyStop: RouteStop? = null
        for (stop in currentStops) {
            val results = FloatArray(1)
            android.location.Location.distanceBetween(currentLat, currentLng, stop.lat, stop.lng, results)
            if (results[0] <= 100) {
                nearbyStop = stop
                break
            }
        }

        runOnUiThread {
            if (nearbyStop != null) {
                binding.btnCompleteTrip.visibility = View.VISIBLE
                if (nearbyStop.type == "destination") {
                    binding.btnCompleteTrip.text = "🏁 운행 전체 완료 (목적지 도착)"
                    binding.btnCompleteTrip.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2E7D32"))
                    binding.btnCompleteTrip.setOnClickListener {
                        currentNaviTripId?.let { updateTripStatus(it, "completed") }
                    }
                } else {
                    binding.btnCompleteTrip.text = "📦 배송지 수동 완료 (${nearbyStop.name})"
                    binding.btnCompleteTrip.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#0288D1"))
                    binding.btnCompleteTrip.setOnClickListener {
                        completeDelivery(nearbyStop.id, nearbyStop.name)
                    }
                }
            } else {
                binding.btnCompleteTrip.visibility = View.GONE
            }
        }
    }

    // =========================================================================
    // WebSocket 연결 로직
    // =========================================================================
    private fun connectWebSocket() {
        val sharedPref = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        val token = sharedPref.getString("access_token", null) ?: return
        val wsUrl = "ws://swc.ddns.net:8000/ws/location"

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("Authorization", "Bearer $token")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("WebSocket", "백엔드 실시간 소켓 연결 성공!")
            }

            @SuppressLint("MissingPermission")
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.optString("type") == "replan_requested") {
                        val message = json.optString("message", "새로운 긴급 경유지가 추가되었습니다. 재탐색합니다.")
                        val tripId = json.optString("trip_id")
                        val waypointsArray = json.optJSONArray("waypoints") ?: JSONArray()

                        runOnUiThread {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("🚨 경로 변경 알림")
                                .setMessage(message)
                                .setPositiveButton("재안내 시작") { _, _ ->
                                    fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                                        if (loc != null) {
                                            requestReplan(tripId, loc.latitude, loc.longitude, waypointsArray)
                                        } else {
                                            Toast.makeText(this@MainActivity, "GPS를 찾을 수 없어 재계산 불가", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                .setCancelable(false)
                                .show()
                        }
                    } else {
                        val arrivedArray = json.optJSONArray("arrived_deliveries")
                        if (arrivedArray != null && arrivedArray.length() > 0) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "✅ 목적지에 도착하여 배송이 자동 완료 처리되었습니다!", Toast.LENGTH_LONG).show()
                                fetchTrips()
                            }
                        }
                    }
                } catch (e: Exception) { }
            }
        }
        webSocket = httpClient.newWebSocket(request, listener)
    }

    private fun requestReplan(tripId: String, currentLat: Double, currentLng: Double, waypointsArray: JSONArray) {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: return
        Toast.makeText(this, "새로운 경로를 서버에서 계산 중입니다...", Toast.LENGTH_LONG).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("http://swc.ddns.net:8000/optimize/replan")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.doOutput = true

                var destName = "목적지"
                var destLat = 0.0
                var destLon = 0.0
                val remainingWaypoints = JSONArray()

                if (waypointsArray.length() > 0) {
                    val lastIdx = waypointsArray.length() - 1
                    val destObj = waypointsArray.getJSONObject(lastIdx)
                    destName = destObj.optString("name", "최종 목적지")
                    destLat = destObj.optDouble("lat", destObj.optDouble("latitude", 0.0))
                    destLon = destObj.optDouble("lon", destObj.optDouble("longitude", 0.0))
                    for (i in 0 until lastIdx) remainingWaypoints.put(waypointsArray.getJSONObject(i))
                }

                val jsonParam = JSONObject().apply {
                    put("trip_id", tripId)
                    put("current_name", "현재 위치")
                    put("current_lat", currentLat)
                    put("current_lon", currentLng)
                    put("current_drive_sec", 0)
                    put("remaining_waypoints", remainingWaypoints)
                    put("dest_name", destName)
                    put("dest_lat", destLat)
                    put("dest_lon", destLon)
                    put("is_emergency", true)
                    put("route_mode", "auto")
                }

                OutputStreamWriter(conn.outputStream).use { it.write(jsonParam.toString()) }

                if (conn.responseCode == 200 || conn.responseCode == 201) {
                    val responseData = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(responseData)
                    parseAndStartNavi(jsonResponse, currentLat, currentLng, destName, destLat, destLon)
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "재경로 계산 실패 (${conn.responseCode})", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) { }
        }
    }

    // =========================================================================
    // 운행 목록 갱신
    // =========================================================================
    private fun setupRunListUI() {
        fetchTrips()
    }

    private fun fetchTrips() {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("http://swc.ddns.net:8000/trips")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                if (conn.responseCode == 200) {
                    val responseData = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonArray = JSONArray(responseData)
                    withContext(Dispatchers.Main) { renderRunList(jsonArray) }
                }
            } catch (e: Exception) { }
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
            val lng = obj.optDouble("dest_lon", obj.optDouble("dest_lng", obj.optDouble("lon", obj.optDouble("lng", 0.0))))
            val status = obj.optString("status", "대기")

            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 40, 40, 40)
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.bottomMargin = 24
                layoutParams = params
                setBackgroundResource(android.R.drawable.btn_default)
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
                setPadding(0, 8, 0, 20)
            }

            val btnLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val btnStart = Button(this).apply {
                text = "안내 시작"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#03C75A"))
                setTextColor(android.graphics.Color.WHITE)
                setOnClickListener {
                    currentNaviTripId = tripId
                    val bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheet)
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

                    fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                        if (location != null) {
                            optimizeAndStartNavi(tripId, destName, lat, lng, location.latitude, location.longitude)
                        } else {
                            Toast.makeText(this@MainActivity, "위치 확인 불가. 일반 안내 시작", Toast.LENGTH_SHORT).show()
                            startNavigationWithWGS84(destName, lat, lng)
                        }
                    }.addOnFailureListener {
                        startNavigationWithWGS84(destName, lat, lng)
                    }
                }
            }

            val btnCancel = Button(this).apply {
                text = "운행 취소"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 20 }
                backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#E74C3C"))
                setTextColor(android.graphics.Color.WHITE)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("운행 취소")
                        .setMessage("정말 이 배차를 취소하시겠습니까?")
                        .setPositiveButton("예") { _, _ -> updateTripStatus(tripId, "cancelled") }
                        .setNegativeButton("아니오", null)
                        .show()
                }
            }

            btnLayout.addView(btnStart)
            btnLayout.addView(btnCancel)
            itemLayout.addView(titleTv)
            itemLayout.addView(statusTv)
            itemLayout.addView(btnLayout)
            container.addView(itemLayout)
        }
    }

    // =========================================================================
    // 경로 최적화 및 파싱 로직
    // =========================================================================
    private suspend fun convertWGS84ToKATEC(lat: Double, lng: Double): Pair<Int, Int>? {
        if (lat < 30.0 || lng < 120.0) return null
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://dapi.kakao.com/v2/local/geo/transcoord.json?x=$lng&y=$lat&input_coord=WGS84&output_coord=KTM")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "KakaoAK efc9f0b149f1b77d83d1b607ee60837d")
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                if (conn.responseCode == 200) {
                    val res = conn.inputStream.bufferedReader().readText()
                    val docs = JSONObject(res).getJSONArray("documents")
                    if (docs.length() > 0) {
                        val x = docs.getJSONObject(0).getDouble("x").toInt()
                        val y = docs.getJSONObject(0).getDouble("y").toInt()
                        return@withContext Pair(x, y)
                    }
                }
            } catch (e: Exception) { }
            return@withContext null
        }
    }

    private fun optimizeAndStartNavi(tripId: String, destName: String, destLat: Double, destLng: Double, currentLat: Double, currentLng: Double) {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: return
        Toast.makeText(this, "경로 최적화 및 휴게소를 계산 중입니다... (최대 15초)", Toast.LENGTH_LONG).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("http://swc.ddns.net:8000/optimize")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.doOutput = true

                val jsonParam = JSONObject().apply {
                    put("trip_id", tripId)
                    put("origin_name", "현재 위치")
                    put("origin_lat", currentLat)
                    put("origin_lon", currentLng)
                    put("initial_drive_sec", 0)
                    put("is_emergency", false)
                }

                OutputStreamWriter(conn.outputStream).use { it.write(jsonParam.toString()) }

                if (conn.responseCode == 200 || conn.responseCode == 201) {
                    val responseData = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(responseData)
                    parseAndStartNavi(jsonResponse, currentLat, currentLng, destName, destLat, destLng)
                } else {
                    withContext(Dispatchers.Main) { startNavigationWithWGS84(destName, destLat, destLng) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { startNavigationWithWGS84(destName, destLat, destLng) }
            }
        }
    }

    private suspend fun parseAndStartNavi(
        jsonResponse: JSONObject,
        currentLat: Double, currentLng: Double,
        fallbackDestName: String, fallbackLat: Double, fallbackLng: Double
    ) {
        val optimizedArray = jsonResponse.optJSONArray("route")
            ?: jsonResponse.optJSONArray("optimized_route")
            ?: jsonResponse.optJSONArray("waypoints")

        if (optimizedArray != null && optimizedArray.length() > 0) {
            val viaList = mutableListOf<KNPOI>()
            var finalDestName = fallbackDestName
            var finalDestLat = fallbackLat
            var finalDestLng = fallbackLng

            currentStops.clear()

            for (i in 0 until optimizedArray.length()) {
                val pt = optimizedArray.getJSONObject(i)
                val type = pt.optString("type", "")
                val name = pt.optString("name", "경유지 ${i + 1}")
                val lat = pt.optDouble("lat", 0.0)
                val lng = pt.optDouble("lon", pt.optDouble("lng", 0.0))
                val deliveryId = pt.optString("delivery_id", pt.optString("id", ""))

                if (type == "waypoint" || type == "destination") {
                    currentStops.add(RouteStop(deliveryId, name, lat, lng, type))
                }

                when (type) {
                    "waypoint", "rest_stop" -> {
                        val katec = convertWGS84ToKATEC(lat, lng)
                        if (katec != null) viaList.add(KNPOI(name, katec.first, katec.second, ""))
                    }
                    "destination" -> {
                        finalDestName = name; finalDestLat = lat; finalDestLng = lng
                    }
                }
            }

            val startKatec = convertWGS84ToKATEC(currentLat, currentLng)
            val goalKatec  = convertWGS84ToKATEC(finalDestLat, finalDestLng)

            if (startKatec != null && goalKatec != null) {
                val matchedLocation = KNSDK.sharedGuidance()?.locationGuide?.location
                val startX = matchedLocation?.pos?.x?.toInt() ?: startKatec.first
                val startY = matchedLocation?.pos?.y?.toInt() ?: startKatec.second

                val startPoi = KNPOI("현재 위치", startX, startY, "")
                val goalPoi  = KNPOI(finalDestName, goalKatec.first, goalKatec.second, "")

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "최적화 완료! 경로 안내를 시작합니다.", Toast.LENGTH_SHORT).show()
                    startNavigationWithWaypoints(startPoi, goalPoi, viaList)
                }
            } else {
                withContext(Dispatchers.Main) { startNavigationWithWGS84(fallbackDestName, fallbackLat, fallbackLng) }
            }
        } else {
            withContext(Dispatchers.Main) { startNavigationWithWGS84(fallbackDestName, fallbackLat, fallbackLng) }
        }
    }

    private fun startNavigationWithWaypoints(start: KNPOI, goal: KNPOI, vias: MutableList<KNPOI>) {
        val guidance = KNSDK.sharedGuidance() ?: return
        guidance.stop()

        KNSDK.makeTripWithStart(start, goal, vias) { aError, aTrip ->
            if (aTrip != null) {
                val curRoutePriority = KNRoutePriority.KNRoutePriority_Recommand
                val curAvoidOptions  = KNRouteAvoidOption.KNRouteAvoidOption_None.value

                aTrip.routeWithPriority(curRoutePriority, curAvoidOptions) { error, _ ->
                    if (error == null) {
                        runOnUiThread {
                            binding.naviContainer.removeAllViews()
                            naviView = KNNaviView(this@MainActivity)
                            binding.naviContainer.addView(naviView)

                            // SharedPreferences에서 설정값 읽기 (SettingsActivity에서 저장한 값)
                            val sharedPref = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
                            naviView.useDarkMode = sharedPref.getBoolean("dark_mode", false)

                            // fuel_type: 0=휘발유, 1=고급, 2=경유, 3=LPG, 4=전기, 5=하이브리드, 6=플러그인HEV, 7=수소
                            naviView.fuelType = when (sharedPref.getInt("fuel_type", 0)) {
                                2    -> KNCarFuel.KNCarFuel_Diesel
                                3    -> KNCarFuel.KNCarFuel_LPG
                                4    -> KNCarFuel.KNCarFuel_Electric
                                5    -> KNCarFuel.KNCarFuel_HybridElectric
                                6    -> KNCarFuel.KNCarFuel_PlugInHybridElectric
                                7    -> KNCarFuel.KNCarFuel_Hydrogen
                                else -> KNCarFuel.KNCarFuel_Gasoline
                            }

                            // car_type: 0=소형, 1=중형, 2=대형, 3=대형화물, 4=특수화물, 5=경차, 6=이륜차
                            naviView.carType = when (sharedPref.getInt("car_type", 0)) {
                                1    -> KNCarType.KNCarType_2
                                2    -> KNCarType.KNCarType_3
                                3    -> KNCarType.KNCarType_4
                                4    -> KNCarType.KNCarType_5
                                5    -> KNCarType.KNCarType_6
                                6    -> KNCarType.KNCarType_Bike
                                else -> KNCarType.KNCarType_1
                            }

                            guidance.apply {
                                setupDelegates(this)
                                naviView.initWithGuidance(this, aTrip, curRoutePriority, curAvoidOptions)
                            }
                        }
                    } else Log.e("KNSDK", "🚨 경로 탐색 실패: ${error.msg}")
                }
            } else Log.e("KNSDK", "🚨 트립 생성 실패: ${aError?.msg}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNavigationWithWGS84(name: String, lat: Double, lng: Double) {
        CoroutineScope(Dispatchers.IO).launch {
            if (lat < 30.0 || lng < 120.0) return@launch
            try {
                val url = URL("https://dapi.kakao.com/v2/local/geo/transcoord.json?x=$lng&y=$lat&input_coord=WGS84&output_coord=KTM")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "KakaoAK efc9f0b149f1b77d83d1b607ee60837d")
                conn.connectTimeout = 3000
                conn.readTimeout = 3000

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
                                            val matchedLocation = KNSDK.sharedGuidance()?.locationGuide?.location
                                            val startX = matchedLocation?.pos?.x?.toInt() ?: startKatec.first
                                            val startY = matchedLocation?.pos?.y?.toInt() ?: startKatec.second
                                            val startPoi = KNPOI("현재 위치", startX, startY, "")
                                            val goalPoi  = KNPOI(name, katecX, katecY, "")
                                            withContext(Dispatchers.Main) {
                                                startNavigationWithWaypoints(startPoi, goalPoi, mutableListOf())
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) { }
        }
    }

    // =========================================================================
    // GPS 전송 (5초 주기)
    // =========================================================================
    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateIntervalMillis(5000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    sendLocationToServer(location.latitude, location.longitude, location.speed)
                    checkProximityToStops(location.latitude, location.longitude)
                }
            }
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private fun sendLocationToServer(lat: Double, lng: Double, speed: Float) {
        val sharedPref = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        val token  = sharedPref.getString("access_token", null) ?: return
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
                webSocket?.send(jsonParam.toString())
            } catch (e: Exception) { }
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

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.cancel()
        if (::fusedLocationClient.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    // =========================================================================
    // 카카오 내비게이션 초기화 및 델리게이트
    // =========================================================================
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
                        val sharedPref = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
                        naviView.useDarkMode = sharedPref.getBoolean("dark_mode", false)
                        startSafeDriving()
                    }
                }
            }
        )
    }

    private fun setupDelegates(guidance: KNGuidance) {
        guidance.guideStateDelegate    = this
        guidance.locationGuideDelegate = this
        guidance.routeGuideDelegate    = this
        guidance.safetyGuideDelegate   = this
        guidance.voiceGuideDelegate    = this
        guidance.citsGuideDelegate     = this
        naviView.mapComponent.mapView.isVisibleTraffic = true
    }

    private fun startSafeDriving() {
        val guidance = KNSDK.sharedGuidance()
        guidance?.apply {
            setupDelegates(this)
            naviView.initWithGuidance(this, null, KNRoutePriority.KNRoutePriority_Recommand, KNRouteAvoidOption.KNRouteAvoidOption_None.value)
        }
    }

    override fun guidanceGuideEnded(aGuidance: KNGuidance) {
        if (::naviView.isInitialized) naviView.guidanceGuideEnded(aGuidance)
        runOnUiThread {
            Toast.makeText(this@MainActivity, "안내가 종료되었습니다.", Toast.LENGTH_SHORT).show()
            binding.naviContainer.removeAllViews()
            naviView = KNNaviView(this@MainActivity)
            binding.naviContainer.addView(naviView)
            val sharedPref = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            naviView.useDarkMode = sharedPref.getBoolean("dark_mode", false)
            startSafeDriving()
        }
    }

    override fun guidanceGuideStarted(aGuidance: KNGuidance)  { if (::naviView.isInitialized) naviView.guidanceGuideStarted(aGuidance) }
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
    override fun shouldPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice, aNewData: MutableList<ByteArray>): Boolean = if (::naviView.isInitialized) naviView.shouldPlayVoiceGuide(aGuidance, aVoiceGuide, aNewData) else false
    override fun willPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice) { if (::naviView.isInitialized) naviView.willPlayVoiceGuide(aGuidance, aVoiceGuide) }
    override fun didFinishPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice) { if (::naviView.isInitialized) naviView.didFinishPlayVoiceGuide(aGuidance, aVoiceGuide) }
    override fun didUpdateCitsGuide(aGuidance: KNGuidance, aCitsGuide: KNGuide_Cits) { if (::naviView.isInitialized) naviView.didUpdateCitsGuide(aGuidance, aCitsGuide) }
}
