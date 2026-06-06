package com.example.routeon

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ActivityScenario
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
import com.kakaomobility.knsdk.guidance.knguidance.KNGuideState
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
import com.kakaomobility.knsdk.ui.view.KNNaviView_GuideStateDelegate
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
import java.util.Locale

data class RouteStop(
    val id: String, val name: String,
    val lat: Double, val lng: Double,
    val type: String
)

data class PendingReplan(val tripId: String, val message: String, val waypoints: JSONArray)

class MainActivity : BaseActivity(),
    KNGuidance_GuideStateDelegate, KNGuidance_LocationGuideDelegate,
    KNGuidance_RouteGuideDelegate, KNGuidance_SafetyGuideDelegate,
    KNGuidance_VoiceGuideDelegate, KNGuidance_CitsGuideDelegate,
    KNNaviView_GuideStateDelegate,
    SensorEventListener,
    BaseActivity.DevRestModeCallback,
    BaseActivity.DemoCallback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var naviView: KNNaviView
    private val permissionRequestCode = 1000

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private val httpClient = OkHttpClient()
    private var webSocket: WebSocket? = null

    private val wsHttpClient = OkHttpClient.Builder()
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .writeTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

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
    private var backPressedTime: Long = 0

    private var isActivityResumed = false
    @Volatile private var pendingReplan: PendingReplan? = null

    private var isRestStopActive = false
    private var restStopCountDown: CountDownTimer? = null
    private val visitedRestStopKeys = mutableSetOf<String>()
    private val REST_STOP_RADIUS_M = 150f
    private val REST_STOP_EXIT_RADIUS_M = 200f   // 이 반경 벗어나면 휴식 강제 취소
    private val REST_STOP_DURATION_MS = 15 * 60 * 1000L
    private val REST_STOP_DEMO_DURATION_MS = 10 * 1000L
    private var activeRestStopLat: Double = 0.0   // 현재 휴식 중인 휴게소 좌표
    private var activeRestStopLng: Double = 0.0
    private var autoCompleteTriggered = false        // 최종 목적지 자동 완료 중복 방지
    private var suppressCompleteSoundOnce = false    // 자동 완료 시 processTripsUpdate 중복 음향 방지

    // ─── 휴게소 오버레이 일시 숨김 관련 ───────────────────────────────────────
    private val restOverlayHandler = Handler(Looper.getMainLooper())
    private var isOverlayTemporarilyHidden = false
    private val OVERLAY_RESHOW_DELAY_MS = 5_000L  // 5초 무입력 후 재표시
    private val restOverlayReshowRunnable = Runnable { reshowRestStopOverlay() }

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

    private var lastLat: Double = 0.0
    private var lastLng: Double = 0.0
    private var isLocationWsReconnecting = false

    private var acceptedTripId: String? = null
    private var acceptedOriginName: String = "실내 위치"
    private var acceptedOriginLat: Double = 0.0
    private var acceptedOriginLon: Double = 0.0

    private val expandedTripIds = mutableSetOf<String>()
    private val startedTripIds  = mutableSetOf<String>()   // 한 번이라도 안내를 시작한 배차(→ + 버튼 숨김 + "경로 재계산")
    private val currentStopPhase = mutableMapOf<String, String>()  // key=stop.id, value=phase (v1.0.76)
    private val tripOriginText  = mutableMapOf<String, String>()
    private val tripDestText    = mutableMapOf<String, String>()
    private val tripOriginCoords = mutableMapOf<String, DoubleArray>()
    private val tripDestCoords   = mutableMapOf<String, DoubleArray>()

    // ─── 개발자 모드: GPX 기록 + 데모 시뮬레이터 ──────────────────────────────
    private val gpxRecorder  by lazy { GpxRecorder(this) }
    private val demoPlayer   by lazy { DemoScenarioPlayer(this) }

    // ─── TripPreviewActivity 결과 처리: 수락 버튼 클릭 시 돌아옴 ───────────────
    private val tripPreviewLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val acceptedId = result.data?.getStringExtra("accepted_trip_id")
            if (!acceptedId.isNullOrEmpty()) {
                acceptedTripId = acceptedId
                fetchTrips()
            }
        }
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

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (bottomSheetBehavior?.state == BottomSheetBehavior.STATE_EXPANDED) {
                    bottomSheetBehavior?.state = BottomSheetBehavior.STATE_COLLAPSED; return
                }
                if (System.currentTimeMillis() - backPressedTime < 2000) finish()
                else { Toast.makeText(this@MainActivity, "한 번 더 누르면 종료됩니다.", Toast.LENGTH_SHORT).show(); backPressedTime = System.currentTimeMillis() }
            }
        })

        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.btnHelp.setOnClickListener { startActivity(Intent(this, HelpActivity::class.java)) }
        binding.btnChat.setOnClickListener { startActivity(Intent(this, ChatActivity::class.java)) }
        binding.btnTripHistory.setOnClickListener { startActivity(Intent(this, TripHistoryActivity::class.java)) }

        connectWebSocket()
        refreshHandler.post(refreshRunnable)
        requestAllBasicPermissions()
    }

    private fun requestAllBasicPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (permissionsToRequest.isNotEmpty())
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), permissionRequestCode)
        else { initKakaoNaviSDK(); startLocationUpdates() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            val locationGranted = permissions.indices.any {
                permissions[it] == Manifest.permission.ACCESS_FINE_LOCATION &&
                grantResults[it] == PackageManager.PERMISSION_GRANTED
            }
            if (locationGranted) { initKakaoNaviSDK(); startLocationUpdates() }
            else { Toast.makeText(this, getString(R.string.navi_location_permission), Toast.LENGTH_LONG).show(); finish() }
        }
    }

    private fun checkSpecialPermissions() {
        // 전화 오버레이 기능 제거됨 — 이 함수는 더 이상 호출되지 않음
    }

    private fun isNotificationServiceEnabled(): Boolean = false

    private fun applySystemBarsColor() {
        val barColor = if (isNightMode) Color.BLACK else Color.WHITE
        window.statusBarColor = barColor; window.navigationBarColor = barColor
        val ic = WindowInsetsControllerCompat(window, window.decorView)
        ic.isAppearanceLightStatusBars = !isNightMode; ic.isAppearanceLightNavigationBars = !isNightMode
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
        isActivityResumed = true
        fetchTrips()
        applySystemBarsColor()
        val prefs = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("light_sensor_auto", false) && lightSensor != null)
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
        val replan = pendingReplan
        if (replan != null) {
            pendingReplan = null
            Log.d("LocationWS", "▶ onResume: 대기 중이던 replan 팝업 표시")
            showReplanDialog(replan.tripId, replan.message, replan.waypoints)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBarsColor()
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        refreshHandler.removeCallbacks(refreshRunnable)
        webSocket?.cancel()
        sensorManager.unregisterListener(this)
        restStopCountDown?.cancel(); restStopCountDown = null
        restOverlayHandler.removeCallbacks(restOverlayReshowRunnable)
        demoPlayer.stop()
        if (::fusedLocationClient.isInitialized && ::locationCallback.isInitialized)
            fusedLocationClient.removeLocationUpdates(locationCallback)
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
                    else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * 엔드 유저 터치/키 입력 시 호출 → 오버레이 일시 숨김 중이면 5초 타이머 리셋
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        if (isRestStopActive && isOverlayTemporarilyHidden) {
            restOverlayHandler.removeCallbacks(restOverlayReshowRunnable)
            restOverlayHandler.postDelayed(restOverlayReshowRunnable, OVERLAY_RESHOW_DELAY_MS)
        }
    }

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
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).getString("access_token", null) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("${Constants.BASE_URL}/trips/$tripId/status?status=$status").openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"; conn.setRequestProperty("Authorization", "Bearer $token")
                if (conn.responseCode in 200..204) {
                    withContext(Dispatchers.Main) {
                        val msg = if (status == "completed") getString(R.string.navi_trip_completed) else getString(R.string.navi_trip_cancelled)
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                        if (status == "completed" || status == "cancelled") {
                            KNSDK.sharedGuidance()?.stop()
                            binding.btnCompleteTrip.visibility = View.GONE
                            autoCompleteTriggered = false
                            currentNaviTripId = null; currentStops.clear(); currentStopPhase.clear(); fetchTrips()
                        }
                    }
                }
            } catch (e: Exception) { }
        }
    }

    /**
     * PATCH /trips/{id}/progress — 세부 운행 단계 기록 (v1.0.76)
     *
     * @param tripId    현재 운행 ID
     * @param phase     단계 문자열 (nullable — event 방식도 지원)
     * @param waypointIndex waypoint 배열 인덱스 (nullable)
     * @param event     "arrived" | "departed" | "completed" (nullable)
     */
    private fun sendTripProgress(
        tripId: String,
        phase: String? = null,
        waypointIndex: Int? = null,
        event: String? = null
    ) {
        val token = getSharedPreferences("RouteOnPrefs", android.content.Context.MODE_PRIVATE)
            .getString("access_token", null) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("${Constants.BASE_URL}/trips/$tripId/progress").openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 8000; conn.readTimeout = 8000; conn.doOutput = true
                val body = JSONObject().apply {
                    if (phase != null)         put("phase", phase)
                    if (waypointIndex != null) put("waypoint_index", waypointIndex)
                    if (event != null)         put("event", event)
                }
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                val code = conn.responseCode
                Log.d("TripProgress", "▶ PATCH /trips/$tripId/progress → HTTP $code (phase=$phase, wp=$waypointIndex, event=$event)")
            } catch (e: Exception) {
                Log.e("TripProgress", "❌ progress 전송 오류: ${e.message}")
            }
        }
    }

    private fun completeDelivery(deliveryId: String, name: String) {
        if (deliveryId.isEmpty()) {
            Toast.makeText(this, "배송지 ID가 없습니다.", Toast.LENGTH_SHORT).show()
            currentStops.removeAll { it.name == name }; binding.btnCompleteTrip.visibility = View.GONE; return
        }
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).getString("access_token", null) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("${Constants.BASE_URL}/deliveries/$deliveryId/complete").openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"; conn.setRequestProperty("Authorization", "Bearer $token")
                if (conn.responseCode in 200..204) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "📦 '$name' ${getString(R.string.navi_btn_complete_delivery)}", Toast.LENGTH_SHORT).show()
                        currentStops.removeAll { it.id == deliveryId }; binding.btnCompleteTrip.visibility = View.GONE
                    }
                }
            } catch (e: Exception) { }
        }
    }

    private fun showRestStopOverlay(stopName: String, stopLat: Double = 0.0, stopLng: Double = 0.0) {
        if (isRestStopActive) return
        isRestStopActive = true
        isOverlayTemporarilyHidden = false
        activeRestStopLat = stopLat
        activeRestStopLng = stopLng
        val isDemoMode = DeveloperModeManager.isEnabled(this)
        val durationMs = if (isDemoMode) REST_STOP_DEMO_DURATION_MS else REST_STOP_DURATION_MS
        val warnThresholdSec = if (isDemoMode) 5L else 60L
        binding.btnCompleteTrip.visibility = View.GONE
        binding.tvRestStopName.text = if (stopName.isNotBlank()) stopName else "휴게소"
        binding.tvRestTimer.text = if (isDemoMode) "00:10" else "15:00"
        binding.tvRestTimer.setTextColor(Color.parseColor("#4CAF50"))
        if (isDemoMode) {
            binding.tvRestTimerLabel.text = "남은 휴식 시간 [개발자 데모 — 10초]"
            binding.btnRestStopSkip.visibility = View.VISIBLE
            binding.btnRestStopSkip.setOnClickListener { restStopCountDown?.cancel(); hideRestStopOverlay() }
        } else {
            binding.tvRestTimerLabel.text = "남은 휴식 시간"; binding.btnRestStopSkip.visibility = View.GONE
        }

        // ── 반투명 배경 터치 시 일시 숨김 ─────────────────────────────────
        binding.restStopDimLayer.setOnClickListener {
            if (!isOverlayTemporarilyHidden) temporarilyHideRestStopOverlay()
        }

        binding.restStopOverlay.alpha = 0f; binding.restStopOverlay.visibility = View.VISIBLE
        binding.restStopOverlay.animate().alpha(1f).setDuration(450).start()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300), -1))
            else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300), -1))
                else { @Suppress("DEPRECATION") v.vibrate(longArrayOf(0, 300, 200, 300), -1) }
            }
        } catch (_: Exception) { }
        restStopCountDown?.cancel()
        restStopCountDown = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val totalSec = millisUntilFinished / 1000
                binding.tvRestTimer.text = String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)
                if (totalSec <= warnThresholdSec) binding.tvRestTimer.setTextColor(Color.parseColor("#FF9800"))
            }
            override fun onFinish() { binding.tvRestTimer.text = "00:00"; hideRestStopOverlay() }
        }.start()
        Log.d("RestStop", "✅ 휴게소 진입: $stopName — 타이머 시작 (데모: $isDemoMode)")
    }

    override fun triggerRestModeDemo() { showRestStopOverlay("데모 휴게소") }

    /** 히식 올립 배경 터치 → 오버레이 일시 숨김 + 5초 후 재표시 */
    private fun temporarilyHideRestStopOverlay() {
        if (!isRestStopActive || isOverlayTemporarilyHidden) return
        isOverlayTemporarilyHidden = true
        restOverlayHandler.removeCallbacks(restOverlayReshowRunnable)
        // 오버레이 페이드아웃
        binding.restStopOverlay.animate().alpha(0f).setDuration(250).withEndAction {
            binding.restStopOverlay.visibility = View.GONE
            Log.d("RestStop", "부 터치 → 오버레이 일시 하포")
        }.start()
        // 5초 후 자동 재표시 예약
        restOverlayHandler.postDelayed(restOverlayReshowRunnable, OVERLAY_RESHOW_DELAY_MS)
    }

    /** 5초 무입력 후 또는 상태목록에서 오버레이 재표시 */
    private fun reshowRestStopOverlay() {
        if (!isRestStopActive || !isOverlayTemporarilyHidden) return
        isOverlayTemporarilyHidden = false
        binding.restStopOverlay.alpha = 0f
        binding.restStopOverlay.visibility = View.VISIBLE
        binding.restStopOverlay.animate().alpha(1f).setDuration(350).start()
        Log.d("RestStop", "5초 무입력 → 오버레이 재표시")
    }

    private fun hideRestStopOverlay() {
        isRestStopActive = false; restStopCountDown?.cancel(); restStopCountDown = null
        isOverlayTemporarilyHidden = false
        activeRestStopLat = 0.0; activeRestStopLng = 0.0
        restOverlayHandler.removeCallbacks(restOverlayReshowRunnable)
        binding.tvRestTimer.setTextColor(Color.parseColor("#4CAF50"))
        binding.restStopOverlay.animate().alpha(0f).setDuration(600).withEndAction { binding.restStopOverlay.visibility = View.GONE }.start()
        Toast.makeText(this, "✅ 휴식이 완료되었습니다. 안전 운행하세요! 🚛", Toast.LENGTH_LONG).show()
        Log.d("RestStop", "✅ 휴식 완료 — 네비 복구")
    }

    /** 휴식 중 휴게소 이탈 시 강제 취소 (음향 재생은 호출자가 이미 실행) */
    private fun forceCancelRestStop() {
        isRestStopActive = false; restStopCountDown?.cancel(); restStopCountDown = null
        isOverlayTemporarilyHidden = false
        activeRestStopLat = 0.0; activeRestStopLng = 0.0
        restOverlayHandler.removeCallbacks(restOverlayReshowRunnable)
        binding.tvRestTimer.setTextColor(Color.parseColor("#4CAF50"))
        binding.restStopOverlay.animate().alpha(0f).setDuration(300).withEndAction { binding.restStopOverlay.visibility = View.GONE }.start()
        Log.w("RestStop", "⚠️ 휴식 강제 취소 완료")
    }

    private fun checkProximityToStops(currentLat: Double, currentLng: Double) {
        // ── 1. 휴게소 근접 체크 ──────────────────────────────────────────────
        if (!isRestStopActive) {
            for (stop in currentStops) {
                if (stop.type != "rest_stop") continue
                val key = "${stop.lat}_${stop.lng}"
                if (key in visitedRestStopKeys) continue
                val dist = FloatArray(1)
                android.location.Location.distanceBetween(currentLat, currentLng, stop.lat, stop.lng, dist)
                if (dist[0] <= REST_STOP_RADIUS_M) {
                    visitedRestStopKeys.add(key)
                    Log.d("RestStop", "📍 휴게소 감지: ${stop.name} (${dist[0].toInt()}m)")
                    runOnUiThread { showRestStopOverlay(stop.name, stop.lat, stop.lng) }
                    return
                }
            }
        } else {
            // ── 1-b. 휴식 중 이탈 감지: 200m 초과 → 경고음 + 취소 ───────────────
            if (activeRestStopLat != 0.0 && activeRestStopLng != 0.0) {
                val exitDist = FloatArray(1)
                android.location.Location.distanceBetween(currentLat, currentLng, activeRestStopLat, activeRestStopLng, exitDist)
                if (exitDist[0] > REST_STOP_EXIT_RADIUS_M) {
                    Log.w("RestStop", "⚠️ 휴게소 이탈 감지: ${exitDist[0].toInt()}m > ${REST_STOP_EXIT_RADIUS_M.toInt()}m → 경고 재생 후 취소")
                    runOnUiThread {
                        playSequential(R.raw.warning, R.raw.rest_cancel)
                        Toast.makeText(this@MainActivity, "⚠️ 휴게소를 벗어났습니다. 휴식이 취소되었습니다.", Toast.LENGTH_LONG).show()
                        forceCancelRestStop()
                    }
                    return
                }
            }
            // 휴게소 오버레이 중에는 다른 버튼 숨김
            runOnUiThread { binding.btnCompleteTrip.visibility = View.GONE }
            return
        }

        // ── 2. 최종 목적지 100m 이내 → 자동 운행 완료 ─────────────────────
        if (!autoCompleteTriggered && currentNaviTripId != null) {
            val destStop = currentStops.lastOrNull { it.type == "destination" }
                ?: currentStops.lastOrNull()
            if (destStop != null) {
                val distDest = FloatArray(1)
                android.location.Location.distanceBetween(currentLat, currentLng, destStop.lat, destStop.lng, distDest)
                if (distDest[0] <= 100f) {
                    autoCompleteTriggered = true
                    suppressCompleteSoundOnce = true  // processTripsUpdate 에서 중복 재생 차단
                    Log.d("AutoComplete", "🏁 최종 목적지 100m 이내 도달 (${distDest[0].toInt()}m) → 자동 운행 완료")
                    val tripId = currentNaviTripId!!
                    runOnUiThread {
                        playSequential(R.raw.bell, R.raw.trip_complite)
                        Toast.makeText(this@MainActivity, "🏁 목적지 도착! 운행을 완료합니다.", Toast.LENGTH_LONG).show()
                        binding.btnCompleteTrip.visibility = View.GONE
                    }
                    updateTripStatus(tripId, "completed")
                    return
                }
            }
        }

        // ── 3. 경유지(상차지·하차지) 100m 이내 → 세부 운행 단계 버튼 표시 ────────
        var nearbyStop: RouteStop? = null
        var nearbyStopIndex: Int = -1
        for ((idx, stop) in currentStops.withIndex()) {
            if (stop.type == "rest_stop" || stop.type == "destination") continue
            val dist = FloatArray(1)
            android.location.Location.distanceBetween(currentLat, currentLng, stop.lat, stop.lng, dist)
            if (dist[0] <= 100) { nearbyStop = stop; nearbyStopIndex = idx; break }
        }
        runOnUiThread {
            if (nearbyStop != null) {
                val stop = nearbyStop
                val stopIdx = nearbyStopIndex
                val tripId = currentNaviTripId ?: ""
                binding.btnCompleteTrip.visibility = View.VISIBLE
                when (stop.type) {
                    "loading" -> showLoadingPhaseButton(stop, stopIdx, tripId)
                    else      -> showUnloadingPhaseButton(stop, stopIdx, tripId)
                }
            } else binding.btnCompleteTrip.visibility = View.GONE
        }
    }

    // =========================================================================
    // 세부 운행 단계 버튼 (v1.0.76 — PATCH /trips/{id}/progress)
    // 상차지: 도착 알림 → 상차 완료 순서
    // 하차지: 도착 알림 → 하차 완료 순서
    // 수동 해제 동작(상차 시 데드림 5분 후 자동 완료 등)은 추후 구현 예정
    // =========================================================================

    /** 상차지 단계 버튼 표시.
     *  currentStopPhase 맵에 저장된 현재 단계에 따라 버튼 텍스트/색상/동작을 전환한다. */
    private fun showLoadingPhaseButton(stop: RouteStop, stopIdx: Int, tripId: String) {
        val phase = currentStopPhase[stop.id] ?: "approaching"   // approaching → arrived → completed
        when (phase) {
            "approaching" -> {
                // 처음 도착 시 — 상차지 도착 알림 버튼
                binding.btnCompleteTrip.text = "📣 상차지 도착 알림 (${stop.name})"
                binding.btnCompleteTrip.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#F57C00"))
                binding.btnCompleteTrip.setOnClickListener {
                    if (tripId.isNotEmpty()) {
                        sendTripProgress(tripId, phase = "loading_arrived", waypointIndex = stopIdx, event = "arrived")
                        Log.d("TripProgress", "📣 상차지 도착 알림 전송: ${stop.name} (wp=$stopIdx)")
                    }
                    currentStopPhase[stop.id] = "arrived"
                    showLoadingPhaseButton(stop, stopIdx, tripId)   // 즉시 버튼 갱신
                    Toast.makeText(this, "📣 상차지 도착이 확인되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            "arrived" -> {
                // 도착 확인 후 — 상차 완료 버튼
                binding.btnCompleteTrip.text = "🚛 상차 완료 (${stop.name})"
                binding.btnCompleteTrip.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#E65100"))
                binding.btnCompleteTrip.setOnClickListener {
                    if (tripId.isNotEmpty()) {
                        sendTripProgress(tripId, phase = "loading_completed", waypointIndex = stopIdx, event = "completed")
                        Log.d("TripProgress", "🚛 상차 완료 전송: ${stop.name} (wp=$stopIdx)")
                    }
                    currentStopPhase.remove(stop.id)
                    completeDelivery(stop.id, stop.name)
                }
            }
        }
    }

    private fun showUnloadingPhaseButton(stop: RouteStop, stopIdx: Int, tripId: String) {
        val phase = currentStopPhase[stop.id] ?: "approaching"
        when (phase) {
            "approaching" -> {
                // 처음 도착 시 — 하차지 도착 알림 버튼
                binding.btnCompleteTrip.text = "📣 하차지 도착 알림 (${stop.name})"
                binding.btnCompleteTrip.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#0277BD"))
                binding.btnCompleteTrip.setOnClickListener {
                    if (tripId.isNotEmpty()) {
                        sendTripProgress(tripId, phase = "unloading_arrived", waypointIndex = stopIdx, event = "arrived")
                        Log.d("TripProgress", "📣 하차지 도착 알림 전송: ${stop.name} (wp=$stopIdx)")
                    }
                    currentStopPhase[stop.id] = "arrived"
                    showUnloadingPhaseButton(stop, stopIdx, tripId)
                    Toast.makeText(this, "📣 하차지 도착이 확인되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            "arrived" -> {
                // 도착 확인 후 — 하차 완료 버튼
                binding.btnCompleteTrip.text = "${getString(R.string.navi_btn_complete_delivery)} (${stop.name})"
                binding.btnCompleteTrip.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#0288D1"))
                binding.btnCompleteTrip.setOnClickListener {
                    if (tripId.isNotEmpty()) {
                        sendTripProgress(tripId, phase = "unloading_completed", waypointIndex = stopIdx, event = "completed")
                        Log.d("TripProgress", "📦 하차 완료 전송: ${stop.name} (wp=$stopIdx)")
                    }
                    currentStopPhase.remove(stop.id)
                    completeDelivery(stop.id, stop.name)
                }
            }
        }
    }

    private fun connectWebSocket() {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).getString("access_token", null) ?: return
        webSocket?.cancel(); webSocket = null
        val request = Request.Builder().url("${Constants.WS_URL}/ws/location?token=$token").build()
        Log.d("LocationWS", "🔌 연결 시도: ${Constants.WS_URL}/ws/location")
        webSocket = wsHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("LocationWS", "✅ 위치 웹소켓 연결 성공"); isLocationWsReconnecting = false
            }
            @SuppressLint("MissingPermission")
            override fun onMessage(webSocket: WebSocket, text: String) {
                logLong("LocationWS", "📩 수신: $text")
                try {
                    val json = JSONObject(text)
                    val msgType = json.optString("type")
                    if (msgType == "ping") { webSocket.send("""{"type":"pong"}"""); return }
                    if (msgType == "replan_requested") {
                        val tripId  = json.optString("trip_id")
                        val message = json.optString("message", getString(R.string.navi_replan_title))
                        val wps     = json.optJSONArray("waypoints") ?: JSONArray()
                        Log.d("LocationWS", "🚨 [Replan] tripId=$tripId, isActivityResumed=$isActivityResumed")
                        if (isActivityResumed) runOnUiThread { showReplanDialog(tripId, message, wps) }
                        else { pendingReplan = PendingReplan(tripId, message, wps); Log.d("LocationWS", "▶ 백그라운드 — pendingReplan 저장") }
                    } else {
                        val arr = json.optJSONArray("arrived_deliveries")
                        if (arr != null && arr.length() > 0) runOnUiThread {
                            Toast.makeText(this@MainActivity, "✅ ${getString(R.string.navi_delivery_done)}", Toast.LENGTH_LONG).show()
                            fetchTrips()
                        }
                    }
                } catch (e: Exception) { Log.e("LocationWS", "❌ 파싱 오류: ${e.message}") }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { Log.w("LocationWS", "⚠️ 닫힘: $reason"); scheduleWsReconnect() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { Log.e("LocationWS", "❌ 에러: ${t.message}"); scheduleWsReconnect() }
        })
    }

    @SuppressLint("MissingPermission")
    private fun showReplanDialog(tripId: String, message: String, wps: JSONArray) {
        if (isFinishing || isDestroyed) return

        // ── 알림음: bell → trip_addwaypoint ───────────────────────────────────
        playSequential(R.raw.bell, R.raw.trip_addwaypoint)

        // ── 위치 기반 재경로 실행 헬퍼 ────────────────────────────────
        val doReplan: (Double, Double) -> Unit = { lat, lng ->
            if (lat != 0.0 && lng != 0.0) requestReplan(tripId, lat, lng, wps)
            else Toast.makeText(this, "현재 위치를 확인할 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
        val executeReplan = {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { loc -> doReplan(loc?.latitude ?: lastLat, loc?.longitude ?: lastLng) }
                .addOnFailureListener { doReplan(lastLat, lastLng) }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.navi_replan_title))
            .setMessage(message)
            .setPositiveButton("${getString(R.string.navi_replan_confirm)} (5)", null)
            .setCancelable(false)
            .create()
        dialog.show()

        val confirmBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        confirmBtn.setOnClickListener { dialog.dismiss(); executeReplan() }

        // ── 5초 카운트다운 후 자동 실행 ──────────────────────────────
        val autoTimer = object : CountDownTimer(5_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                val s = (millisUntilFinished / 1000L + 1).toInt()
                if (dialog.isShowing) confirmBtn.text = "${getString(R.string.navi_replan_confirm)} ($s)"
            }
            override fun onFinish() {
                if (!dialog.isShowing || isFinishing || isDestroyed) return
                dialog.dismiss(); executeReplan()
            }
        }
        autoTimer.start()
        dialog.setOnDismissListener { autoTimer.cancel() }
        Log.d("LocationWS", "🚨 팝업 표시 완료 — 5초 자동 확인 + bell→trip_addwaypoint 재생")
    }

    private fun scheduleWsReconnect() {
        if (isLocationWsReconnecting) return
        isLocationWsReconnecting = true
        Handler(Looper.getMainLooper()).postDelayed({ isLocationWsReconnecting = false; connectWebSocket() }, 3000)
    }

    private fun requestReplan(tripId: String, currentLat: Double, currentLng: Double, wps: JSONArray) {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).getString("access_token", null) ?: return
        Toast.makeText(this, getString(R.string.navi_replanning), Toast.LENGTH_LONG).show()
        autoCompleteTriggered = false
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("${Constants.BASE_URL}/optimize/replan").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"; conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 30000; conn.readTimeout = 30000; conn.doOutput = true
                var dName = "목적지"; var dLat = 0.0; var dLon = 0.0
                val rem = JSONArray()
                if (wps.length() > 0) {
                    var destIdx = -1
                    for (i in wps.length() - 1 downTo 0) { if (wps.getJSONObject(i).optString("type", "unloading") == "unloading") { destIdx = i; break } }
                    if (destIdx == -1) for (i in wps.length() - 1 downTo 0) { if (wps.getJSONObject(i).optString("type", "") == "loading") { destIdx = i; break } }
                    if (destIdx == -1) destIdx = wps.length() - 1
                    val destWp = wps.getJSONObject(destIdx)
                    dName = destWp.optString("name", "목적지"); dLat = destWp.optDouble("lat", 0.0); dLon = destWp.optDouble("lon", destWp.optDouble("lng", 0.0))
                    for (i in 0 until wps.length()) {
                        if (i == destIdx) continue
                        val wp = wps.getJSONObject(i)
                        rem.put(JSONObject().apply {
                            put("name", wp.optString("name", "경유지")); put("lat", wp.optDouble("lat", 0.0)); put("lon", wp.optDouble("lon", wp.optDouble("lng", 0.0)))
                            val t = wp.optString("type", ""); if (t.isNotEmpty()) put("type", t)
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
                if (conn.responseCode in 200..201) parseAndStartNavi(JSONObject(conn.inputStream.bufferedReader().readText()), currentLat, currentLng, dName, dLat, dLon)
                else withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, getString(R.string.navi_optimize_fail), Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) { Log.e("Replan", "Replan API Error: ${e.message}") }
        }
    }

    /** Logcat 은 한 줄 약 4000자에서 잘리므로 긴 문자열을 나눠서 출력 */
    private fun logLong(tag: String, msg: String) {
        val max = 3500
        if (msg.length <= max) { Log.d(tag, msg); return }
        var i = 0; var part = 1
        while (i < msg.length) {
            val end = minOf(i + max, msg.length)
            Log.d(tag, "[분할 $part] ${msg.substring(i, end)}")
            i = end; part++
        }
    }

    private fun fetchTrips() {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).getString("access_token", null)
        if (token == null) { Log.w("FetchTrips", "⚠️ access_token 없음 — 로그인 필요") ; return }
        val userId = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).getString("user_id", null)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "${Constants.BASE_URL}/trips"
                Log.d("FetchTrips", "📡 GET $url (내 user_id=$userId)")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"; conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                val code = conn.responseCode
                Log.d("FetchTrips", "📥 응답 코드: HTTP $code")
                when (code) {
                    200 -> {
                        val body = conn.inputStream.bufferedReader().readText()
                        Log.d("FetchTrips", "✅ 응답 길이: ${body.length}자 — 전체 본문 ↓↓↓")
                        logLong("FetchTrips", body)
                        withContext(Dispatchers.Main) { processTripsUpdate(JSONArray(body)) }
                    }
                    else -> {
                        val err = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                        Log.e("FetchTrips", "❌ HTTP $code — 오류 본문: ${err ?: "(없음)"}")
                        if (code == 401) withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "세션이 만료되었습니다. 다시 로그인해 주세요.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) { Log.e("FetchTrips", "💥 ${e::class.simpleName}: ${e.message}", e) }
        }
    }

    private fun processTripsUpdate(jsonArray: JSONArray) {
        val newStatuses = mutableMapOf<String, String>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val id = obj.optString("id", ""); val st = obj.optString("status", "")
            if (id.isNotEmpty()) newStatuses[id] = st
        }
        // ── 수신한 배차 요약 로그 (배차 미표시 원인 추적용) ──────────────────
        Log.d("TripsUpdate", "📦 수신한 trip 총 ${jsonArray.length()}건")
        for (i in 0 until jsonArray.length()) {
            val o = jsonArray.getJSONObject(i)
            Log.d("TripsUpdate",
                "  • id=${o.optString("id")} status=${o.optString("status")} " +
                "driver_id=${o.optString("driver_id")} dest=${o.optString("dest_name")} " +
                "loading=${o.optInt("loading_count", -1)} unloading=${o.optInt("unloading_count", -1)} " +
                "waypoints=${o.optJSONArray("waypoints")?.length() ?: 0}")
        }
        if (!isFirstFetch) {
            // 신규 trip: knownTripStatuses에 없던 ID가 scheduled 상태로 처음 등장한 경우만
            // (in_progress는 replan 직후 fetchTrips에서 중복 재생되므로 제외)
            for ((id, status) in newStatuses)
                if (id !in knownTripStatuses && status == "scheduled") { playSequential(R.raw.bell, R.raw.trip_new); vibrate(200); break }
            for ((id, oldSt) in knownTripStatuses) if (oldSt !in listOf("cancelled","completed") && newStatuses[id] == "cancelled") { playSequential(R.raw.bell, R.raw.trip_cancel); vibrate(200) }
            for ((id, oldSt) in knownTripStatuses) if (oldSt !in listOf("cancelled","completed") && newStatuses[id] == "completed") {
                if (suppressCompleteSoundOnce) { suppressCompleteSoundOnce = false }
                else { playSequential(R.raw.bell, R.raw.trip_complite); vibrate(200) }
            }
        }
        // ── 진행 중이던 배차가 취소되면(관리자·기사 취소 등) 내비게이션 자동 종료 ──
        val navTripId = currentNaviTripId
        if (navTripId != null && newStatuses[navTripId] == "cancelled") {
            Log.d("Cancel", "🛑 진행 중 배차($navTripId) 취소 감지 → 내비게이션 자동 종료")
            endNavigationToSafeMode(getString(R.string.navi_trip_cancelled))
        }
        if (acceptedTripId != null && newStatuses[acceptedTripId] != "scheduled") { acceptedTripId = null; acceptedOriginLat = 0.0; acceptedOriginLon = 0.0; acceptedOriginName = "현재 위치" }
        knownTripStatuses.clear(); knownTripStatuses.putAll(newStatuses)
        isFirstFetch = false
        renderRunList(jsonArray)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun makeChip(label: String, textHex: String, bgHex: String): TextView {
        return TextView(this).apply {
            text = label; textSize = 11f; setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(textHex))
            background = GradientDrawable().apply { setColor(Color.parseColor(bgHex)); cornerRadius = dpToPx(20).toFloat() }
            setPadding(dpToPx(10), dpToPx(3), dpToPx(10), dpToPx(3))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** JSON null / 빈 문자열 정규화 (org.json 은 명시적 null 을 "null" 문자열로 반환할 수 있음) */
    private fun cleanField(raw: String?): String =
        raw?.let { if (it == "null" || it.isBlank()) "" else it.trim() } ?: ""

    /** 톤수 표기: 정수면 소수점 제거, 아니면 소수 1자리 */
    private fun formatTon(ton: Double): String =
        if (ton % 1.0 == 0.0) ton.toInt().toString() else String.format(Locale.US, "%.1f", ton)

    /**
     * 배차카드 화물 정보 섹션 생성 (백엔드 v1.0.28 + v1.0.76 연락정보 업데이트).
     * 하차(unloading) waypoint 의 recipient_name / cargo_type / cargo_weight_ton /
     * shipper_name / contact_name / contact_phone / shipper_phone 중
     * 하나라도 값이 있는 하차지를 모아 카드 안에 표시한다.
     * 정보가 전혀 없으면 null 반환(섹션 미표시).
     */
    private fun buildCargoInfoSection(waypointsArr: JSONArray?): LinearLayout? {
        if (waypointsArr == null || waypointsArr.length() == 0) return null
        val names = mutableListOf<String>()
        val infos = mutableListOf<String>()
        val contactsList = mutableListOf<String>()  // 연락정보 줄단위 저장
        for (i in 0 until waypointsArr.length()) {
            val wp = waypointsArr.getJSONObject(i)
            val nodeType = wp.optString("node_type", "")
            val t        = wp.optString("type", "unloading")
            val isUnloading = if (nodeType.isNotEmpty()) nodeType == "unloading"
                              else (t == "unloading" || t == "destination")
            if (!isUnloading) continue

            // 화물 정보
            val recipient    = cleanField(wp.optString("recipient_name", ""))
            val cargo        = cleanField(wp.optString("cargo_type", ""))
            val cargoSize    = cleanField(wp.optString("cargo_size", ""))   // 백엔드가 무게/단위 문자열로 내려줌 (예: "4톤", "500kg")
            val ton          = wp.optDouble("cargo_weight_ton", 0.0)
            // 연락정보 (v1.0.76)
            val shipperName  = cleanField(wp.optString("shipper_name", ""))
            val contactName  = cleanField(wp.optString("contact_name", ""))
            val contactPhone = cleanField(wp.optString("contact_phone", ""))
            val shipperPhone = cleanField(wp.optString("shipper_phone", ""))

            val hasCargoInfo    = recipient.isNotEmpty() || cargo.isNotEmpty() || ton > 0.0 || cargoSize.isNotEmpty()
            val hasContactInfo  = shipperName.isNotEmpty() || contactName.isNotEmpty() ||
                                  contactPhone.isNotEmpty() || shipperPhone.isNotEmpty()
            if (!hasCargoInfo && !hasContactInfo) continue

            val name = cleanField(wp.optString("name", "")).ifEmpty { getString(R.string.navi_cargo_dest_fallback) }

            // 화물 정보 줄
            val cargoParts = mutableListOf<String>()
            if (recipient.isNotEmpty()) cargoParts.add("\uD83D\uDC64 $recipient")            // 화주(고객)
            if (cargo.isNotEmpty())     cargoParts.add("\uD83D\uDCE6 $cargo")                // 화물종류
            // 무게: cargo_weight_ton 우선, 없으면 cargo_size 문자열 그대로 표시
            when {
                ton > 0.0          -> cargoParts.add("\u2696\uFE0F " + getString(R.string.navi_cargo_ton, formatTon(ton)))
                cargoSize.isNotEmpty() -> cargoParts.add("\u2696\uFE0F $cargoSize")
            }

            // 연락정보 줄 (v1.0.76)
            val contactParts = mutableListOf<String>()
            if (shipperName.isNotEmpty())  contactParts.add("🏢 화주: $shipperName")
            if (shipperPhone.isNotEmpty()) contactParts.add("📞 $shipperPhone")
            if (contactName.isNotEmpty())  contactParts.add("👷 담당자: $contactName")
            if (contactPhone.isNotEmpty()) contactParts.add("📱 $contactPhone")

            names.add(name)
            infos.add(cargoParts.joinToString("   "))
            contactsList.add(contactParts.joinToString("   "))
        }
        if (names.isEmpty()) return null

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(if (isNightMode) Color.parseColor("#161622") else Color.parseColor("#F5F7FA"))
                cornerRadius = dpToPx(10).toFloat()
            }
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(12) }
        }
        section.addView(TextView(this).apply {
            text = "\uD83D\uDCCB " + getString(R.string.navi_cargo_section_title)   // 화물 정보
            textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(if (isNightMode) "#AAB4C0" else "#607D8B"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(6) }
        })
        for (idx in names.indices) {
            val rowBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (idx > 0) topMargin = dpToPx(10) }
            }
            // 여러 하차지가 있을 때만 하차지 이름을 머리글로 표시
            if (names.size > 1) {
                rowBox.addView(TextView(this).apply {
                    text = "\uD83D\uDCCD ${names[idx]}"
                    textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(if (isNightMode) Color.parseColor("#E0E0E0") else Color.parseColor("#263238"))
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            // 화물 정보 행 (recipient / cargo / ton)
            if (infos[idx].isNotEmpty()) {
                rowBox.addView(TextView(this).apply {
                    text = infos[idx]
                    textSize = 13f
                    setTextColor(if (isNightMode) Color.parseColor("#C2CAD2") else Color.parseColor("#455A64"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { if (names.size > 1) topMargin = dpToPx(2) }
                })
            }
            // 연락정보 행 (shipper / contact) — v1.0.76 신규
            if (contactsList[idx].isNotEmpty()) {
                // 분리선
                if (infos[idx].isNotEmpty()) {
                    rowBox.addView(View(this).apply {
                        setBackgroundColor(if (isNightMode) Color.parseColor("#2A2A3A") else Color.parseColor("#E0E0E0"))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1
                        ).apply { topMargin = dpToPx(4); bottomMargin = dpToPx(4) }
                    })
                }
                // 연락정보 레이아웃: 라벨 + 클릭 가능 전화번호
                val contactInfo = contactsList[idx]
                // 연락정보를 라인별로 분리해 각 줄단위 TextView
                val contactLines = contactInfo.split("   ")
                for (line in contactLines) {
                    if (line.isBlank()) continue
                    // 전화번호 형식 (군단에 "폰" 아이콘 있으면 콜 가능으로 처리)
                    val isPhone = line.contains("📞") || line.contains("📱")
                    rowBox.addView(TextView(this).apply {
                        text = line; textSize = 12f
                        setTextColor(
                            if (isPhone)
                                Color.parseColor(if (isNightMode) "#80CBFF" else "#0277BD")
                            else
                                Color.parseColor(if (isNightMode) "#B0BEC5" else "#546E7A")
                        )
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dpToPx(1) }
                        if (isPhone) {
                            // 전화번호만 클릭 시 다이얼러
                            val raw = line.replace(Regex("[^0-9]"), "")
                            if (raw.length >= 9) {
                                isClickable = true; isFocusable = true
                                setOnClickListener {
                                    val intent = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$raw"))
                                    startActivity(intent)
                                }
                            }
                        }
                    })
                }
            }
            section.addView(rowBox)
        }
        return section
    }

    @SuppressLint("SetTextI18n", "MissingPermission")
    private fun renderRunList(jsonArray: JSONArray) {
        val focused = currentFocus
        if (focused is android.widget.EditText) {
            val container = binding.root.findViewById<LinearLayout>(R.id.run_list_container)
            var v: android.view.View? = focused
            while (v != null) { if (v === container) return; v = v.parent as? android.view.View }
        }
        val container = binding.root.findViewById<LinearLayout>(R.id.run_list_container)
        container.removeAllViews()
        val activeItems = mutableListOf<JSONObject>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i); val st = obj.optString("status", "")
            if (st != "cancelled" && st != "completed") activeItems.add(obj)
        }
        Log.d("RenderRunList", "🧾 전체 ${jsonArray.length()}건 중 표시 대상(scheduled/in_progress 등) ${activeItems.size}건 — cancelled/completed 는 숨김")
        if (activeItems.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.navi_no_trips); setPadding(dpToPx(16), dpToPx(32), dpToPx(16), dpToPx(32))
                textSize = 15f; gravity = android.view.Gravity.CENTER
                setTextColor(if (isNightMode) Color.parseColor("#888888") else Color.parseColor("#999999"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }); return
        }
        activeItems.forEachIndexed { index, obj ->
            val tripId = obj.optString("id", "")
            val rawDestName = obj.optString("dest_name", "").let { if (it == "null" || it.isBlank()) "" else it }
            val rawDestLat  = obj.optDouble("dest_lat", 0.0)
            val rawDestLon  = obj.optDouble("dest_lon", 0.0)
            val loadingCount   = obj.optInt("loading_count", 0)
            val unloadingCount = obj.optInt("unloading_count", 0)
            val optimizedRoute = obj.optJSONObject("optimized_route")
            val routeArr = optimizedRoute?.optJSONArray("route")
            val destFromRoute: String? = if (routeArr != null)
                (0 until routeArr.length()).map { routeArr.getJSONObject(it) }
                    .lastOrNull { it.optString("type") == "destination" }
                    ?.optString("name", "")?.let { if (it == "null" || it.isBlank()) null else it }
            else null
            val waypointsArr = obj.optJSONArray("waypoints")
            val destFromWaypoints: String? = if (waypointsArr != null && waypointsArr.length() > 0) {
                val wpList = (0 until waypointsArr.length()).map { waypointsArr.getJSONObject(it) }
                (wpList.lastOrNull { it.optString("type", "unloading") == "unloading" } ?: wpList.last())
                    .optString("name", "").let { if (it == "null" || it.isBlank()) null else it }
            } else null
            val displayName = rawDestName.takeIf { it.isNotEmpty() } ?: destFromRoute ?: destFromWaypoints
                ?: run {
                    val parts = mutableListOf<String>()
                    if (loadingCount > 0) parts.add(getString(R.string.navi_loading_count, loadingCount))
                    if (unloadingCount > 0) parts.add(getString(R.string.navi_unloading_count, unloadingCount))
                    parts.joinToString(" / ").takeIf { it.isNotEmpty() } ?: getString(R.string.navi_waypoint_trip)
                }
            val status = obj.optString("status", "")
            val statusText = when (status) {
                "in_progress" -> getString(R.string.navi_status_in_progress); "scheduled" -> getString(R.string.navi_status_scheduled)
                "completed"   -> getString(R.string.navi_status_completed);   "cancelled" -> getString(R.string.navi_status_cancelled)
                else -> status
            }
            val (statusTextColor, statusBgColor) = if (isNightMode) when (status) { "in_progress" -> Pair("#FFB74D","#3E2000"); else -> Pair("#90CAF9","#0D2137") }
            else when (status) { "in_progress" -> Pair("#E65100","#FFF3E0"); else -> Pair("#1565C0","#E3F2FD") }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(if (isNightMode) Color.parseColor("#1E1E2A") else Color.WHITE); cornerRadius = dpToPx(16).toFloat() }
                elevation = dpToPx(3).toFloat(); setPadding(dpToPx(20), dpToPx(18), dpToPx(20), dpToPx(18))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dpToPx(12); marginStart = dpToPx(2); marginEnd = dpToPx(2) }
            }
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val circleSize = dpToPx(30)
            headerRow.addView(TextView(this).apply {
                text = "${index + 1}"; textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE); gravity = android.view.Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#2E7D32")) }
                layoutParams = LinearLayout.LayoutParams(circleSize, circleSize).apply { marginEnd = dpToPx(12) }
            })
            headerRow.addView(TextView(this).apply {
                text = displayName; textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(if (isNightMode) Color.parseColor("#E8E8E8") else Color.parseColor("#1A1A1A"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            headerRow.addView(TextView(this).apply {
                text = statusText; textSize = 11f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(statusTextColor))
                background = GradientDrawable().apply { setColor(Color.parseColor(statusBgColor)); cornerRadius = dpToPx(20).toFloat() }
                setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dpToPx(8) }
            })
            card.addView(headerRow)
            if (loadingCount > 0 || unloadingCount > 0) {
                val chipRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpToPx(10) }
                }
                if (loadingCount > 0) chipRow.addView(makeChip("🚛 ${getString(R.string.navi_loading_count, loadingCount)}", "#E65100", if (isNightMode) "#3E1A00" else "#FFF3E0"))
                if (unloadingCount > 0) chipRow.addView(makeChip("📦 ${getString(R.string.navi_unloading_count, unloadingCount)}", "#0277BD", if (isNightMode) "#00233E" else "#E1F5FE").apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dpToPx(6) })
                card.addView(chipRow)
            }
            // ── 화물 정보 섹션 (화주·물건정보·톤수) — 정보가 있을 때만 표시 ──────────
            // waypoints 우선, 없으면 optimized_route.route(운행 중) 노드에서 회수
            (buildCargoInfoSection(waypointsArr) ?: buildCargoInfoSection(routeArr))?.let { card.addView(it) }
            card.addView(View(this).apply {
                setBackgroundColor(if (isNightMode) Color.parseColor("#2E2E3A") else Color.parseColor("#F0F0F0"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)).apply { topMargin = dpToPx(16); bottomMargin = dpToPx(14) }
            })
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            if (status == "scheduled" && tripId != acceptedTripId) {
                btnRow.addView(androidx.appcompat.widget.AppCompatButton(this).apply {
                    text = getString(R.string.navi_btn_reject_dispatch); textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor(if (isNightMode) "#EF9A9A" else "#C62828"))
                    background = GradientDrawable().apply { setColor(if (isNightMode) Color.parseColor("#2D0A0A") else Color.parseColor("#FFF5F5")); cornerRadius = dpToPx(12).toFloat() }
                    stateListAnimator = null; elevation = 0f
                    layoutParams = LinearLayout.LayoutParams(0, dpToPx(48), 1f).apply { marginEnd = dpToPx(8) }
                    setOnClickListener { startActivity(Intent(this@MainActivity, HelpActivity::class.java).apply { putExtra("cancel_trip_id", tripId) }) }
                })
                btnRow.addView(androidx.appcompat.widget.AppCompatButton(this).apply {
                    text = getString(R.string.navi_btn_accept_dispatch); textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { setColor(Color.parseColor("#2E7D32")); cornerRadius = dpToPx(12).toFloat() }
                    stateListAnimator = null; elevation = 0f; layoutParams = LinearLayout.LayoutParams(0, dpToPx(48), 1f)
                    setOnClickListener { acceptedTripId = tripId; fetchTrips() }
                })
            } else {
                // 한 번이라도 안내 시작 이후(서버 in_progress 또는 로컬 기록) → + 버튼 숨김 + "경로 재계산"
                val hasStarted = status == "in_progress" || tripId in startedTripIds
                val originCoords = tripOriginCoords.getOrPut(tripId) { doubleArrayOf(0.0, 0.0) }
                val destCoords   = tripDestCoords.getOrPut(tripId)   { doubleArrayOf(0.0, 0.0) }
                val originInputRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpToPx(6) }
                }
                val originLabel = TextView(this).apply {
                    text = "출발지"; textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor(if (isNightMode) "#AAAAAA" else "#666666"))
                    layoutParams = LinearLayout.LayoutParams(dpToPx(44), LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                val originField = android.widget.EditText(this).apply {
                    hint = "출발지 이름·주소 검색"; textSize = 13f; maxLines = 1; isSingleLine = true; setText(tripOriginText[tripId] ?: "")
                    setTextColor(if (isNightMode) Color.parseColor("#E8E8E8") else Color.parseColor("#1A1A1A"))
                    setHintTextColor(Color.parseColor(if (isNightMode) "#666666" else "#AAAAAA"))
                    background = GradientDrawable().apply { setColor(if (isNightMode) Color.parseColor("#2A2A3A") else Color.parseColor("#EBEBEB")); cornerRadius = dpToPx(8).toFloat() }
                    setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8)); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                originField.addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) { tripOriginText[tripId] = s?.toString() ?: "" }
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                })
                val originSearchBtn = TextView(this).apply {
                    text = "🔍"; textSize = 15f; gravity = android.view.Gravity.CENTER; setPadding(dpToPx(6), 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(dpToPx(34), dpToPx(34)); isClickable = true; isFocusable = true
                }
                originInputRow.addView(originLabel); originInputRow.addView(originField); originInputRow.addView(originSearchBtn)

                val destInputRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                val destLabel = TextView(this).apply {
                    text = "목적지"; textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor(if (isNightMode) "#AAAAAA" else "#666666"))
                    layoutParams = LinearLayout.LayoutParams(dpToPx(44), LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                val destField = android.widget.EditText(this).apply {
                    hint = "목적지 이름·주소 검색"; textSize = 13f; maxLines = 1; isSingleLine = true; setText(tripDestText[tripId] ?: "")
                    setTextColor(if (isNightMode) Color.parseColor("#E8E8E8") else Color.parseColor("#1A1A1A"))
                    setHintTextColor(Color.parseColor(if (isNightMode) "#666666" else "#AAAAAA"))
                    background = GradientDrawable().apply { setColor(if (isNightMode) Color.parseColor("#2A2A3A") else Color.parseColor("#EBEBEB")); cornerRadius = dpToPx(8).toFloat() }
                    setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8)); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                destField.addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) { tripDestText[tripId] = s?.toString() ?: "" }
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                })
                val destSearchBtn = TextView(this).apply {
                    text = "🔍"; textSize = 15f; gravity = android.view.Gravity.CENTER; setPadding(dpToPx(6), 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(dpToPx(34), dpToPx(34)); isClickable = true; isFocusable = true
                }
                destInputRow.addView(destLabel); destInputRow.addView(destField); destInputRow.addView(destSearchBtn)

                val expandPanel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = if (tripId in expandedTripIds) View.VISIBLE else View.GONE
                    background = GradientDrawable().apply { setColor(if (isNightMode) Color.parseColor("#161622") else Color.parseColor("#F5F5F5")); cornerRadius = dpToPx(10).toFloat() }
                    setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpToPx(6) }
                }
                expandPanel.addView(originInputRow); expandPanel.addView(destInputRow)

                val doSearch = { field: android.widget.EditText, coords: DoubleArray, isOrigin: Boolean ->
                    val q = field.text.toString().trim()
                    if (q.isEmpty()) Toast.makeText(this@MainActivity, "검색어를 입력하세요", Toast.LENGTH_SHORT).show()
                    else searchAddressAndShow(q, field, coords) { selectedText ->
                        if (isOrigin) tripOriginText[tripId] = selectedText else tripDestText[tripId] = selectedText
                    }
                }
                originSearchBtn.setOnClickListener { doSearch(originField, originCoords, true) }
                destSearchBtn.setOnClickListener   { doSearch(destField, destCoords, false) }
                originField.setOnEditorActionListener { _, actionId, _ -> if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { doSearch(originField, originCoords, true); true } else false }
                destField.setOnEditorActionListener   { _, actionId, _ -> if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { doSearch(destField, destCoords, false); true } else false }

                var isExpanded = tripId in expandedTripIds
                val plusRow2 = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.END
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpToPx(4) }
                }
                val plusBtn = TextView(this).apply {
                    text = if (tripId in expandedTripIds) "−" else "+"; textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE); gravity = android.view.Gravity.CENTER
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(if (isNightMode) "#37474F" else "#78909C")) }
                    layoutParams = LinearLayout.LayoutParams(dpToPx(26), dpToPx(26)); isClickable = true; isFocusable = true
                }
                plusBtn.setOnClickListener {
                    isExpanded = !isExpanded
                    if (isExpanded) expandedTripIds.add(tripId) else expandedTripIds.remove(tripId)
                    plusBtn.text = if (isExpanded) "−" else "+"
                    if (isExpanded) { expandPanel.visibility = View.VISIBLE; expandPanel.alpha = 0f; expandPanel.animate().alpha(1f).setDuration(220).start() }
                    else expandPanel.animate().alpha(0f).setDuration(180).withEndAction { expandPanel.visibility = View.GONE }.start()
                }
                plusRow2.addView(plusBtn)

                btnRow.addView(androidx.appcompat.widget.AppCompatButton(this).apply {
                    text = if (hasStarted) getString(R.string.navi_btn_reroute) else getString(R.string.navi_btn_start); textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { setColor(Color.parseColor(if (hasStarted) "#1565C0" else "#2E7D32")); cornerRadius = dpToPx(12).toFloat() }
                    stateListAnimator = null; elevation = 0f; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48))
                    setOnClickListener {
                        if (hasStarted) {
                            // ── 경로 재계산: 현재 위치 기준으로 /optimize 재요청 (출발지·목적지는 서버 자동 결정) ──
                            startedTripIds.add(tripId)
                            currentNaviTripId = tripId
                            bottomSheetBehavior?.state = BottomSheetBehavior.STATE_COLLAPSED
                            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                                optimizeAndStartNavi(tripId, displayName, rawDestLat, rawDestLon,
                                    loc?.latitude ?: lastLat.takeIf { it != 0.0 },
                                    loc?.longitude ?: lastLng.takeIf { it != 0.0 })
                            }.addOnFailureListener {
                                optimizeAndStartNavi(tripId, displayName, rawDestLat, rawDestLon,
                                    lastLat.takeIf { it != 0.0 }, lastLng.takeIf { it != 0.0 })
                            }
                            return@setOnClickListener
                        }
                        startedTripIds.add(tripId)
                        val originText = originField.text.toString().trim()
                        val destText   = destField.text.toString().trim()
                        // 기사가 검색으로 목적지 좌표까지 확정한 경우에만 userProvidedDest = true
                        val userSetDest = destText.isNotEmpty() && destCoords[0] != 0.0
                        currentNaviTripId = tripId
                        bottomSheetBehavior?.state = BottomSheetBehavior.STATE_COLLAPSED
                        when {
                            originText.isNotEmpty() && originCoords[0] != 0.0 -> {
                                val dName = if (userSetDest) destText else displayName
                                val dLat  = if (userSetDest) destCoords[0] else rawDestLat
                                val dLon  = if (userSetDest) destCoords[1] else rawDestLon
                                optimizeAndStartNavi(tripId, dName, dLat, dLon, originCoords[0], originCoords[1], originText,
                                    userProvidedDest = userSetDest)
                            }
                            originText.isNotEmpty() -> {
                                geocodeForStart(originText, destText, destCoords, tripId, displayName, rawDestLat, rawDestLon)
                            }
                            else -> {
                                val dName = if (userSetDest) destText else displayName
                                val dLat  = if (userSetDest) destCoords[0] else rawDestLat
                                val dLon  = if (userSetDest) destCoords[1] else rawDestLon
                                fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                                    optimizeAndStartNavi(tripId, dName, dLat, dLon,
                                        loc?.latitude ?: lastLat.takeIf { it != 0.0 },
                                        loc?.longitude ?: lastLng.takeIf { it != 0.0 },
                                        userProvidedDest = userSetDest)
                                }.addOnFailureListener {
                                    optimizeAndStartNavi(tripId, dName, dLat, dLon,
                                        lastLat.takeIf { it != 0.0 }, lastLng.takeIf { it != 0.0 },
                                        userProvidedDest = userSetDest)
                                }
                            }
                        }
                    }
                })
                if (!hasStarted) { card.addView(expandPanel); card.addView(plusRow2) }
            }
            card.addView(btnRow)

            // ── 경로 미리보기: 카드 아무 곳 클릭 시 TripPreviewActivity ──────────
            // 내부 버튼(거절·수락·완료·안내시작 등)은 자체 이벤트를 소모하므로
            // 카드 배경·텍스트·칩 영역을 탭했을 때만 미리보기가 열린다.
            run {
                val previewDistKm = optimizedRoute?.optDouble("total_distance_km",    0.0) ?: 0.0
                val previewDurMin = optimizedRoute?.optDouble("estimated_duration_min", 0.0) ?: 0.0

                // 첫 상차지 이름, 마지막 하차지(또는 마지막 경유지) 이름
                val wpList = if (waypointsArr != null)
                    (0 until waypointsArr.length()).map { waypointsArr.getJSONObject(it) }
                else emptyList()
                val firstLoading  = wpList.firstOrNull { it.optString("type", "unloading") == "loading" }
                val lastUnloading = wpList.lastOrNull  { it.optString("type", "unloading") == "unloading" }
                    ?: wpList.lastOrNull()

                val pickupAddr = firstLoading?.optString("name", "")  ?: ""
                val destAddr   = rawDestName.takeIf { it.isNotEmpty() }
                    ?: lastUnloading?.optString("name", "")?.takeIf { it.isNotEmpty() }
                    ?: displayName

                // ── 미리보기 지도용 waypoints: 기존 경유지 + 도착지(dest_*)를 하차 핀으로 추가 ──
                // /trips 응답은 도착지를 waypoints 가 아닌 dest_lat/lon 으로 내려주므로,
                // 그대로 두면 지도에 하차 핀이 안 찍힌다. → dest 를 unloading 노드로 합쳐서 전달
                val previewWaypoints = JSONArray()
                wpList.forEach { previewWaypoints.put(it) }
                if (rawDestLat != 0.0 && rawDestLon != 0.0) {
                    val dup = (0 until previewWaypoints.length()).any {
                        val w  = previewWaypoints.getJSONObject(it)
                        val wl = w.optDouble("lat", 0.0)
                        val wo = w.optDouble("lon", w.optDouble("lng", 0.0))
                        Math.abs(wl - rawDestLat) < 1e-6 && Math.abs(wo - rawDestLon) < 1e-6
                    }
                    if (!dup) previewWaypoints.put(JSONObject().apply {
                        put("name", rawDestName.ifEmpty { destAddr })
                        put("lat",  rawDestLat)
                        put("lon",  rawDestLon)
                        put("type", "unloading")
                    })
                }

                card.isClickable = true
                card.isFocusable = true
                card.setOnClickListener {
                    tripPreviewLauncher.launch(
                        Intent(this@MainActivity, TripPreviewActivity::class.java).apply {
                            putExtra("trip_id",        tripId)
                            putExtra("trip_name",      displayName)
                            putExtra("distance_km",    previewDistKm.toFloat())
                            putExtra("duration_min",   previewDurMin.toFloat())
                            putExtra("waypoints_json", previewWaypoints.toString())
                            putExtra("pickup_name",    pickupAddr)
                            putExtra("dest_name",      destAddr)
                            putExtra("status",         status)
                        }
                    )
                }
            }

            container.addView(card)
        }
    }

    private suspend fun convertWGS84ToKATEC(lat: Double, lng: Double): Pair<Int, Int>? {
        if (lat < 30.0 || lng < 120.0) return null
        return withContext(Dispatchers.IO) {
            try {
                val conn = URL("https://dapi.kakao.com/v2/local/geo/transcoord.json?x=$lng&y=$lat&input_coord=WGS84&output_coord=KTM")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "GET"; conn.setRequestProperty("Authorization", "KakaoAK efc9f0b149f1b77d83d1b607ee60837d")
                conn.connectTimeout = 3000; conn.readTimeout = 3000
                if (conn.responseCode == 200) {
                    val docs = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONArray("documents")
                    if (docs.length() > 0) return@withContext Pair(docs.getJSONObject(0).getDouble("x").toInt(), docs.getJSONObject(0).getDouble("y").toInt())
                }
            } catch (e: Exception) { }
            null
        }
    }

    /**
     * 경로 최적화 후 내비게이션을 시작한다.
     *
     * @param userProvidedDest 기사가 🔍 검색으로 목적지 좌표를 직접 확정한 경우 true.
     *   true면 dest_* 필드를 서버(/optimize)에 전달하여 서버의 dest 우선순위 체계를 활용.
     *   false면 전달하지 않으므로 서버가 trip.dest → 마지막 하차지 순으로 자동 결정.
     */
    private fun optimizeAndStartNavi(
        tripId: String, destName: String, destLat: Double, destLng: Double,
        currentLat: Double?, currentLng: Double?,
        originNameForBackend: String? = null,
        userProvidedDest: Boolean = false
    ) {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).getString("access_token", null) ?: return
        Toast.makeText(this, getString(R.string.navi_optimizing), Toast.LENGTH_LONG).show()
        visitedRestStopKeys.clear()
        autoCompleteTriggered = false
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("${Constants.BASE_URL}/optimize").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"; conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 90000; conn.readTimeout = 90000; conn.doOutput = true

                OutputStreamWriter(conn.outputStream).use {
                    it.write(JSONObject().apply {
                        put("trip_id", tripId)
                        // 출발지: 기사가 직접 입력한 경우에만 전달
                        if (originNameForBackend != null && currentLat != null && currentLng != null) {
                            put("origin_name", originNameForBackend)
                            put("origin_lat",  currentLat)
                            put("origin_lon",  currentLng)
                        }
                        // ✅ 목적지: 기사가 🔍 검색으로 직접 확정한 경우에만 전달
                        // 입력 없으면 서버가 trip.dest → 마지막 하차지 → 마지막 상차지 순으로 자동 결정
                        if (userProvidedDest && destLat != 0.0 && destLng != 0.0) {
                            put("dest_name", destName)
                            put("dest_lat",  destLat)
                            put("dest_lon",  destLng)
                        }
                        put("initial_drive_sec", 0)
                        put("is_emergency", false)
                    }.toString())
                }

                Log.d("OptimizeAPI", "📤 /optimize 요청: trip=$tripId, userProvidedDest=$userProvidedDest" +
                        if (userProvidedDest) ", dest=$destName ($destLat,$destLng)" else "")

                if (conn.responseCode in 200..201) {
                    val responseString = conn.inputStream.bufferedReader().readText()
                    Log.d("NaviLog", "✅ 서버 응답: $responseString")
                    parseAndStartNavi(JSONObject(responseString), currentLat ?: 0.0, currentLng ?: 0.0, destName, destLat, destLng)
                } else withContext(Dispatchers.Main) {
                    if (destLat != 0.0 && destLng != 0.0) startNavigationWithWGS84(destName, destLat, destLng)
                    else Toast.makeText(this@MainActivity, getString(R.string.navi_optimize_fail), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (destLat != 0.0 && destLng != 0.0) startNavigationWithWGS84(destName, destLat, destLng)
                    else Toast.makeText(this@MainActivity, getString(R.string.navi_optimize_fail), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun parseAndStartNavi(
        jsonResponse: JSONObject, currentLat: Double, currentLng: Double,
        fallbackDestName: String, fallbackLat: Double, fallbackLng: Double
    ) {
        val arr = jsonResponse.optJSONArray("route")
            ?: jsonResponse.optJSONObject("optimized_route")?.optJSONArray("route")
            ?: jsonResponse.optJSONArray("waypoints")
        if (arr != null && arr.length() > 0) {
            // ── 개발자 모드: /optimize 응답 라우트 + /polyline 실도로 경로 → GPX 저장 ────────
            if (DeveloperModeManager.isEnabled(this) && currentNaviTripId != null) {
                val tripId = currentNaviTripId!!
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
                            .getString("access_token", null) ?: return@launch
                        val polyConn = java.net.URL("${Constants.BASE_URL}/trips/$tripId/polyline")
                            .openConnection() as java.net.HttpURLConnection
                        polyConn.requestMethod = "GET"
                        polyConn.setRequestProperty("Authorization", "Bearer $token")
                        polyConn.connectTimeout = 8_000; polyConn.readTimeout = 8_000

                        val polyArr = if (polyConn.responseCode == 200) {
                            val raw = polyConn.inputStream.bufferedReader().readText()
                            // 포맷 A: [[lat,lon],...] 또는 포맷 B: {"points":[...]}
                            try { org.json.JSONArray(raw) }
                            catch (_: Exception) {
                                org.json.JSONObject(raw).optJSONArray("points") ?: org.json.JSONArray()
                            }
                        } else org.json.JSONArray()

                        val saved = gpxRecorder.saveRouteWithPolyline(tripId, arr, polyArr)
                        withContext(Dispatchers.Main) {
                            if (saved != null) {
                                val msg = if (polyArr.length() > 0)
                                    "📍 GPX 저장 (실도로 ${polyArr.length()}포인트): ${saved.name}"
                                else
                                    "📍 GPX 저장 (경유지만): ${saved.name}"
                                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("MainActivity", "GPX polyline fetch 실패: ${e.message}")
                    }
                }
            }
            // ── 기존 라우트 파싱 로직 ──────────────────────────────────────────
            val vias = mutableListOf<KNPOI>()
            var fName = fallbackDestName; var fLat = fallbackLat; var fLng = fallbackLng
            currentStops.clear()
            for (i in 0 until arr.length()) {
                val pt = arr.getJSONObject(i)
                val rawType  = pt.optString("type", "")
                val nodeType = pt.optString("node_type", "")
                val effectiveType = when { nodeType.isNotEmpty() -> nodeType; rawType in listOf("loading","unloading") -> rawType; else -> rawType }
                val name = pt.optString("name", "경유지${i+1}")
                val lat  = pt.optDouble("lat", 0.0)
                val lng  = pt.optDouble("lon", pt.optDouble("lng", 0.0))
                val did  = pt.optString("delivery_id", pt.optString("id", ""))
                if (rawType != "origin") currentStops.add(RouteStop(did, name, lat, lng, effectiveType))
                when (rawType) {
                    "loading","unloading","waypoint","rest_stop" -> convertWGS84ToKATEC(lat, lng)?.let { vias.add(KNPOI(name, it.first, it.second, "")) }
                    "destination" -> { fName = name; fLat = lat; fLng = lng }
                }
            }
            if (currentStops.none { it.type == "destination" } && currentStops.isNotEmpty()) {
                val last = currentStops.last()
                currentStops[currentStops.lastIndex] = last.copy(type = "destination")
                if (fLat == fallbackLat && fLng == fallbackLng && last.lat != 0.0) { fName = last.name; fLat = last.lat; fLng = last.lng; if (vias.isNotEmpty()) vias.removeAt(vias.lastIndex) }
            }
            val sk = convertWGS84ToKATEC(currentLat, currentLng)
            val gk = convertWGS84ToKATEC(fLat, fLng)
            if (sk != null && gk != null) {
                val ml = KNSDK.sharedGuidance()?.locationGuide?.location
                val sp = KNPOI("현재 위치", ml?.pos?.x?.toInt() ?: sk.first, ml?.pos?.y?.toInt() ?: sk.second, "")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.navi_optimized), Toast.LENGTH_SHORT).show()
                    startNavigationWithWaypoints(sp, KNPOI(fName, gk.first, gk.second, ""), vias)
                }
            } else withContext(Dispatchers.Main) { startNavigationWithWGS84(fallbackDestName, fallbackLat, fallbackLng) }
        } else withContext(Dispatchers.Main) { startNavigationWithWGS84(fallbackDestName, fallbackLat, fallbackLng) }
    }

    private fun startNavigationWithWaypoints(start: KNPOI, goal: KNPOI, vias: MutableList<KNPOI>) {
        val guidance = KNSDK.sharedGuidance() ?: return
        guidance.stop()
        val limitedVias = if (vias.size > 15) { runOnUiThread { Toast.makeText(this, "경유지가 많아 가까운 15개까지만 먼저 안내합니다.", Toast.LENGTH_LONG).show() }; vias.take(15).toMutableList() } else vias
        KNSDK.makeTripWithStart(start, goal, limitedVias) { error, aTrip ->
            if (aTrip != null) {
                val pri = KNRoutePriority.KNRoutePriority_Recommand; val avoid = KNRouteAvoidOption.KNRouteAvoidOption_None.value
                aTrip.routeWithPriority(pri, avoid) { routeError, _ ->
                    if (routeError == null) runOnUiThread {
                        binding.naviContainer.removeAllViews(); naviView = KNNaviView(this@MainActivity); binding.naviContainer.addView(naviView)
                        applyNaviSettings(); guidance.apply { setupDelegates(this); naviView.initWithGuidance(this, aTrip, pri, avoid) }
                    } else { Log.e("KNSDK", "탐색 실패: ${routeError.msg}"); runOnUiThread { Toast.makeText(this@MainActivity, "경로 탐색 실패: ${routeError.msg}", Toast.LENGTH_LONG).show() } }
                }
            } else { Log.e("KNSDK", "Trip 생성 실패: ${error?.msg}"); runOnUiThread { Toast.makeText(this@MainActivity, "경로 생성 실패: ${error?.msg}", Toast.LENGTH_LONG).show() } }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNavigationWithWGS84(name: String, lat: Double, lng: Double) {
        CoroutineScope(Dispatchers.IO).launch {
            if (lat < 30.0 || lng < 120.0) return@launch
            try {
                val conn = URL("https://dapi.kakao.com/v2/local/geo/transcoord.json?x=$lng&y=$lat&input_coord=WGS84&output_coord=KTM")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "GET"; conn.setRequestProperty("Authorization", "KakaoAK efc9f0b149f1b77d83d1b607ee60837d")
                conn.connectTimeout = 3000; conn.readTimeout = 3000
                if (conn.responseCode == 200) {
                    val docs = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONArray("documents")
                    if (docs.length() > 0) {
                        val kx = docs.getJSONObject(0).getDouble("x").toInt(); val ky = docs.getJSONObject(0).getDouble("y").toInt()
                        withContext(Dispatchers.Main) {
                            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                                if (loc != null) CoroutineScope(Dispatchers.IO).launch {
                                    convertWGS84ToKATEC(loc.latitude, loc.longitude)?.let { sk ->
                                        val ml = KNSDK.sharedGuidance()?.locationGuide?.location
                                        val sp = KNPOI("현재 위치", ml?.pos?.x?.toInt() ?: sk.first, ml?.pos?.y?.toInt() ?: sk.second, "")
                                        withContext(Dispatchers.Main) { startNavigationWithWaypoints(sp, KNPOI(name, kx, ky, ""), mutableListOf()) }
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
        naviView.guideStateDelegate = this   // 메뉴 '안내종료' / 안전운행 'X' → naviViewGuideEnded 콜백 수신
        val sp = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        naviView.useDarkMode = isNightMode
        naviView.fuelType = when (sp.getInt("fuel_type", 0)) {
            2 -> KNCarFuel.KNCarFuel_Diesel; 3 -> KNCarFuel.KNCarFuel_LPG; 4 -> KNCarFuel.KNCarFuel_Electric
            5 -> KNCarFuel.KNCarFuel_HybridElectric; 6 -> KNCarFuel.KNCarFuel_PlugInHybridElectric; 7 -> KNCarFuel.KNCarFuel_Hydrogen
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
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).setMinUpdateIntervalMillis(5000).build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    lastLat = loc.latitude; lastLng = loc.longitude
                    sendLocationToServer(loc.latitude, loc.longitude, loc.speed)
                    checkProximityToStops(loc.latitude, loc.longitude)
                }
            }
        }
        fusedLocationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
    }

    private fun sendLocationToServer(lat: Double, lng: Double, speed: Float) {
        val sp = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        val token = sp.getString("access_token", null) ?: return; val userId = sp.getString("user_id", null) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("${Constants.BASE_URL}/location-logs").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"; conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token"); conn.connectTimeout = 3000; conn.readTimeout = 3000; conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use { it.write(JSONObject().apply { put("user_id", userId); put("lat", lat); put("lon", lng); put("speed", speed) }.toString()) }
                conn.responseCode
            } catch (e: Exception) { }
        }
    }

    private fun initKakaoNaviSDK() {
        val prefs = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        val langCode = prefs.getString("language", "ko") ?: "ko"
        val knLang = if (langCode == "en") KNLanguageType.KNLanguageType_ENGLISH else KNLanguageType.KNLanguageType_KOREAN
        KNSDK.initializeWithAppKey(
            aAppKey = "b57bc6d46e97f480deecdd3a8e4cd754", aClientVersion = "1.0", aAppUserId = "test_user",
            aLangType = knLang,
            aCompletion = { error ->
                if (error == null) runOnUiThread {
                    naviView = KNNaviView(this@MainActivity); binding.naviContainer.addView(naviView)
                    applyNaviSettings(); startSafeDriving()
                }
            }
        )
    }

    private fun setupDelegates(guidance: KNGuidance) {
        guidance.guideStateDelegate = this; guidance.locationGuideDelegate = this
        guidance.routeGuideDelegate = this; guidance.safetyGuideDelegate   = this
        guidance.voiceGuideDelegate = this; guidance.citsGuideDelegate     = this
        naviView.mapComponent.mapView.isVisibleTraffic = true
    }

    private fun startSafeDriving() {
        KNSDK.sharedGuidance()?.apply {
            setupDelegates(this)
            naviView.initWithGuidance(this, null, KNRoutePriority.KNRoutePriority_Recommand, KNRouteAvoidOption.KNRouteAvoidOption_None.value)
        }
    }

    override fun guidanceGuideEnded(aGuidance: KNGuidance) {
        if (::naviView.isInitialized) naviView.guidanceGuideEnded(aGuidance)
        if (demoPlayer.isPlaying) demoPlayer.stop()
        runOnUiThread {
            Toast.makeText(this@MainActivity, getString(R.string.navi_ended), Toast.LENGTH_SHORT).show()
            binding.naviContainer.removeAllViews(); naviView = KNNaviView(this@MainActivity); binding.naviContainer.addView(naviView)
            applyNaviSettings(); startSafeDriving()
        }
    }

    /** 주행 화면 메뉴의 '안내종료' 또는 안전운행 모드의 'X' 클릭 시 호출 (KNNaviView_GuideStateDelegate). */
    override fun naviViewGuideEnded() { endNavigationToSafeMode(getString(R.string.navi_ended)) }

    /** 주행 화면 상태(KNGuideState) 변경 콜백 — 인터페이스 충족용, 별도 처리 없음 */
    override fun naviViewGuideState(state: KNGuideState) { }

    /** 진행 중인 길 안내를 중단하고 안전운행 모드로 복귀시킨다. (안내종료·배차 취소 등 공통 사용) */
    private fun endNavigationToSafeMode(toastMsg: String?) {
        KNSDK.sharedGuidance()?.stop()
        if (demoPlayer.isPlaying) demoPlayer.stop()
        currentNaviTripId = null; currentStops.clear()
        currentStopPhase.clear()
        autoCompleteTriggered = false; suppressCompleteSoundOnce = false
        visitedRestStopKeys.clear()
        if (isRestStopActive) forceCancelRestStop()
        binding.naviContainer.post {
            binding.btnCompleteTrip.visibility = View.GONE
            binding.naviContainer.removeAllViews()
            naviView = KNNaviView(this@MainActivity)
            binding.naviContainer.addView(naviView)
            applyNaviSettings(); startSafeDriving()
            bottomSheetBehavior?.state = BottomSheetBehavior.STATE_EXPANDED
            if (!toastMsg.isNullOrEmpty()) Toast.makeText(this@MainActivity, toastMsg, Toast.LENGTH_SHORT).show()
            fetchTrips()
        }
    }
    override fun willPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice) { if (::naviView.isInitialized) naviView.willPlayVoiceGuide(aGuidance, aVoiceGuide); vibrate(150) }
    override fun guidanceGuideStarted(aGuidance: KNGuidance) { if (::naviView.isInitialized) naviView.guidanceGuideStarted(aGuidance) }
    override fun guidanceCheckingRouteChange(aGuidance: KNGuidance) { if (::naviView.isInitialized) naviView.guidanceCheckingRouteChange(aGuidance) }
    override fun guidanceRouteUnchanged(aGuidance: KNGuidance) { if (::naviView.isInitialized) naviView.guidanceRouteUnchanged(aGuidance) }
    override fun guidanceRouteUnchangedWithError(aGuidnace: KNGuidance, aError: KNError) { if (::naviView.isInitialized) naviView.guidanceRouteUnchangedWithError(aGuidnace, aError) }
    override fun guidanceOutOfRoute(aGuidance: KNGuidance) { if (::naviView.isInitialized) naviView.guidanceOutOfRoute(aGuidance) }
    override fun guidanceRouteChanged(aGuidance: KNGuidance, f: KNRoute, fl: KNLocation, t: KNRoute, tl: KNLocation, r: KNGuideRouteChangeReason) {}
    override fun guidanceDidUpdateRoutes(aGuidance: KNGuidance, aRoutes: List<KNRoute>, aMultiRouteInfo: KNMultiRouteInfo?) { if (::naviView.isInitialized) naviView.guidanceDidUpdateRoutes(aGuidance, aRoutes, aMultiRouteInfo) }
    override fun guidanceDidUpdateIndoorRoute(aGuidance: KNGuidance, aRoute: KNRoute?) {}
    override fun guidanceDidUpdateLocation(aGuidance: KNGuidance, aLocationGuide: KNGuide_Location) {
        if (::naviView.isInitialized) naviView.guidanceDidUpdateLocation(aGuidance, aLocationGuide)
    }
    override fun guidanceDidUpdateRouteGuide(aGuidance: KNGuidance, aRouteGuide: KNGuide_Route) { if (::naviView.isInitialized) naviView.guidanceDidUpdateRouteGuide(aGuidance, aRouteGuide) }
    override fun guidanceDidUpdateSafetyGuide(aGuidance: KNGuidance, aSafetyGuide: KNGuide_Safety?) { if (::naviView.isInitialized) naviView.guidanceDidUpdateSafetyGuide(aGuidance, aSafetyGuide) }
    override fun guidanceDidUpdateAroundSafeties(aGuidance: KNGuidance, aSafeties: List<KNSafety>?) { if (::naviView.isInitialized) naviView.guidanceDidUpdateAroundSafeties(aGuidance, aSafeties) }
    override fun shouldPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice, aNewData: MutableList<ByteArray>): Boolean = if (::naviView.isInitialized) naviView.shouldPlayVoiceGuide(aGuidance, aVoiceGuide, aNewData) else false
    override fun didFinishPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice) { if (::naviView.isInitialized) naviView.didFinishPlayVoiceGuide(aGuidance, aVoiceGuide) }

    private fun searchAddressAndShow(query: String, field: android.widget.EditText, coords: DoubleArray, onSelected: ((String) -> Unit)? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                val conn = URL("https://dapi.kakao.com/v2/local/search/keyword.json?query=$encoded&size=10").openConnection() as HttpURLConnection
                conn.requestMethod = "GET"; conn.setRequestProperty("Authorization", "KakaoAK efc9f0b149f1b77d83d1b607ee60837d")
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val docs = json.getJSONArray("documents")
                    if (docs.length() == 0) { withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "검색 결과가 없습니다.", Toast.LENGTH_SHORT).show() }; return@launch }
                    val labels = Array(docs.length()) { i ->
                        val d = docs.getJSONObject(i); val pname = d.optString("place_name", ""); val addr = d.optString("road_address_name", d.optString("address_name", ""))
                        if (addr.isNotEmpty()) "$pname\n$addr" else pname
                    }
                    withContext(Dispatchers.Main) {
                        AlertDialog.Builder(this@MainActivity).setTitle("장소 선택").setItems(labels) { _, which ->
                            val d = docs.getJSONObject(which); val pname = d.optString("place_name", ""); val addr = d.optString("road_address_name", d.optString("address_name", ""))
                            val selected = if (pname.isNotEmpty()) pname else addr
                            field.setText(selected); coords[0] = d.optDouble("y", 0.0); coords[1] = d.optDouble("x", 0.0); onSelected?.invoke(selected)
                        }.setNegativeButton("취소", null).show()
                    }
                } else withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "주소 검색에 실패했습니다.", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "검색 오류: ${e.message}", Toast.LENGTH_SHORT).show() } }
        }
    }

    private fun geocodeForStart(originText: String, destText: String, destCoords: DoubleArray, tripId: String, displayName: String, rawDestLat: Double, rawDestLon: Double) {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).getString("access_token", null) ?: return
        Toast.makeText(this, "출발지 검색 중...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val encoded = java.net.URLEncoder.encode(originText, "UTF-8")
                val conn = URL("${Constants.BASE_URL}/address/coord?query=$encoded").openConnection() as HttpURLConnection
                conn.requestMethod = "GET"; conn.setRequestProperty("Authorization", "Bearer $token"); conn.connectTimeout = 8000; conn.readTimeout = 8000
                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val oLat = json.optDouble("lat", 0.0); val oLon = json.optDouble("lon", json.optDouble("lng", 0.0))
                    if (oLat != 0.0 || oLon != 0.0) {
                        withContext(Dispatchers.Main) {
                            val userSetDest = destText.isNotEmpty() && destCoords[0] != 0.0
                            val dName = if (userSetDest) destText else displayName
                            val dLat  = if (userSetDest) destCoords[0] else rawDestLat
                            val dLon  = if (userSetDest) destCoords[1] else rawDestLon
                            optimizeAndStartNavi(tripId, dName, dLat, dLon, oLat, oLon, originText,
                                userProvidedDest = userSetDest)
                        }
                    } else withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "출발지를 찾을 수 없습니다. 다시 검색해 주세요.", Toast.LENGTH_SHORT).show() }
                } else withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "출발지 검색 실패", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "오류: ${e.message}", Toast.LENGTH_SHORT).show() } }
        }
    }

    private fun showOriginAddressDialog(tripId: String, displayName: String, rawDestLat: Double, rawDestLon: Double) {
        val input = android.widget.EditText(this).apply { hint = getString(R.string.navi_origin_address_hint); inputType = android.text.InputType.TYPE_CLASS_TEXT; setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(16)) }
        AlertDialog.Builder(this).setTitle(getString(R.string.navi_origin_address_title)).setView(input)
            .setPositiveButton(getString(R.string.navi_replan_confirm)) { _, _ ->
                val address = input.text.toString().trim()
                if (address.isEmpty()) { Toast.makeText(this, getString(R.string.navi_origin_address_hint), Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).getString("access_token", null) ?: return@setPositiveButton
                Toast.makeText(this, getString(R.string.navi_origin_geocoding), Toast.LENGTH_SHORT).show()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val encoded = java.net.URLEncoder.encode(address, "UTF-8")
                        val conn = URL("${Constants.BASE_URL}/address/coord?query=$encoded").openConnection() as HttpURLConnection
                        conn.requestMethod = "GET"; conn.setRequestProperty("Authorization", "Bearer $token"); conn.connectTimeout = 8000; conn.readTimeout = 8000
                        if (conn.responseCode == 200) {
                            val json = JSONObject(conn.inputStream.bufferedReader().readText())
                            val lat = json.optDouble("lat", 0.0); val lon = json.optDouble("lon", json.optDouble("lng", 0.0))
                            if (lat != 0.0 || lon != 0.0) withContext(Dispatchers.Main) { currentNaviTripId = tripId; bottomSheetBehavior?.state = BottomSheetBehavior.STATE_COLLAPSED; optimizeAndStartNavi(tripId, displayName, rawDestLat, rawDestLon, lat, lon, address) }
                            else withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, getString(R.string.navi_origin_not_found), Toast.LENGTH_SHORT).show() }
                        } else withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, getString(R.string.navi_origin_not_found), Toast.LENGTH_SHORT).show() }
                    } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "오류: ${e.message}", Toast.LENGTH_SHORT).show() } }
                }
            }.setNegativeButton(getString(R.string.common_cancel), null).show()
    }

    override fun didUpdateCitsGuide(aGuidance: KNGuidance, aCitsGuide: KNGuide_Cits) { if (::naviView.isInitialized) naviView.didUpdateCitsGuide(aGuidance, aCitsGuide) }

    // =========================================================================
    // DemoCallback 구현 — 데모 시나리오 시작
    // =========================================================================

    /**
     * BaseActivity 의 데모 다이얼로그에서 시나리오가 선택됐을 때 호출된다.
     *
     * 1. scenario.stops → currentStops 에 주입 (하차지도착 / 휴게소 입장 오버레이 치리용)
     * 2. KNSDK 네비게이션을 시나리오 시스트/도착지로 시작 (NaviView 에 경로 표시)
     * 3. DemoScenarioPlayer 가 Mock GPS 를 주입해 실제 지도마커 이동
     */
    override fun onDemoScenarioSelected(scenario: DemoScenarioPlayer.DemoScenario) {
        if (scenario.trackPoints.size < 2) {
            Toast.makeText(this, "시나리오 데이터가 부족합니다.", Toast.LENGTH_SHORT).show()
            return
        }

        // ─ 1. 데모 트립 ID + currentStops 설정
        val demoTripId = "demo_${scenario.id}"
        currentNaviTripId = demoTripId
        currentStops.clear()
        visitedRestStopKeys.clear()
        autoCompleteTriggered = false
        currentStopPhase.clear()
        scenario.stops.forEach { stop ->
            currentStops.add(RouteStop(
                id    = "",
                name  = stop.name,
                lat   = stop.lat,
                lng   = stop.lon,
                type  = stop.type
            ))
        }

        // ─ 2. BottomSheet 닫기 + Toast
        bottomSheetBehavior?.state = BottomSheetBehavior.STATE_COLLAPSED
        Toast.makeText(this, "🗺️ ${scenario.name} 데모 시작!", Toast.LENGTH_SHORT).show()

        // ─ 3. 시나리오 첫 번째 / 마지막 정보
        val originPt = scenario.trackPoints.first()
        val destStop = scenario.stops.lastOrNull()
        val destLat  = destStop?.lat ?: scenario.trackPoints.last().lat
        val destLon  = destStop?.lon ?: scenario.trackPoints.last().lon
        val destName = destStop?.name ?: scenario.name

        // ─ 4. 속도 배율 읽기
        val speed = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
            .getInt("demo_speed_multiplier", 3)
        demoPlayer.speedMultiplier = speed

        // ─ 5. KNSDK 네비 시작 (실제 경로 라우팅 → NaviView 에 경로 표시됨)
        CoroutineScope(Dispatchers.IO).launch {
            val originKatec = convertWGS84ToKATEC(originPt.lat, originPt.lon)
            val destKatec   = convertWGS84ToKATEC(destLat, destLon)
            if (originKatec == null || destKatec == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "좌표 변환 실패 — 지도만 시뮬레이션됨", Toast.LENGTH_SHORT).show()
                }
            } else {
                val sp = KNPOI("출발지", originKatec.first, originKatec.second, "")
                val gp = KNPOI(destName, destKatec.first, destKatec.second, "")
                // 중간 stops (rest_stop 제외) 를 via 로
                val vias = mutableListOf<KNPOI>()
                scenario.stops.dropLast(1).forEach { stop ->
                    if (stop.type != "destination") {
                        convertWGS84ToKATEC(stop.lat, stop.lon)?.let {
                            vias.add(KNPOI(stop.name, it.first, it.second, ""))
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    startNavigationWithWaypoints(sp, gp, vias)
                }
            }

            // ─ 6. Mock GPS 시뮬레이션 시작 (네비 시작 후 1초 딥 대기)
            withContext(Dispatchers.Main) {
                android.os.Handler(Looper.getMainLooper()).postDelayed({
                    demoPlayer.play(
                        scenario = scenario,
                        onLocation = { lat, lon, speedKmh ->
                            // 서버에 GPS 전송 (관리자 대시보드에 표시됨)
                            sendLocationToServer(lat, lon, speedKmh / 3.6f)
                            // 하차지 근접 체크 → 오버레이 버튼 표시
                            checkProximityToStops(lat, lon)
                            lastLat = lat; lastLng = lon
                        },
                        onFinished = {
                            Toast.makeText(
                                this@MainActivity,
                                "✅ 데모 시나리오 완료!",
                                Toast.LENGTH_LONG
                            ).show()
                            currentNaviTripId = null
                            currentStops.clear()
                        }
                    )
                }, 1_000L)
            }
        }
    }
}
