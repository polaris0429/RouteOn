package com.example.routeon

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
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
    SensorEventListener,
    BaseActivity.DevRestModeCallback {

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
    private var permissionDialog: AlertDialog? = null

    private var isActivityResumed = false
    @Volatile private var pendingReplan: PendingReplan? = null

    private var isRestStopActive = false
    private var restStopCountDown: CountDownTimer? = null
    private val visitedRestStopKeys = mutableSetOf<String>()
    private val REST_STOP_RADIUS_M = 150f
    private val REST_STOP_DURATION_MS = 15 * 60 * 1000L
    private val REST_STOP_DEMO_DURATION_MS = 10 * 1000L

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
    private val tripOriginText  = mutableMapOf<String, String>()
    private val tripDestText    = mutableMapOf<String, String>()
    private val tripOriginCoords = mutableMapOf<String, DoubleArray>()
    private val tripDestCoords   = mutableMapOf<String, DoubleArray>()

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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED)
            permissionsToRequest.add(Manifest.permission.ANSWER_PHONE_CALLS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE)
        if (permissionsToRequest.isNotEmpty())
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), permissionRequestCode)
        else { initKakaoNaviSDK(); startLocationUpdates(); checkSpecialPermissions() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            var locationGranted = false
            for (i in permissions.indices) {
                if (permissions[i] == Manifest.permission.ACCESS_FINE_LOCATION && grantResults[i] == PackageManager.PERMISSION_GRANTED) locationGranted = true
            }
            if (locationGranted) { initKakaoNaviSDK(); startLocationUpdates(); checkSpecialPermissions() }
            else { Toast.makeText(this, getString(R.string.navi_location_permission), Toast.LENGTH_LONG).show(); finish() }
        }
    }

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
                    if (!isNotificationEnabled) startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                    else startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
                .setCancelable(false).create()
            permissionDialog?.show()
        } else { permissionDialog?.dismiss(); permissionDialog = null }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!flat.isNullOrEmpty()) {
            for (name in flat.split(":")) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && cn.packageName == packageName) return true
            }
        }
        return false
    }

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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            checkSpecialPermissions()
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
        permissionDialog?.dismiss()
    }

    override fun onDestroy() {
        super.onDestroy()
        refreshHandler.removeCallbacks(refreshRunnable)
        webSocket?.cancel()
        sensorManager.unregisterListener(this)
        permissionDialog?.dismiss(); permissionDialog = null
        restStopCountDown?.cancel(); restStopCountDown = null
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

    private fun showRestStopOverlay(stopName: String) {
        if (isRestStopActive) return
        isRestStopActive = true
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

    private fun hideRestStopOverlay() {
        isRestStopActive = false; restStopCountDown?.cancel(); restStopCountDown = null
        binding.tvRestTimer.setTextColor(Color.parseColor("#4CAF50"))
        binding.restStopOverlay.animate().alpha(0f).setDuration(600).withEndAction { binding.restStopOverlay.visibility = View.GONE }.start()
        Toast.makeText(this, "✅ 휴식이 완료되었습니다. 안전 운행하세요! 🚛", Toast.LENGTH_LONG).show()
        Log.d("RestStop", "✅ 휴식 완료 — 네비 복구")
    }

    private fun checkProximityToStops(currentLat: Double, currentLng: Double) {
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
                    runOnUiThread { showRestStopOverlay(stop.name) }
                    return
                }
            }
        }
        if (isRestStopActive) { runOnUiThread { binding.btnCompleteTrip.visibility = View.GONE }; return }
        var nearbyStop: RouteStop? = null
        for (stop in currentStops) {
            if (stop.type == "rest_stop") continue
            val dist = FloatArray(1)
            android.location.Location.distanceBetween(currentLat, currentLng, stop.lat, stop.lng, dist)
            if (dist[0] <= 100) { nearbyStop = stop; break }
        }
        runOnUiThread {
            if (nearbyStop != null) {
                binding.btnCompleteTrip.visibility = View.VISIBLE
                when (nearbyStop.type) {
                    "destination" -> {
                        binding.btnCompleteTrip.text = getString(R.string.navi_btn_complete_dest)
                        binding.btnCompleteTrip.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2E7D32"))
                        binding.btnCompleteTrip.setOnClickListener { currentNaviTripId?.let { updateTripStatus(it, "completed") } }
                    }
                    "loading" -> {
                        binding.btnCompleteTrip.text = "🚛 상차 완료 (${nearbyStop.name})"
                        binding.btnCompleteTrip.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E65100"))
                        binding.btnCompleteTrip.setOnClickListener { completeDelivery(nearbyStop.id, nearbyStop.name) }
                    }
                    else -> {
                        binding.btnCompleteTrip.text = "${getString(R.string.navi_btn_complete_delivery)} (${nearbyStop.name})"
                        binding.btnCompleteTrip.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0288D1"))
                        binding.btnCompleteTrip.setOnClickListener { completeDelivery(nearbyStop.id, nearbyStop.name) }
                    }
                }
            } else binding.btnCompleteTrip.visibility = View.GONE
        }
    }

    private fun connectWebSocket() {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).getString("access_token", null) ?: return
        webSocket?.cancel(); webSocket = null
        val request = Request.Builder().url("${Constants.WS_URL}/ws/location?token=$token").build()
        webSocket = wsHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("LocationWS", "✅ 위치 웹소켓 연결 성공"); isLocationWsReconnecting = false
            }
            @SuppressLint("MissingPermission")
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("LocationWS", "📩 수신: $text")
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
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.navi_replan_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.navi_replan_confirm)) { _, _ ->
                fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                    val lat = loc?.latitude ?: lastLat; val lng = loc?.longitude ?: lastLng
                    if (lat != 0.0 && lng != 0.0) requestReplan(tripId, lat, lng, wps)
                    else Toast.makeText(this, "현재 위치를 확인할 수 없습니다.", Toast.LENGTH_SHORT).show()
                }.addOnFailureListener {
                    if (lastLat != 0.0 && lastLng != 0.0) requestReplan(tripId, lastLat, lastLng, wps)
                    else Toast.makeText(this, "현재 위치를 확인할 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }.setCancelable(false).show()
        Log.d("LocationWS", "🚨 팝업 표시 완료")
    }

    private fun scheduleWsReconnect() {
        if (isLocationWsReconnecting) return
        isLocationWsReconnecting = true
        Handler(Looper.getMainLooper()).postDelayed({ isLocationWsReconnecting = false; connectWebSocket() }, 3000)
    }

    private fun requestReplan(tripId: String, currentLat: Double, currentLng: Double, wps: JSONArray) {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).getString("access_token", null) ?: return
        Toast.makeText(this, getString(R.string.navi_replanning), Toast.LENGTH_LONG).show()
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

    private fun fetchTrips() {
        val token = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE).getString("access_token", null) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("${Constants.BASE_URL}/trips").openConnection() as HttpURLConnection
                conn.requestMethod = "GET"; conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                val code = conn.responseCode
                Log.d("FetchTrips", "📡 GET /trips → HTTP $code")
                when (code) {
                    200 -> {
                        val body = conn.inputStream.bufferedReader().readText()
                        Log.d("FetchTrips", "✅ 응답 (${body.length}자): ${body.take(200)}")
                        withContext(Dispatchers.Main) { processTripsUpdate(JSONArray(body)) }
                    }
                    401 -> withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "세션이 만료되었습니다. 다시 로그인해 주세요.", Toast.LENGTH_LONG).show() }
                    else -> Log.e("FetchTrips", "❌ HTTP $code")
                }
            } catch (e: Exception) { Log.e("FetchTrips", "💥 ${e::class.simpleName}: ${e.message}") }
        }
    }

    private fun processTripsUpdate(jsonArray: JSONArray) {
        val newStatuses = mutableMapOf<String, String>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val id = obj.optString("id", ""); val st = obj.optString("status", "")
            if (id.isNotEmpty()) newStatuses[id] = st
        }
        if (!isFirstFetch) {
            for ((id, status) in newStatuses) if (id !in knownTripStatuses && status in listOf("scheduled", "in_progress")) { playSequential(R.raw.bell, R.raw.trip_new); vibrate(200); break }
            for ((id, oldSt) in knownTripStatuses) if (oldSt !in listOf("cancelled","completed") && newStatuses[id] == "cancelled") { playSequential(R.raw.bell, R.raw.trip_cancel); vibrate(200) }
            for ((id, oldSt) in knownTripStatuses) if (oldSt !in listOf("cancelled","completed") && newStatuses[id] == "completed") { playSequential(R.raw.bell, R.raw.trip_complite); vibrate(200) }
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
                    text = getString(R.string.navi_btn_complete_dest); textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor(if (isNightMode) "#90CAF9" else "#1565C0"))
                    background = GradientDrawable().apply { setColor(if (isNightMode) Color.parseColor("#0D2137") else Color.parseColor("#E3F2FD")); cornerRadius = dpToPx(12).toFloat() }
                    stateListAnimator = null; elevation = 0f; layoutParams = LinearLayout.LayoutParams(0, dpToPx(48), 1f).apply { marginEnd = dpToPx(8) }
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity).setTitle(getString(R.string.navi_cancel_confirm_title)).setMessage("운행을 완료 처리하시겠습니까?")
                            .setPositiveButton(getString(R.string.navi_yes)) { _, _ -> acceptedTripId = null; updateTripStatus(tripId, "completed") }
                            .setNegativeButton(getString(R.string.navi_no), null).show()
                    }
                })
                btnRow.addView(androidx.appcompat.widget.AppCompatButton(this).apply {
                    text = getString(R.string.navi_btn_start); textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { setColor(Color.parseColor("#2E7D32")); cornerRadius = dpToPx(12).toFloat() }
                    stateListAnimator = null; elevation = 0f; layoutParams = LinearLayout.LayoutParams(0, dpToPx(48), 1f)
                    setOnClickListener {
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
                card.addView(expandPanel); card.addView(plusRow2)
            }
            card.addView(btnRow)
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
        runOnUiThread {
            Toast.makeText(this@MainActivity, getString(R.string.navi_ended), Toast.LENGTH_SHORT).show()
            binding.naviContainer.removeAllViews(); naviView = KNNaviView(this@MainActivity); binding.naviContainer.addView(naviView)
            applyNaviSettings(); startSafeDriving()
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
    override fun guidanceDidUpdateLocation(aGuidance: KNGuidance, aLocationGuide: KNGuide_Location) { if (::naviView.isInitialized) naviView.guidanceDidUpdateLocation(aGuidance, aLocationGuide) }
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
}
