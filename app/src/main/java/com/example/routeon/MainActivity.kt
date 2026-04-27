package com.example.routeon

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
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
    val id: String, val name: String,
    val lat: Double, val lng: Double, val type: String
)

class MainActivity : AppCompatActivity(),
    KNGuidance_GuideStateDelegate, KNGuidance_LocationGuideDelegate,
    KNGuidance_RouteGuideDelegate,  KNGuidance_SafetyGuideDelegate,
    KNGuidance_VoiceGuideDelegate,  KNGuidance_CitsGuideDelegate,
    SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var naviView: KNNaviView
    private val locationPermissionRequestCode = 1000

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private val httpClient = OkHttpClient()
    private var webSocket: WebSocket? = null

    private var currentNaviTripId: String? = null
    private val currentStops = mutableListOf<RouteStop>()

    // 조도 센서
    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private val DARK_THRESHOLD = 20f
    private val switchHandler = Handler(Looper.getMainLooper())
    private var lastSwitchTime = 0L
    private val SWITCH_DEBOUNCE_MS = 3000L

    // BottomSheet
    private var bottomSheetBehavior: BottomSheetBehavior<LinearLayout>? = null

    // 현재 다크모드 여부를 Configuration 기준으로 판단
    private val isNightMode: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    // =========================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 시스템바 정상 표시 (콘텐츠가 뒤에 안 깔리게)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        KNSDK.install(application, "$filesDir/knsdk")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // setContentView 이후에 호출해야 decorView가 준비됨
        applySystemBarsColor()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor   = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        // ── 바텀시트 설정 ──
        val bsb = BottomSheetBehavior.from(binding.bottomSheet)
        bottomSheetBehavior = bsb
        bsb.isFitToContents = false

        // 최대 90% 높이: expandedOffset = 화면 높이의 10%
        val screenHeight = resources.displayMetrics.heightPixels
        bsb.expandedOffset = (screenHeight * 0.05).toInt()

        bsb.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                val isExpanded = newState == BottomSheetBehavior.STATE_EXPANDED
                binding.bottomActionBar.visibility = if (isExpanded) View.VISIBLE else View.GONE
                applySystemBarsColor()
            }
            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                applySystemBarsColor()
            }
        })

        // 설정 버튼 (왼쪽 FAB)
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 도움말 텍스트 (가운데) → HelpActivity
        binding.btnHelp.setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }

        // 채팅 버튼 (오른쪽 FAB) → ChatActivity
        binding.btnChat.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }

        setupRunListUI()
        checkLocationPermission()
        connectWebSocket()
    }

    // ── 다크모드: 무조건 검정 고정, 라이트모드: 흰색 ──
    // BottomSheetBehavior 가 onSlide/onStateChanged 에서 색을 덮어쓰므로 매번 재적용
    private fun applySystemBarsColor() {
        val barColor = if (isNightMode) Color.BLACK else Color.WHITE
        window.statusBarColor     = barColor
        window.navigationBarColor = barColor
        val ic = WindowInsetsControllerCompat(window, window.decorView)
        ic.isAppearanceLightStatusBars     = !isNightMode
        ic.isAppearanceLightNavigationBars = !isNightMode
    }

    // configChanges="uiMode" → 테마 전환 시 Activity 재생성 없이 여기만 호출됨
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        val isDark = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        // 1. 네비뷰 다크모드 즉시 전환
        if (::naviView.isInitialized) naviView.useDarkMode = isDark

        // 2. 바텀시트 UI 색상 갱신
        val bgColor     = ResourcesCompat.getColor(resources, R.color.bg_bottom_sheet, theme)
        val textColor   = ResourcesCompat.getColor(resources, R.color.text_primary, theme)
        val handleColor = ResourcesCompat.getColor(resources, R.color.drag_handle, theme)
        binding.bottomSheet.setBackgroundColor(bgColor)
        binding.bottomSheet.getChildAt(0)?.setBackgroundColor(handleColor)
        binding.bottomSheet.getChildAt(1)?.let { if (it is TextView) it.setTextColor(textColor) }

        // 3. "도움이 필요하신가요?" 텍스트: configChanges=uiMode 환경에서는
        //    Activity 재생성이 없으므로 XML 리소스 색상이 자동 갱신되지 않음 → 수동 업데이트
        binding.btnHelp.setTextColor(textColor)

        // 3. 시스템바 색상/아이콘 갱신
        applySystemBarsColor()
    }

    override fun onResume() {
        super.onResume()
        fetchTrips()
        applySystemBarsColor()
        val prefs = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("light_sensor_auto", false) && lightSensor != null) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    // ThemeVibrationSettingsActivity 에서 수동으로 테마를 바꾸고 돌아올 때
    // Android 시스템이 window decoration 을 테마 기본값으로 덮어쓴 뒤 포커스가 옵니다.
    // onResume 보다 늦게 호출되는 onWindowFocusChanged 에서 마지막으로 재적용해야
    // 네비게이션 바 색상이 흰색으로 남는 현상을 막을 수 있습니다.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBarsColor()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    // 조도 센서
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_LIGHT) return
        val prefs = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("light_sensor_auto", false)) return
        val lux = event.values[0]
        val now = System.currentTimeMillis()
        if (now - lastSwitchTime < SWITCH_DEBOUNCE_MS) return
        val shouldBeDark  = lux < DARK_THRESHOLD
        val currentlyDark = isNightMode
        if (shouldBeDark != currentlyDark) {
            lastSwitchTime = now
            prefs.edit().putBoolean("dark_mode", shouldBeDark).apply()
            switchHandler.post {
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    if (shouldBeDark) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    else              androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // 진동 헬퍼
    private fun vibrate(ms: Long = 200) {
        val prefs = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("vibration", false)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else { @Suppress("DEPRECATION") v.vibrate(ms) }
        }
    }

    // =========================================================================
    // 배송 완료 / 취소
    // =========================================================================
    private fun updateTripStatus(tripId: String, status: String) {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("http://swc.ddns.net:8000/trips/$tripId/status?status=$status")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"
                conn.setRequestProperty("Authorization", "Bearer $token")
                if (conn.responseCode in 200..204) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity,
                            "운행이 ${if (status == "completed") "완료" else "취소"}되었습니다.",
                            Toast.LENGTH_SHORT).show()
                        if (status == "completed" || status == "cancelled") {
                            KNSDK.sharedGuidance()?.stop()
                            binding.btnCompleteTrip.visibility = View.GONE
                            currentNaviTripId = null; currentStops.clear(); fetchTrips()
                        }
                    }
                }
            } catch (e: Exception) { }
        }
    }

    private fun completeDelivery(deliveryId: String, name: String) {
        if (deliveryId.isEmpty()) {
            Toast.makeText(this, "배송지 ID가 없습니다.", Toast.LENGTH_SHORT).show()
            currentStops.removeAll { it.name == name }
            binding.btnCompleteTrip.visibility = View.GONE; return
        }
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("http://swc.ddns.net:8000/deliveries/$deliveryId/complete")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"
                conn.setRequestProperty("Authorization", "Bearer $token")
                if (conn.responseCode in 200..204) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "📦 '$name' 배송 완료!", Toast.LENGTH_SHORT).show()
                        currentStops.removeAll { it.id == deliveryId }
                        binding.btnCompleteTrip.visibility = View.GONE
                    }
                }
            } catch (e: Exception) { }
        }
    }

    private fun checkProximityToStops(currentLat: Double, currentLng: Double) {
        var nearbyStop: RouteStop? = null
        for (stop in currentStops) {
            val r = FloatArray(1)
            android.location.Location.distanceBetween(currentLat, currentLng, stop.lat, stop.lng, r)
            if (r[0] <= 100) { nearbyStop = stop; break }
        }
        runOnUiThread {
            if (nearbyStop != null) {
                binding.btnCompleteTrip.visibility = View.VISIBLE
                if (nearbyStop.type == "destination") {
                    binding.btnCompleteTrip.text = "🏁 운행 전체 완료 (목적지 도착)"
                    binding.btnCompleteTrip.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#2E7D32"))
                    binding.btnCompleteTrip.setOnClickListener {
                        currentNaviTripId?.let { updateTripStatus(it, "completed") }
                    }
                } else {
                    binding.btnCompleteTrip.text = "📦 배송지 수동 완료 (${nearbyStop.name})"
                    binding.btnCompleteTrip.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#0288D1"))
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
    // WebSocket
    // =========================================================================
    private fun connectWebSocket() {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: return
        val request = Request.Builder()
            .url("ws://swc.ddns.net:8000/ws/location")
            .addHeader("Authorization", "Bearer $token").build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { Log.d("WS", "연결") }
            @SuppressLint("MissingPermission")
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.optString("type") == "replan_requested") {
                        val message = json.optString("message", "긴급 경유지 추가됨")
                        val tripId  = json.optString("trip_id")
                        val wps     = json.optJSONArray("waypoints") ?: JSONArray()
                        runOnUiThread {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("🚨 경로 변경").setMessage(message)
                                .setPositiveButton("재안내 시작") { _, _ ->
                                    fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                                        if (loc != null) requestReplan(tripId, loc.latitude, loc.longitude, wps)
                                    }
                                }.setCancelable(false).show()
                        }
                    } else {
                        val arr = json.optJSONArray("arrived_deliveries")
                        if (arr != null && arr.length() > 0) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "✅ 배송 자동 완료!", Toast.LENGTH_LONG).show()
                                fetchTrips()
                            }
                        }
                    }
                } catch (e: Exception) { }
            }
        })
    }

    private fun requestReplan(tripId: String, currentLat: Double, currentLng: Double, wps: JSONArray) {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: return
        Toast.makeText(this, "새 경로 계산 중...", Toast.LENGTH_LONG).show()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("http://swc.ddns.net:8000/optimize/replan")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 15000; conn.readTimeout = 15000; conn.doOutput = true
                var dName = "목적지"; var dLat = 0.0; var dLon = 0.0
                val rem = JSONArray()
                if (wps.length() > 0) {
                    val last = wps.getJSONObject(wps.length() - 1)
                    dName = last.optString("name")
                    dLat  = last.optDouble("lat")
                    dLon  = last.optDouble("lon")
                    for (i in 0 until wps.length() - 1) rem.put(wps.getJSONObject(i))
                }
                OutputStreamWriter(conn.outputStream).use {
                    it.write(JSONObject().apply {
                        put("trip_id", tripId); put("current_name", "현재 위치")
                        put("current_lat", currentLat); put("current_lon", currentLng)
                        put("current_drive_sec", 0); put("remaining_waypoints", rem)
                        put("dest_name", dName); put("dest_lat", dLat); put("dest_lon", dLon)
                        put("is_emergency", true); put("route_mode", "auto")
                    }.toString())
                }
                if (conn.responseCode in 200..201)
                    parseAndStartNavi(
                        JSONObject(conn.inputStream.bufferedReader().readText()),
                        currentLat, currentLng, dName, dLat, dLon
                    )
                else withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "재경로 실패", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) { }
        }
    }

    // =========================================================================
    // 운행 목록
    // =========================================================================
    private fun setupRunListUI() { fetchTrips() }

    private fun fetchTrips() {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("http://swc.ddns.net:8000/trips")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                if (conn.responseCode == 200)
                    withContext(Dispatchers.Main) {
                        renderRunList(JSONArray(conn.inputStream.bufferedReader().readText()))
                    }
            } catch (e: Exception) { }
        }
    }

    @SuppressLint("SetTextI18n", "MissingPermission")
    private fun renderRunList(jsonArray: JSONArray) {
        val container = binding.root.findViewById<LinearLayout>(R.id.run_list_container)
        container.removeAllViews()

        val titleColor  = if (isNightMode) Color.parseColor("#E0E0E0") else Color.BLACK
        val statusColor = if (isNightMode) Color.parseColor("#AAAAAA") else Color.DKGRAY

        if (jsonArray.length() == 0) {
            container.addView(TextView(this).apply {
                text = "현재 배정된 배차(Trip)가 없습니다."
                setPadding(20, 20, 20, 20); textSize = 16f; setTextColor(titleColor)
            }); return
        }

        for (i in 0 until jsonArray.length()) {
            val obj      = jsonArray.getJSONObject(i)
            val tripId   = obj.optString("id", "")
            val destName = obj.optString("dest_name", obj.optString("address", "목적지 없음"))
            val lat = obj.optDouble("dest_lat", obj.optDouble("lat", 0.0))
            val lng = obj.optDouble("dest_lon",
                obj.optDouble("dest_lng", obj.optDouble("lon", obj.optDouble("lng", 0.0))))
            val status = obj.optString("status", "대기")

            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 24 }
                setBackgroundResource(android.R.drawable.btn_default)
            }
            itemLayout.addView(TextView(this).apply {
                text = "${i + 1}. $destName"; textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(titleColor)
            })
            itemLayout.addView(TextView(this).apply {
                text = "상태: $status"; textSize = 14f
                setTextColor(statusColor); setPadding(0, 8, 0, 20)
            })

            val btnLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            btnLayout.addView(Button(this).apply {
                text = "안내 시작"
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#03C75A"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    currentNaviTripId = tripId
                    bottomSheetBehavior?.state = BottomSheetBehavior.STATE_COLLAPSED
                    fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                        if (loc != null)
                            optimizeAndStartNavi(tripId, destName, lat, lng, loc.latitude, loc.longitude)
                        else startNavigationWithWGS84(destName, lat, lng)
                    }.addOnFailureListener { startNavigationWithWGS84(destName, lat, lng) }
                }
            })
            btnLayout.addView(Button(this).apply {
                text = "운행 취소"
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = 20 }
                backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#E74C3C"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("운행 취소").setMessage("정말 취소하시겠습니까?")
                        .setPositiveButton("예") { _, _ -> updateTripStatus(tripId, "cancelled") }
                        .setNegativeButton("아니오", null).show()
                }
            })
            itemLayout.addView(btnLayout)
            container.addView(itemLayout)
        }
    }

    // =========================================================================
    // 경로 최적화
    // =========================================================================
    private suspend fun convertWGS84ToKATEC(lat: Double, lng: Double): Pair<Int, Int>? {
        if (lat < 30.0 || lng < 120.0) return null
        return withContext(Dispatchers.IO) {
            try {
                val conn = URL(
                    "https://dapi.kakao.com/v2/local/geo/transcoord.json" +
                    "?x=$lng&y=$lat&input_coord=WGS84&output_coord=KTM"
                ).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "KakaoAK efc9f0b149f1b77d83d1b607ee60837d")
                conn.connectTimeout = 3000; conn.readTimeout = 3000
                if (conn.responseCode == 200) {
                    val docs = JSONObject(conn.inputStream.bufferedReader().readText())
                        .getJSONArray("documents")
                    if (docs.length() > 0)
                        return@withContext Pair(
                            docs.getJSONObject(0).getDouble("x").toInt(),
                            docs.getJSONObject(0).getDouble("y").toInt()
                        )
                }
            } catch (e: Exception) { }
            null
        }
    }

    private fun optimizeAndStartNavi(
        tripId: String, destName: String, destLat: Double, destLng: Double,
        currentLat: Double, currentLng: Double
    ) {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: return
        Toast.makeText(this, "경로 최적화 중...", Toast.LENGTH_LONG).show()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("http://swc.ddns.net:8000/optimize")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 15000; conn.readTimeout = 15000; conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use {
                    it.write(JSONObject().apply {
                        put("trip_id", tripId); put("origin_name", "현재 위치")
                        put("origin_lat", currentLat); put("origin_lon", currentLng)
                        put("initial_drive_sec", 0); put("is_emergency", false)
                    }.toString())
                }
                if (conn.responseCode in 200..201)
                    parseAndStartNavi(
                        JSONObject(conn.inputStream.bufferedReader().readText()),
                        currentLat, currentLng, destName, destLat, destLng
                    )
                else withContext(Dispatchers.Main) {
                    startNavigationWithWGS84(destName, destLat, destLng)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { startNavigationWithWGS84(destName, destLat, destLng) }
            }
        }
    }

    private suspend fun parseAndStartNavi(
        jsonResponse: JSONObject, currentLat: Double, currentLng: Double,
        fallbackDestName: String, fallbackLat: Double, fallbackLng: Double
    ) {
        val arr = jsonResponse.optJSONArray("route")
            ?: jsonResponse.optJSONArray("optimized_route")
            ?: jsonResponse.optJSONArray("waypoints")
        if (arr != null && arr.length() > 0) {
            val vias = mutableListOf<KNPOI>()
            var fName = fallbackDestName; var fLat = fallbackLat; var fLng = fallbackLng
            currentStops.clear()
            for (i in 0 until arr.length()) {
                val pt   = arr.getJSONObject(i)
                val type = pt.optString("type", "")
                val name = pt.optString("name", "경유지${i + 1}")
                val lat  = pt.optDouble("lat", 0.0)
                val lng  = pt.optDouble("lon", pt.optDouble("lng", 0.0))
                val did  = pt.optString("delivery_id", pt.optString("id", ""))
                if (type == "waypoint" || type == "destination")
                    currentStops.add(RouteStop(did, name, lat, lng, type))
                when (type) {
                    "waypoint", "rest_stop" ->
                        convertWGS84ToKATEC(lat, lng)?.let { vias.add(KNPOI(name, it.first, it.second, "")) }
                    "destination" -> { fName = name; fLat = lat; fLng = lng }
                }
            }
            val sk = convertWGS84ToKATEC(currentLat, currentLng)
            val gk = convertWGS84ToKATEC(fLat, fLng)
            if (sk != null && gk != null) {
                val ml = KNSDK.sharedGuidance()?.locationGuide?.location
                val sp = KNPOI("현재 위치",
                    ml?.pos?.x?.toInt() ?: sk.first, ml?.pos?.y?.toInt() ?: sk.second, "")
                val gp = KNPOI(fName, gk.first, gk.second, "")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "최적화 완료! 안내를 시작합니다.", Toast.LENGTH_SHORT).show()
                    startNavigationWithWaypoints(sp, gp, vias)
                }
            } else withContext(Dispatchers.Main) {
                startNavigationWithWGS84(fallbackDestName, fallbackLat, fallbackLng)
            }
        } else withContext(Dispatchers.Main) {
            startNavigationWithWGS84(fallbackDestName, fallbackLat, fallbackLng)
        }
    }

    private fun startNavigationWithWaypoints(start: KNPOI, goal: KNPOI, vias: MutableList<KNPOI>) {
        val guidance = KNSDK.sharedGuidance() ?: return
        guidance.stop()
        KNSDK.makeTripWithStart(start, goal, vias) { _, aTrip ->
            if (aTrip != null) {
                val pri   = KNRoutePriority.KNRoutePriority_Recommand
                val avoid = KNRouteAvoidOption.KNRouteAvoidOption_None.value
                aTrip.routeWithPriority(pri, avoid) { error, _ ->
                    if (error == null) runOnUiThread {
                        binding.naviContainer.removeAllViews()
                        naviView = KNNaviView(this@MainActivity)
                        binding.naviContainer.addView(naviView)
                        applyNaviSettings()
                        guidance.apply {
                            setupDelegates(this)
                            naviView.initWithGuidance(this, aTrip, pri, avoid)
                        }
                    } else Log.e("KNSDK", "탐색 실패: ${error.msg}")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNavigationWithWGS84(name: String, lat: Double, lng: Double) {
        CoroutineScope(Dispatchers.IO).launch {
            if (lat < 30.0 || lng < 120.0) return@launch
            try {
                val conn = URL(
                    "https://dapi.kakao.com/v2/local/geo/transcoord.json" +
                    "?x=$lng&y=$lat&input_coord=WGS84&output_coord=KTM"
                ).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "KakaoAK efc9f0b149f1b77d83d1b607ee60837d")
                conn.connectTimeout = 3000; conn.readTimeout = 3000
                if (conn.responseCode == 200) {
                    val docs = JSONObject(conn.inputStream.bufferedReader().readText())
                        .getJSONArray("documents")
                    if (docs.length() > 0) {
                        val kx = docs.getJSONObject(0).getDouble("x").toInt()
                        val ky = docs.getJSONObject(0).getDouble("y").toInt()
                        withContext(Dispatchers.Main) {
                            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                                if (loc != null) CoroutineScope(Dispatchers.IO).launch {
                                    convertWGS84ToKATEC(loc.latitude, loc.longitude)?.let { sk ->
                                        val ml = KNSDK.sharedGuidance()?.locationGuide?.location
                                        val sp = KNPOI("현재 위치",
                                            ml?.pos?.x?.toInt() ?: sk.first,
                                            ml?.pos?.y?.toInt() ?: sk.second, "")
                                        val gp = KNPOI(name, kx, ky, "")
                                        withContext(Dispatchers.Main) {
                                            startNavigationWithWaypoints(sp, gp, mutableListOf())
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

    private fun applyNaviSettings() {
        if (!::naviView.isInitialized) return
        val sp = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        naviView.useDarkMode = isNightMode
        naviView.fuelType = when (sp.getInt("fuel_type", 0)) {
            2 -> KNCarFuel.KNCarFuel_Diesel
            3 -> KNCarFuel.KNCarFuel_LPG
            4 -> KNCarFuel.KNCarFuel_Electric
            5 -> KNCarFuel.KNCarFuel_HybridElectric
            6 -> KNCarFuel.KNCarFuel_PlugInHybridElectric
            7 -> KNCarFuel.KNCarFuel_Hydrogen
            else -> KNCarFuel.KNCarFuel_Gasoline
        }
        naviView.carType = when (sp.getInt("car_type", 0)) {
            1 -> KNCarType.KNCarType_2; 2 -> KNCarType.KNCarType_3
            3 -> KNCarType.KNCarType_4; 4 -> KNCarType.KNCarType_5
            5 -> KNCarType.KNCarType_6; 6 -> KNCarType.KNCarType_Bike
            else -> KNCarType.KNCarType_1
        }
    }

    // =========================================================================
    // GPS
    // =========================================================================
    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateIntervalMillis(5000).build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    sendLocationToServer(loc.latitude, loc.longitude, loc.speed)
                    checkProximityToStops(loc.latitude, loc.longitude)
                }
            }
        }
        fusedLocationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
    }

    private fun sendLocationToServer(lat: Double, lng: Double, speed: Float) {
        val sp = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        val token  = sp.getString("access_token", null) ?: return
        val userId = sp.getString("user_id", null) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("http://swc.ddns.net:8000/location-logs")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 3000; conn.readTimeout = 3000; conn.doOutput = true
                val jp = JSONObject().apply {
                    put("user_id", userId); put("lat", lat); put("lon", lng); put("speed", speed)
                }
                OutputStreamWriter(conn.outputStream).use { it.write(jp.toString()) }
                conn.responseCode
                webSocket?.send(jp.toString())
            } catch (e: Exception) { }
        }
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION),
                locationPermissionRequestCode)
        else { initKakaoNaviSDK(); startLocationUpdates() }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == locationPermissionRequestCode
            && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) { initKakaoNaviSDK(); startLocationUpdates() }
        else { Toast.makeText(this, "위치 권한이 필요합니다.", Toast.LENGTH_LONG).show(); finish() }
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.cancel()
        sensorManager.unregisterListener(this)
        if (::fusedLocationClient.isInitialized)
            fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    // =========================================================================
    // 카카오 SDK
    // =========================================================================
    private fun initKakaoNaviSDK() {
        KNSDK.initializeWithAppKey(
            aAppKey       = "b57bc6d46e97f480deecdd3a8e4cd754",
            aClientVersion = "1.0",
            aAppUserId    = "test_user",
            aLangType     = KNLanguageType.KNLanguageType_KOREAN,
            aCompletion   = { error ->
                if (error == null) runOnUiThread {
                    naviView = KNNaviView(this@MainActivity)
                    binding.naviContainer.addView(naviView)
                    applyNaviSettings()
                    startSafeDriving()
                }
            }
        )
    }

    private fun setupDelegates(guidance: KNGuidance) {
        guidance.guideStateDelegate    = this; guidance.locationGuideDelegate = this
        guidance.routeGuideDelegate    = this; guidance.safetyGuideDelegate   = this
        guidance.voiceGuideDelegate    = this; guidance.citsGuideDelegate     = this
        naviView.mapComponent.mapView.isVisibleTraffic = true
    }

    private fun startSafeDriving() {
        KNSDK.sharedGuidance()?.apply {
            setupDelegates(this)
            naviView.initWithGuidance(this, null,
                KNRoutePriority.KNRoutePriority_Recommand,
                KNRouteAvoidOption.KNRouteAvoidOption_None.value)
        }
    }

    override fun guidanceGuideEnded(aGuidance: KNGuidance) {
        if (::naviView.isInitialized) naviView.guidanceGuideEnded(aGuidance)
        runOnUiThread {
            Toast.makeText(this@MainActivity, "안내가 종료되었습니다.", Toast.LENGTH_SHORT).show()
            binding.naviContainer.removeAllViews()
            naviView = KNNaviView(this@MainActivity)
            binding.naviContainer.addView(naviView)
            applyNaviSettings()
            startSafeDriving()
        }
    }

    override fun willPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice) {
        if (::naviView.isInitialized) naviView.willPlayVoiceGuide(aGuidance, aVoiceGuide)
        vibrate(150)
    }

    override fun guidanceGuideStarted(aGuidance: KNGuidance) {
        if (::naviView.isInitialized) naviView.guidanceGuideStarted(aGuidance) }
    override fun guidanceCheckingRouteChange(aGuidance: KNGuidance) {
        if (::naviView.isInitialized) naviView.guidanceCheckingRouteChange(aGuidance) }
    override fun guidanceRouteUnchanged(aGuidance: KNGuidance) {
        if (::naviView.isInitialized) naviView.guidanceRouteUnchanged(aGuidance) }
    override fun guidanceRouteUnchangedWithError(aGuidnace: KNGuidance, aError: KNError) {
        if (::naviView.isInitialized) naviView.guidanceRouteUnchangedWithError(aGuidnace, aError) }
    override fun guidanceOutOfRoute(aGuidance: KNGuidance) {
        if (::naviView.isInitialized) naviView.guidanceOutOfRoute(aGuidance) }
    override fun guidanceRouteChanged(
        aGuidance: KNGuidance, f: KNRoute, fl: KNLocation,
        t: KNRoute, tl: KNLocation, r: KNGuideRouteChangeReason) {}
    override fun guidanceDidUpdateRoutes(
        aGuidance: KNGuidance, aRoutes: List<KNRoute>, aMultiRouteInfo: KNMultiRouteInfo?) {
        if (::naviView.isInitialized) naviView.guidanceDidUpdateRoutes(aGuidance, aRoutes, aMultiRouteInfo) }
    override fun guidanceDidUpdateIndoorRoute(aGuidance: KNGuidance, aRoute: KNRoute?) {}
    override fun guidanceDidUpdateLocation(
        aGuidance: KNGuidance, aLocationGuide: KNGuide_Location) {
        if (::naviView.isInitialized) naviView.guidanceDidUpdateLocation(aGuidance, aLocationGuide) }
    override fun guidanceDidUpdateRouteGuide(
        aGuidance: KNGuidance, aRouteGuide: KNGuide_Route) {
        if (::naviView.isInitialized) naviView.guidanceDidUpdateRouteGuide(aGuidance, aRouteGuide) }
    override fun guidanceDidUpdateSafetyGuide(
        aGuidance: KNGuidance, aSafetyGuide: KNGuide_Safety?) {
        if (::naviView.isInitialized) naviView.guidanceDidUpdateSafetyGuide(aGuidance, aSafetyGuide) }
    override fun guidanceDidUpdateAroundSafeties(
        aGuidance: KNGuidance, aSafeties: List<KNSafety>?) {
        if (::naviView.isInitialized) naviView.guidanceDidUpdateAroundSafeties(aGuidance, aSafeties) }
    override fun shouldPlayVoiceGuide(
        aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice,
        aNewData: MutableList<ByteArray>): Boolean =
        if (::naviView.isInitialized)
            naviView.shouldPlayVoiceGuide(aGuidance, aVoiceGuide, aNewData) else false
    override fun didFinishPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice) {
        if (::naviView.isInitialized) naviView.didFinishPlayVoiceGuide(aGuidance, aVoiceGuide) }
    override fun didUpdateCitsGuide(aGuidance: KNGuidance, aCitsGuide: KNGuide_Cits) {
        if (::naviView.isInitialized) naviView.didUpdateCitsGuide(aGuidance, aCitsGuide) }
}
