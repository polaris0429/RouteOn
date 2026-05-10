package com.example.routeon

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
    val lat: Double, val lng: Double,
    val type: String
)

class MainActivity : BaseActivity(),
    KNGuidance_GuideStateDelegate, KNGuidance_LocationGuideDelegate,
    KNGuidance_RouteGuideDelegate, KNGuidance_SafetyGuideDelegate,
    KNGuidance_VoiceGuideDelegate, KNGuidance_CitsGuideDelegate,
    SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var naviView: KNNaviView
    private val permissionRequestCode = 1000

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private val httpClient = OkHttpClient()
    private var webSocket: WebSocket? = null

    private var currentNaviTripId: String? = null
    private val currentStops = mutableListOf<RouteStop>()

    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private val DARK_THRESHOLD = 20f
    private val switchHandler = Handler(Looper.getMainLooper())
    private var lastSwitchTime = 0L
    private val SWITCH_DEBOUNCE_MS = 3000L

    private var bottomSheetBehavior: BottomSheetBehavior<LinearLayout>? = null

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            fetchTrips()
            refreshHandler.postDelayed(this, 5_000)
        }
    }

    private val knownTripStatuses = mutableMapOf<String, String>()
    private var isFirstFetch = true

    // ✅ 다이얼로그 중복/누수 방지 변수
    private var permissionDialog: AlertDialog? = null

    private val isNightMode: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    private fun playSequential(firstRes: Int, secondRes: Int) {
        try {
            val mp1 = MediaPlayer.create(this, firstRes) ?: return
            mp1.setOnCompletionListener { first ->
                first.release()
                try {
                    val mp2 = MediaPlayer.create(this@MainActivity, secondRes) ?: return@setOnCompletionListener
                    mp2.setOnCompletionListener { it.release() }
                    mp2.start()
                } catch (_: Exception) { }
            }
            mp1.start()
        } catch (_: Exception) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        KNSDK.install(application, "$filesDir/knsdk")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarsColor()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        val bsb = BottomSheetBehavior.from(binding.bottomSheet)
        bottomSheetBehavior = bsb
        bsb.isFitToContents = false
        bsb.expandedOffset = (resources.displayMetrics.heightPixels * 0.05).toInt()

        bsb.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                binding.bottomActionBar.visibility =
                    if (newState == BottomSheetBehavior.STATE_EXPANDED) View.VISIBLE else View.GONE
                applySystemBarsColor()
            }
            override fun onSlide(bottomSheet: View, slideOffset: Float) { applySystemBarsColor() }
        })

        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.btnHelp.setOnClickListener { startActivity(Intent(this, HelpActivity::class.java)) }
        binding.btnChat.setOnClickListener { startActivity(Intent(this, ChatActivity::class.java)) }
        binding.btnTripHistory.setOnClickListener { startActivity(Intent(this, TripHistoryActivity::class.java)) }

        connectWebSocket()
        refreshHandler.post(refreshRunnable)

        // ✅ 앱 시작 시 기본 권한부터 한 번에 요청
        requestAllBasicPermissions()
    }

    // ✅ 기본 권한(위치, 전화) 통합 요청
    private fun requestAllBasicPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE)
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), permissionRequestCode)
        } else {
            // 모든 기본 권한이 이미 허용되어 있다면 내비게이션 초기화 및 특수 권한 체크
            initKakaoNaviSDK()
            startLocationUpdates()
            checkSpecialPermissions()
        }
    }

    // ✅ 권한 응답 결과 처리
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == permissionRequestCode) {
            var locationGranted = false
            for (i in permissions.indices) {
                if (permissions[i] == Manifest.permission.ACCESS_FINE_LOCATION && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    locationGranted = true
                }
            }

            if (locationGranted) {
                initKakaoNaviSDK()
                startLocationUpdates()
                // 위치 등 기본 권한을 받았으면 특수 권한(오버레이 등) 체크를 이어서 진행
                checkSpecialPermissions()
            } else {
                Toast.makeText(this, getString(R.string.navi_location_permission), Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    // ✅ 오버레이 및 알림 접근 권한 팝업 제어
    private fun checkSpecialPermissions() {
        if (isFinishing || isDestroyed) return

        val isNotificationEnabled = isNotificationServiceEnabled()
        val isOverlayEnabled = Settings.canDrawOverlays(this)

        if (!isNotificationEnabled || !isOverlayEnabled) {
            if (permissionDialog?.isShowing == true) return

            permissionDialog = AlertDialog.Builder(this)
                .setTitle("필수 권한 설정 안내")
                .setMessage("전화 제어 기능을 사용하려면 아래 두 권한이 반드시 필요합니다.\n\n1. 알림 접근 권한 (RouteOn 허용)\n2. 다른 앱 위에 표시 (RouteOn 허용)")
                .setPositiveButton("설정하러 가기") { _, _ ->
                    if (!isNotificationEnabled) {
                        startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                    } else {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        startActivity(intent)
                    }
                }
                .setCancelable(false)
                .create()

            permissionDialog?.show()
        } else {
            permissionDialog?.dismiss()
            permissionDialog = null
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!flat.isNullOrEmpty()) {
            val names = flat.split(":")
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && cn.packageName == pkgName) {
                    return true
                }
            }
        }
        return false
    }

    private fun applySystemBarsColor() {
        val barColor = if (isNightMode) Color.BLACK else Color.WHITE
        window.statusBarColor = barColor
        window.navigationBarColor = barColor
        val ic = WindowInsetsControllerCompat(window, window.decorView)
        ic.isAppearanceLightStatusBars = !isNightMode
        ic.isAppearanceLightNavigationBars = !isNightMode
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isDark = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (::naviView.isInitialized) naviView.useDarkMode = isDark
        val bgColor = ResourcesCompat.getColor(resources, R.color.bg_bottom_sheet, theme)
        val textColor = ResourcesCompat.getColor(resources, R.color.text_primary, theme)
        val handleColor = ResourcesCompat.getColor(resources, R.color.drag_handle, theme)
        binding.bottomSheet.setBackgroundColor(bgColor)
        binding.bottomSheet.getChildAt(0)?.setBackgroundColor(handleColor)
        binding.bottomSheet.getChildAt(1)?.let { if (it is TextView) it.setTextColor(textColor) }
        binding.btnHelp.setTextColor(textColor)
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

        // 앱으로 돌아왔을 때, 기본 위치 권한이 있다면 특수 권한 팝업 진행 여부 확인
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            checkSpecialPermissions()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBarsColor()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)

        // 🚨 화면 넘어갈 때 메모리 누수(WindowLeaked) 방지
        permissionDialog?.dismiss()
    }

    override fun onDestroy() {
        super.onDestroy()
        refreshHandler.removeCallbacks(refreshRunnable)
        webSocket?.cancel()
        sensorManager.unregisterListener(this)

        // 🚨 액티비티 파괴 시 팝업 닫기
        permissionDialog?.dismiss()
        permissionDialog = null

        // 🚨 위치 콜백 초기화 전에 호출하면 죽는 에러 방지
        if (::fusedLocationClient.isInitialized && ::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_LIGHT) return
        val prefs = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("light_sensor_auto", false)) return
        val lux = event.values[0]
        val now = System.currentTimeMillis()
        if (now - lastSwitchTime < SWITCH_DEBOUNCE_MS) return
        val shouldBeDark = lux < DARK_THRESHOLD
        if (shouldBeDark != isNightMode) {
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

    private fun updateTripStatus(tripId: String, status: String) {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("${Constants.BASE_URL}/trips/$tripId/status?status=$status")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"
                conn.setRequestProperty("Authorization", "Bearer $token")
                if (conn.responseCode in 200..204) {
                    withContext(Dispatchers.Main) {
                        val msg = if (status == "completed") getString(R.string.navi_trip_completed)
                        else getString(R.string.navi_trip_cancelled)
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
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
                val conn = URL("${Constants.BASE_URL}/deliveries/$deliveryId/complete")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"
                conn.setRequestProperty("Authorization", "Bearer $token")
                if (conn.responseCode in 200..204) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity,
                            "📦 '$name' ${getString(R.string.navi_btn_complete_delivery)}",
                            Toast.LENGTH_SHORT).show()
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
                when (nearbyStop.type) {
                    "destination" -> {
                        binding.btnCompleteTrip.text = getString(R.string.navi_btn_complete_dest)
                        binding.btnCompleteTrip.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(Color.parseColor("#2E7D32"))
                        binding.btnCompleteTrip.setOnClickListener {
                            currentNaviTripId?.let { updateTripStatus(it, "completed") }
                        }
                    }
                    "loading" -> {
                        binding.btnCompleteTrip.text = "🚛 상차 완료 (${nearbyStop.name})"
                        binding.btnCompleteTrip.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(Color.parseColor("#E65100"))
                        binding.btnCompleteTrip.setOnClickListener {
                            completeDelivery(nearbyStop.id, nearbyStop.name)
                        }
                    }
                    else -> {
                        binding.btnCompleteTrip.text =
                            "${getString(R.string.navi_btn_complete_delivery)} (${nearbyStop.name})"
                        binding.btnCompleteTrip.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(Color.parseColor("#0288D1"))
                        binding.btnCompleteTrip.setOnClickListener {
                            completeDelivery(nearbyStop.id, nearbyStop.name)
                        }
                    }
                }
            } else {
                binding.btnCompleteTrip.visibility = View.GONE
            }
        }
    }

    private fun connectWebSocket() {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: return
        val request = Request.Builder()
            .url("${Constants.WS_URL}/ws/location")
            .addHeader("Authorization", "Bearer $token").build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { Log.d("WS", "연결") }
            @SuppressLint("MissingPermission")
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.optString("type") == "replan_requested") {
                        val message = json.optString("message", getString(R.string.navi_replan_title))
                        val tripId  = json.optString("trip_id")
                        val wps     = json.optJSONArray("waypoints") ?: JSONArray()
                        runOnUiThread {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle(getString(R.string.navi_replan_title))
                                .setMessage(message)
                                .setPositiveButton(getString(R.string.navi_replan_confirm)) { _, _ ->
                                    fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                                        if (loc != null) requestReplan(tripId, loc.latitude, loc.longitude, wps)
                                    }
                                }.setCancelable(false).show()
                        }
                    } else {
                        val arr = json.optJSONArray("arrived_deliveries")
                        if (arr != null && arr.length() > 0) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity,
                                    "✅ ${getString(R.string.navi_delivery_done)}", Toast.LENGTH_LONG).show()
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
        Toast.makeText(this, getString(R.string.navi_replanning), Toast.LENGTH_LONG).show()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("${Constants.BASE_URL}/optimize/replan")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 30000; conn.readTimeout = 30000; conn.doOutput = true

                var dName = "목적지"; var dLat = 0.0; var dLon = 0.0
                val rem = JSONArray()

                if (wps.length() > 0) {
                    var destIdx = -1
                    for (i in wps.length() - 1 downTo 0) {
                        if (wps.getJSONObject(i).optString("type", "unloading") == "unloading") {
                            destIdx = i; break
                        }
                    }
                    if (destIdx == -1) {
                        for (i in wps.length() - 1 downTo 0) {
                            if (wps.getJSONObject(i).optString("type", "") == "loading") {
                                destIdx = i; break
                            }
                        }
                    }
                    if (destIdx == -1) destIdx = wps.length() - 1

                    val destWp = wps.getJSONObject(destIdx)
                    dName = destWp.optString("name")
                    dLat  = destWp.optDouble("lat")
                    dLon  = destWp.optDouble("lon")

                    for (i in 0 until wps.length()) {
                        if (i == destIdx) continue
                        val wp = wps.getJSONObject(i)
                        rem.put(JSONObject().apply {
                            put("name", wp.optString("name"))
                            put("lat",  wp.optDouble("lat"))
                            put("lon",  wp.optDouble("lon"))
                            val t = wp.optString("type", "")
                            if (t.isNotEmpty()) put("type", t)
                        })
                    }
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
                    parseAndStartNavi(JSONObject(conn.inputStream.bufferedReader().readText()),
                        currentLat, currentLng, dName, dLat, dLon)
                else withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.navi_optimize_fail), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) { }
        }
    }

    private fun fetchTrips() {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("${Constants.BASE_URL}/trips").openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                if (conn.responseCode == 200) {
                    val jsonArray = JSONArray(conn.inputStream.bufferedReader().readText())
                    withContext(Dispatchers.Main) { processTripsUpdate(jsonArray) }
                }
            } catch (e: Exception) { }
        }
    }

    private fun processTripsUpdate(jsonArray: JSONArray) {
        val newStatuses = mutableMapOf<String, String>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val id  = obj.optString("id", "")
            val st  = obj.optString("status", "")
            if (id.isNotEmpty()) newStatuses[id] = st
        }
        if (!isFirstFetch) {
            for ((id, status) in newStatuses) {
                if (id !in knownTripStatuses && status in listOf("scheduled", "in_progress")) {
                    playSequential(R.raw.bell, R.raw.trip_new); vibrate(200); break
                }
            }
            for ((id, oldSt) in knownTripStatuses) {
                if (oldSt !in listOf("cancelled","completed") && newStatuses[id] == "cancelled") {
                    playSequential(R.raw.bell, R.raw.trip_cancel); vibrate(200)
                }
            }
            for ((id, oldSt) in knownTripStatuses) {
                if (oldSt !in listOf("cancelled","completed") && newStatuses[id] == "completed") {
                    playSequential(R.raw.bell, R.raw.trip_complite); vibrate(200)
                }
            }
        }
        knownTripStatuses.clear(); knownTripStatuses.putAll(newStatuses)
        isFirstFetch = false
        renderRunList(jsonArray)
    }

    @SuppressLint("SetTextI18n", "MissingPermission")
    private fun renderRunList(jsonArray: JSONArray) {
        val container = binding.root.findViewById<LinearLayout>(R.id.run_list_container)
        container.removeAllViews()
        val titleColor  = if (isNightMode) Color.parseColor("#E0E0E0") else Color.BLACK
        val statusColor = if (isNightMode) Color.parseColor("#AAAAAA") else Color.DKGRAY

        val activeItems = mutableListOf<JSONObject>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val st  = obj.optString("status", "")
            if (st != "cancelled" && st != "completed") activeItems.add(obj)
        }

        if (activeItems.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.navi_no_trips)
                setPadding(20, 20, 20, 20); textSize = 16f; setTextColor(titleColor)
            }); return
        }

        activeItems.forEachIndexed { index, obj ->
            val tripId = obj.optString("id", "")
            val rawDestName = obj.optString("dest_name", "")
            val rawDestLat  = obj.optDouble("dest_lat", 0.0)
            val rawDestLon  = obj.optDouble("dest_lon", 0.0)

            val loadingCount   = obj.optInt("loading_count", 0)
            val unloadingCount = obj.optInt("unloading_count", 0)

            val displayName = rawDestName.takeIf { it.isNotEmpty() } ?: run {
                val parts = mutableListOf<String>()
                if (loadingCount > 0)   parts.add("상차지 ${loadingCount}건")
                if (unloadingCount > 0) parts.add("하차지 ${unloadingCount}건")
                parts.joinToString(" / ").takeIf { it.isNotEmpty() } ?: "경유지 운행"
            }
            val status = obj.optString("status", "대기")

            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 24 }
                setBackgroundResource(android.R.drawable.btn_default)
            }
            itemLayout.addView(TextView(this).apply {
                text = "${index + 1}. $displayName"; textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(titleColor)
            })

            if (rawDestName.isNotEmpty() && (loadingCount > 0 || unloadingCount > 0)) {
                itemLayout.addView(TextView(this).apply {
                    val parts = mutableListOf<String>()
                    if (loadingCount > 0)   parts.add("상차 ${loadingCount}건")
                    if (unloadingCount > 0) parts.add("하차 ${unloadingCount}건")
                    text = parts.joinToString(" · ")
                    textSize = 12f; setTextColor(Color.parseColor("#FF8F00"))
                    setPadding(0, 4, 0, 0)
                })
            }

            itemLayout.addView(TextView(this).apply {
                text = "상태: $status"; textSize = 14f
                setTextColor(statusColor); setPadding(0, 8, 0, 20)
            })
            val btnLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            btnLayout.addView(Button(this).apply {
                text = getString(R.string.navi_btn_cancel_trip)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginEnd = 20 }
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E74C3C"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.navi_cancel_confirm_title))
                        .setMessage(getString(R.string.navi_cancel_confirm_message))
                        .setPositiveButton(getString(R.string.navi_yes)) { _, _ ->
                            updateTripStatus(tripId, "cancelled")
                        }
                        .setNegativeButton(getString(R.string.navi_no), null)
                        .show()
                }
            })

            btnLayout.addView(Button(this).apply {
                text = getString(R.string.navi_btn_start)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#03C75A"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    currentNaviTripId = tripId
                    bottomSheetBehavior?.state = BottomSheetBehavior.STATE_COLLAPSED
                    fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                        optimizeAndStartNavi(
                            tripId, displayName, rawDestLat, rawDestLon,
                            loc?.latitude, loc?.longitude
                        )
                    }.addOnFailureListener {
                        optimizeAndStartNavi(tripId, displayName, rawDestLat, rawDestLon, null, null)
                    }
                }
            })
            itemLayout.addView(btnLayout)
            container.addView(itemLayout)
        }
    }

    private suspend fun convertWGS84ToKATEC(lat: Double, lng: Double): Pair<Int, Int>? {
        if (lat < 30.0 || lng < 120.0) return null
        return withContext(Dispatchers.IO) {
            try {
                val conn = URL("https://dapi.kakao.com/v2/local/geo/transcoord.json?x=$lng&y=$lat&input_coord=WGS84&output_coord=KTM")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "KakaoAK efc9f0b149f1b77d83d1b607ee60837d")
                conn.connectTimeout = 3000; conn.readTimeout = 3000
                if (conn.responseCode == 200) {
                    val docs = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONArray("documents")
                    if (docs.length() > 0)
                        return@withContext Pair(
                            docs.getJSONObject(0).getDouble("x").toInt(),
                            docs.getJSONObject(0).getDouble("y").toInt())
                }
            } catch (e: Exception) { }
            null
        }
    }

    private fun optimizeAndStartNavi(
        tripId: String, destName: String, destLat: Double, destLng: Double,
        currentLat: Double?, currentLng: Double?
    ) {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getString("access_token", null) ?: return
        Toast.makeText(this, getString(R.string.navi_optimizing), Toast.LENGTH_LONG).show()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("${Constants.BASE_URL}/optimize").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 30000; conn.readTimeout = 30000; conn.doOutput = true

                OutputStreamWriter(conn.outputStream).use {
                    it.write(JSONObject().apply {
                        put("trip_id", tripId)
                        if (currentLat != null && currentLng != null) {
                            put("origin_name", "현재 위치")
                            put("origin_lat",  currentLat)
                            put("origin_lon",  currentLng)
                        }
                        put("initial_drive_sec", 0)
                        put("is_emergency", false)
                    }.toString())
                }

                if (conn.responseCode in 200..201) {
                    parseAndStartNavi(
                        JSONObject(conn.inputStream.bufferedReader().readText()),
                        currentLat ?: 0.0, currentLng ?: 0.0,
                        destName, destLat, destLng
                    )
                } else withContext(Dispatchers.Main) {
                    if (destLat != 0.0 && destLng != 0.0)
                        startNavigationWithWGS84(destName, destLat, destLng)
                    else
                        Toast.makeText(this@MainActivity,
                            getString(R.string.navi_optimize_fail), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (destLat != 0.0 && destLng != 0.0)
                        startNavigationWithWGS84(destName, destLat, destLng)
                    else
                        Toast.makeText(this@MainActivity,
                            getString(R.string.navi_optimize_fail), Toast.LENGTH_SHORT).show()
                }
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
                val pt = arr.getJSONObject(i)
                val rawType  = pt.optString("type", "")
                val nodeType = pt.optString("node_type", "")

                val effectiveType = when {
                    nodeType.isNotEmpty() -> nodeType
                    rawType in listOf("loading", "unloading") -> rawType
                    else -> rawType
                }

                val name = pt.optString("name", "경유지${i+1}")
                val lat  = pt.optDouble("lat", 0.0)
                val lng  = pt.optDouble("lon", pt.optDouble("lng", 0.0))
                val did  = pt.optString("delivery_id", pt.optString("id", ""))

                if (rawType != "origin") {
                    currentStops.add(RouteStop(did, name, lat, lng, effectiveType))
                }

                when (rawType) {
                    "loading", "unloading", "waypoint", "rest_stop" ->
                        convertWGS84ToKATEC(lat, lng)?.let {
                            vias.add(KNPOI(name, it.first, it.second, ""))
                        }
                    "destination" -> { fName = name; fLat = lat; fLng = lng }
                }
            }

            if (currentStops.none { it.type == "destination" } && currentStops.isNotEmpty()) {
                val last = currentStops.last()
                currentStops[currentStops.lastIndex] = last.copy(type = "destination")
                if (fLat == fallbackLat && fLng == fallbackLng && last.lat != 0.0) {
                    fName = last.name; fLat = last.lat; fLng = last.lng
                    if (vias.isNotEmpty()) vias.removeAt(vias.lastIndex)
                }
            }

            val sk = convertWGS84ToKATEC(currentLat, currentLng)
            val gk = convertWGS84ToKATEC(fLat, fLng)
            if (sk != null && gk != null) {
                val ml = KNSDK.sharedGuidance()?.locationGuide?.location
                val sp = KNPOI("현재 위치", ml?.pos?.x?.toInt() ?: sk.first, ml?.pos?.y?.toInt() ?: sk.second, "")
                val gp = KNPOI(fName, gk.first, gk.second, "")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.navi_optimized), Toast.LENGTH_SHORT).show()
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

        val limitedVias = if (vias.size > 10) {
            runOnUiThread {
                Toast.makeText(this, "경유지가 많아 가까운 10개까지만 먼저 안내합니다.", Toast.LENGTH_LONG).show()
            }
            vias.take(10).toMutableList()
        } else {
            vias
        }

        KNSDK.makeTripWithStart(start, goal, limitedVias) { error, aTrip ->
            if (aTrip != null) {
                val pri   = KNRoutePriority.KNRoutePriority_Recommand
                val avoid = KNRouteAvoidOption.KNRouteAvoidOption_None.value
                aTrip.routeWithPriority(pri, avoid) { routeError, _ ->
                    if (routeError == null) runOnUiThread {
                        binding.naviContainer.removeAllViews()
                        naviView = KNNaviView(this@MainActivity)
                        binding.naviContainer.addView(naviView)
                        applyNaviSettings()
                        guidance.apply {
                            setupDelegates(this)
                            naviView.initWithGuidance(this, aTrip, pri, avoid)
                        }
                    } else {
                        Log.e("KNSDK", "탐색 실패: ${routeError.msg}")
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "경로 탐색 실패: ${routeError.msg}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } else {
                Log.e("KNSDK", "Trip 생성 실패: ${error?.msg}")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "경로 생성 실패: ${error?.msg}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNavigationWithWGS84(name: String, lat: Double, lng: Double) {
        CoroutineScope(Dispatchers.IO).launch {
            if (lat < 30.0 || lng < 120.0) return@launch
            try {
                val conn = URL("https://dapi.kakao.com/v2/local/geo/transcoord.json?x=$lng&y=$lat&input_coord=WGS84&output_coord=KTM")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "KakaoAK efc9f0b149f1b77d83d1b607ee60837d")
                conn.connectTimeout = 3000; conn.readTimeout = 3000
                if (conn.responseCode == 200) {
                    val docs = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONArray("documents")
                    if (docs.length() > 0) {
                        val kx = docs.getJSONObject(0).getDouble("x").toInt()
                        val ky = docs.getJSONObject(0).getDouble("y").toInt()
                        withContext(Dispatchers.Main) {
                            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                                if (loc != null) CoroutineScope(Dispatchers.IO).launch {
                                    convertWGS84ToKATEC(loc.latitude, loc.longitude)?.let { sk ->
                                        val ml = KNSDK.sharedGuidance()?.locationGuide?.location
                                        val sp = KNPOI("현재 위치",
                                            ml?.pos?.x?.toInt() ?: sk.first, ml?.pos?.y?.toInt() ?: sk.second, "")
                                        withContext(Dispatchers.Main) {
                                            startNavigationWithWaypoints(sp, KNPOI(name, kx, ky, ""), mutableListOf())
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
            2 -> KNCarFuel.KNCarFuel_Diesel;       3 -> KNCarFuel.KNCarFuel_LPG
            4 -> KNCarFuel.KNCarFuel_Electric;     5 -> KNCarFuel.KNCarFuel_HybridElectric
            6 -> KNCarFuel.KNCarFuel_PlugInHybridElectric; 7 -> KNCarFuel.KNCarFuel_Hydrogen
            else -> KNCarFuel.KNCarFuel_Gasoline
        }
        naviView.carType = when (sp.getInt("car_type", 0)) {
            1 -> KNCarType.KNCarType_2; 2 -> KNCarType.KNCarType_3; 3 -> KNCarType.KNCarType_4
            4 -> KNCarType.KNCarType_5; 5 -> KNCarType.KNCarType_6; 6 -> KNCarType.KNCarType_Bike
            else -> KNCarType.KNCarType_1
        }
    }

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
        val sp     = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        val token  = sp.getString("access_token", null) ?: return
        val userId = sp.getString("user_id", null) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("${Constants.BASE_URL}/location-logs").openConnection() as HttpURLConnection
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

    private fun initKakaoNaviSDK() {
        val prefs    = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        val langCode = prefs.getString("language", "ko") ?: "ko"
        val knLang   = if (langCode == "en") KNLanguageType.KNLanguageType_ENGLISH
        else KNLanguageType.KNLanguageType_KOREAN
        KNSDK.initializeWithAppKey(
            aAppKey        = "b57bc6d46e97f480deecdd3a8e4cd754",
            aClientVersion = "1.0",
            aAppUserId     = "test_user",
            aLangType      = knLang,
            aCompletion    = { error ->
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
        guidance.guideStateDelegate = this;  guidance.locationGuideDelegate = this
        guidance.routeGuideDelegate = this;  guidance.safetyGuideDelegate   = this
        guidance.voiceGuideDelegate = this;  guidance.citsGuideDelegate     = this
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
            Toast.makeText(this@MainActivity, getString(R.string.navi_ended), Toast.LENGTH_SHORT).show()
            binding.naviContainer.removeAllViews()
            naviView = KNNaviView(this@MainActivity)
            binding.naviContainer.addView(naviView)
            applyNaviSettings(); startSafeDriving()
        }
    }

    override fun willPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice) {
        if (::naviView.isInitialized) naviView.willPlayVoiceGuide(aGuidance, aVoiceGuide); vibrate(150) }
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
    override fun guidanceRouteChanged(aGuidance: KNGuidance, f: KNRoute, fl: KNLocation,
                                      t: KNRoute, tl: KNLocation, r: KNGuideRouteChangeReason) {}
    override fun guidanceDidUpdateRoutes(aGuidance: KNGuidance, aRoutes: List<KNRoute>, aMultiRouteInfo: KNMultiRouteInfo?) {
        if (::naviView.isInitialized) naviView.guidanceDidUpdateRoutes(aGuidance, aRoutes, aMultiRouteInfo) }
    override fun guidanceDidUpdateIndoorRoute(aGuidance: KNGuidance, aRoute: KNRoute?) {}
    override fun guidanceDidUpdateLocation(aGuidance: KNGuidance, aLocationGuide: KNGuide_Location) {
        if (::naviView.isInitialized) naviView.guidanceDidUpdateLocation(aGuidance, aLocationGuide) }
    override fun guidanceDidUpdateRouteGuide(aGuidance: KNGuidance, aRouteGuide: KNGuide_Route) {
        if (::naviView.isInitialized) naviView.guidanceDidUpdateRouteGuide(aGuidance, aRouteGuide) }
    override fun guidanceDidUpdateSafetyGuide(aGuidance: KNGuidance, aSafetyGuide: KNGuide_Safety?) {
        if (::naviView.isInitialized) naviView.guidanceDidUpdateSafetyGuide(aGuidance, aSafetyGuide) }
    override fun guidanceDidUpdateAroundSafeties(aGuidance: KNGuidance, aSafeties: List<KNSafety>?) {
        if (::naviView.isInitialized) naviView.guidanceDidUpdateAroundSafeties(aGuidance, aSafeties) }
    override fun shouldPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice, aNewData: MutableList<ByteArray>): Boolean =
        if (::naviView.isInitialized) naviView.shouldPlayVoiceGuide(aGuidance, aVoiceGuide, aNewData) else false
    override fun didFinishPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice) {
        if (::naviView.isInitialized) naviView.didFinishPlayVoiceGuide(aGuidance, aVoiceGuide) }
    override fun didUpdateCitsGuide(aGuidance: KNGuidance, aCitsGuide: KNGuide_Cits) {
        if (::naviView.isInitialized) naviView.didUpdateCitsGuide(aGuidance, aCitsGuide) }
}