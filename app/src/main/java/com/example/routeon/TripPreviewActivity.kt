package com.example.routeon

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject

class TripPreviewActivity : BaseActivity() {

    companion object {
        // 카카오 개발자 콘솔 JS API 키 (하드코딩)
        private const val KAKAO_JS_KEY = "362ecd9f0f9f8cd9b1a4840ae84c6021"

        // ★ Kakao Maps SDK 도메인 검증 통과용 baseURL
        //   카카오 개발자 콘솔 '웹 플랫폼'에 등록된 도메인 = 관리자 웹 프론트엔드
        //   https://m.map.kakao.com 은 카카오 자체 도메인 → SDK가 감지하고 차단함
        private const val MAP_BASE_URL = "http://168.138.45.63:3000"
    }

    private lateinit var webViewMap: WebView

    private val isNightModeNow: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-Edge: 상태바·네비바 뒤까지 콘텐츠 확장
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor     = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContentView(R.layout.activity_trip_preview)

        val ic = WindowInsetsControllerCompat(window, window.decorView)
        ic.isAppearanceLightStatusBars     = !isNightModeNow
        ic.isAppearanceLightNavigationBars = !isNightModeNow

        // ── Intent 파싱 ─────────────────────────────────────────────────────────
        val tripId        = intent.getStringExtra("trip_id")        ?: run { finish(); return }
        val distKm        = intent.getFloatExtra("distance_km",  0f)
        val durMin        = intent.getFloatExtra("duration_min", 0f)
        val waypointsJson = intent.getStringExtra("waypoints_json") ?: "[]"
        val pickupName    = intent.getStringExtra("pickup_name")    ?: ""
        val destName      = intent.getStringExtra("dest_name")      ?: ""

        // ── 뷰 초기화 ───────────────────────────────────────────────────────────
        val btnBack        = findViewById<FrameLayout>(R.id.btnBack)
        val tvBackArrow    = findViewById<TextView>(R.id.tvBackArrow)
        val tvPickup       = findViewById<TextView>(R.id.tvPickup)
        val tvDest         = findViewById<TextView>(R.id.tvDest)
        val tvDistance     = findViewById<TextView>(R.id.tvDistance)
        val tvDuration     = findViewById<TextView>(R.id.tvDuration)
        val tvEstLabel     = findViewById<TextView>(R.id.tvEstimateLabel)
        webViewMap         = findViewById(R.id.webViewMap)
        val loadingOverlay = findViewById<LinearLayout>(R.id.loadingOverlay)
        val bottomCard     = findViewById<LinearLayout>(R.id.bottomCard)
        val cardContent    = findViewById<LinearLayout>(R.id.cardContent)

        // ── 시스템 인셋: 뒤로가기 버튼 상단 마진 / 하단 카드 bottom 패딩 ────────
        ViewCompat.setOnApplyWindowInsetsListener(btnBack) { view, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (view.layoutParams as FrameLayout.LayoutParams).topMargin = sb.top + dpToPx(8)
            view.requestLayout()
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(bottomCard) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            cardContent.setPadding(
                dpToPx(20), dpToPx(20), dpToPx(20),
                sb.bottom + dpToPx(20)
            )
            insets
        }

        // ── 뒤로가기 버튼: 흰 원형 스타일 ────────────────────────────────────
        btnBack.background = GradientDrawable().apply {
            shape    = GradientDrawable.OVAL
            setColor(if (isNightModeNow) Color.parseColor("#2A2A35") else Color.WHITE)
        }
        btnBack.elevation = dpToPx(4).toFloat()
        tvBackArrow.setTextColor(
            if (isNightModeNow) Color.WHITE else Color.parseColor("#333333")
        )
        btnBack.setOnClickListener { finish() }

        // ── 하단 카드: 위쪽 모서리만 라운드 ───────────────────────────────────
        val r = dpToPx(20).toFloat()
        bottomCard.background = GradientDrawable().apply {
            setColor(if (isNightModeNow) Color.parseColor("#1E1E2A") else Color.WHITE)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        bottomCard.elevation = dpToPx(8).toFloat()

        // ── 경유지 파싱 ─────────────────────────────────────────────────────────
        val waypoints    = try { JSONArray(waypointsJson) } catch (e: Exception) { JSONArray() }
        val loadingPts   = mutableListOf<JSONObject>()
        val unloadingPts = mutableListOf<JSONObject>()
        for (i in 0 until waypoints.length()) {
            val wp = waypoints.getJSONObject(i)
            if (wp.optString("type", "unloading") == "loading") loadingPts.add(wp)
            else unloadingPts.add(wp)
        }

        // ── 주소 표시 ────────────────────────────────────────────────────────────
        tvPickup.text = pickupName.ifEmpty {
            loadingPts.firstOrNull()
                ?.optString("name", "")?.takeIf { it.isNotEmpty() } ?: "픽업지 정보 없음"
        }
        tvDest.text = destName.ifEmpty {
            (unloadingPts.lastOrNull() ?: loadingPts.lastOrNull())
                ?.optString("name", "")?.takeIf { it.isNotEmpty() } ?: "도착지 정보 없음"
        }

        // ── 거리 / 시간 계산 ─────────────────────────────────────────────────────
        val hasRealData    = distKm > 0f
        val computedDistKm = if (hasRealData) distKm.toDouble()
                             else computeRouteDistance(waypoints)
        val computedDurMin = when {
            hasRealData           -> durMin.toDouble()
            computedDistKm > 0.0 -> computedDistKm * 1.35 / 40.0 * 60.0
            else                  -> 0.0
        }

        if (computedDistKm > 0) {
            tvDistance.text = String.format("%.1f km", computedDistKm)
            val h = (computedDurMin / 60).toInt()
            val m = (computedDurMin % 60).toInt()
            tvDuration.text = if (h > 0) "${h}시간 ${m}분" else "${m}분"
        } else {
            tvDistance.text = "정보 없음"
            tvDuration.text = "정보 없음"
        }
        tvEstLabel.visibility =
            if (!hasRealData && computedDistKm > 0) View.VISIBLE else View.GONE

        // ── WebView 설정 ─────────────────────────────────────────────────────────
        setupWebView()

        // ── 지도 로드 ────────────────────────────────────────────────────────────
        // baseURL = 카카오 개발자 콘솔에 등록된 도메인 → SDK 도메인 검증 통과
        loadingOverlay.visibility = View.VISIBLE
        webViewMap.loadDataWithBaseURL(
            MAP_BASE_URL,
            buildMapHtml(waypointsJson, isNightModeNow),
            "text/html", "UTF-8", null
        )
        // SDK 초기화(네트워크 로드) 완료 후 오버레이 제거
        Handler(Looper.getMainLooper()).postDelayed(
            { loadingOverlay.visibility = View.GONE }, 2000L
        )
    }

    // ── WebView 설정: Kakao Maps 렌더링에 필요한 모든 옵션 ────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webViewMap.settings.apply {
            javaScriptEnabled        = true
            domStorageEnabled        = true
            loadWithOverviewMode     = true
            useWideViewPort          = true
            setSupportZoom(true)
            builtInZoomControls      = true
            displayZoomControls      = false
            // HTTP 지도 타일 허용 (서버가 HTTP)
            @Suppress("DEPRECATION")
            mixedContentMode         = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowContentAccess       = true
            allowFileAccess          = true
            databaseEnabled          = true
            cacheMode                = WebSettings.LOAD_DEFAULT
            blockNetworkImage        = false
            loadsImagesAutomatically = true
        }
        // Kakao Maps 렌더링에 하드웨어 가속 필수
        webViewMap.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        // JS console.log / alert 등 처리 (SDK 내부 경고 포함)
        webViewMap.webChromeClient = WebChromeClient()
        webViewMap.webViewClient   = WebViewClient()
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()

    // ── Haversine 직선거리 합산 ───────────────────────────────────────────────
    private fun computeRouteDistance(waypoints: JSONArray): Double {
        if (waypoints.length() < 2) return 0.0
        var total = 0.0
        for (i in 0 until waypoints.length() - 1) {
            val a = waypoints.getJSONObject(i)
            val b = waypoints.getJSONObject(i + 1)
            total += haversine(
                a.optDouble("lat", 0.0), a.optDouble("lon", 0.0),
                b.optDouble("lat", 0.0), b.optDouble("lon", 0.0)
            )
        }
        return total
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (lat1 == 0.0 || lon1 == 0.0 || lat2 == 0.0 || lon2 == 0.0) return 0.0
        val R    = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a    = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    // ── Kakao Maps JS HTML ────────────────────────────────────────────────────
    // ★ autoload=false + kakao.maps.load() 콜백 방식:
    //   SDK 스크립트 다운로드 완료 후 명시적으로 초기화 → 타이밍 문제 없음
    private fun buildMapHtml(waypointsJson: String, isDark: Boolean): String {
        val mapBg       = if (isDark) "#1a1a2e"             else "#e8e8e8"
        val labelBg     = if (isDark) "rgba(30,30,42,0.92)" else "rgba(255,255,255,0.95)"
        val labelColor  = if (isDark) "#EEEEEE"             else "#333333"
        val labelBorder = if (isDark) "#555555"             else "#DDDDDD"
        val dashColor   = if (isDark) "#888888"             else "#AAAAAA"

        // 한글 JS 문자열 리터럴을 유니코드 이스케이프로 처리
        // 상차 = \uc0c1\ucc28, 하차 = \ud558\ucc28, … = \u2026
        return buildString {
            append("<!DOCTYPE html>\n<html>\n<head>\n")
            append("<meta charset=\"utf-8\">\n")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,maximum-scale=5.0\">\n")
            append("<style>\n* { margin:0; padding:0; box-sizing:border-box; }\n")
            append("html, body { width:100%; height:100%; background:$mapBg; }\n")
            append("#map { width:100%; height:100vh; }\n</style>\n</head>\n<body>\n")
            append("<div id=\"map\"></div>\n")
            // autoload=false: 스크립트 다운로드만, 초기화는 kakao.maps.load() 콜백에서
            append("<script type=\"text/javascript\"\n")
            append("  src=\"https://dapi.kakao.com/v2/maps/sdk.js?appkey=$KAKAO_JS_KEY&autoload=false\">\n")
            append("</script>\n")
            append("<script type=\"text/javascript\">\n")
            append("kakao.maps.load(function(){\n")
            append("'use strict';\n")
            append("var RAW_WP=$waypointsJson;\n")
            append("var container=document.getElementById('map');\n")
            append("var map=new kakao.maps.Map(container,{center:new kakao.maps.LatLng(37.5665,126.9780),level:8});\n")
            append("map.addControl(new kakao.maps.ZoomControl(),kakao.maps.ControlPosition.RIGHT);\n")
            append("var bounds=new kakao.maps.LatLngBounds();\n")
            append("var allPos=[]; var items=[];\n")
            append("for(var i=0;i<RAW_WP.length;i++){\n")
            append("  var wp=RAW_WP[i];\n")
            append("  var lat=wp.lat||wp.latitude||0;\n")
            append("  var lon=wp.lon||wp.lng||wp.longitude||0;\n")
            append("  if(!lat||!lon)continue;\n")
            append("  var pos=new kakao.maps.LatLng(lat,lon);\n")
            append("  allPos.push(pos); bounds.extend(pos);\n")
            append("  items.push({pos:pos,type:wp.type||'unloading',name:wp.name||''});\n")
            append("}\n")
            // 연결 점선
            append("if(allPos.length>=2){\n")
            append("  new kakao.maps.Polyline({map:map,path:allPos,")
            append("strokeWeight:3,strokeColor:'$dashColor',strokeOpacity:0.65,strokeStyle:'dashed'});\n")
            append("}\n")
            // 마커 루프
            append("for(var j=0;j<items.length;j++){\n")
            append("  var it=items[j];\n")
            append("  var isLoad=(it.type==='loading');\n")
            append("  var bgColor=isLoad?'#F97316':'#0277BD';\n")
            append("  var label=isLoad?'\\uc0c1\\ucc28':'\\ud558\\ucc28';\n")
            append("  var pinHtml='<div style=\"display:flex;flex-direction:column;align-items:center;pointer-events:none\">'\n")
            append("    +'<div style=\"background:'+bgColor+';color:#fff;font-weight:700;font-size:13px;'\n")
            append("    +'padding:5px 16px;border-radius:20px 20px 20px 4px;white-space:nowrap;'\n")
            append("    +'line-height:1.5;box-shadow:0 3px 10px rgba(0,0,0,0.28)\">'+label+'</div>'\n")
            append("    +'<div style=\"width:2px;height:9px;background:'+bgColor+'\"></div>'\n")
            append("    +'<div style=\"width:8px;height:8px;border-radius:50%;background:'+bgColor\n")
            append("    +';box-shadow:0 1px 4px rgba(0,0,0,0.22)\"></div>'\n")
            append("    +'</div>';\n")
            append("  new kakao.maps.CustomOverlay({map:map,position:it.pos,content:pinHtml,xAnchor:0.5,yAnchor:1.0});\n")
            append("  if(it.name){\n")
            append("    var nm=it.name.length>13?it.name.substr(0,13)+'\\u2026':it.name;\n")
            append("    var nameHtml='<div style=\"background:$labelBg;color:$labelColor;'\n")
            append("      +'font-size:11px;padding:3px 8px;border-radius:8px;'\n")
            append("      +'border:1px solid $labelBorder;white-space:nowrap;'\n")
            append("      +'box-shadow:0 1px 4px rgba(0,0,0,0.12);pointer-events:none\">'+nm+'</div>';\n")
            append("    new kakao.maps.CustomOverlay({map:map,position:it.pos,content:nameHtml,xAnchor:0.5,yAnchor:-0.35});\n")
            append("  }\n")
            append("}\n")
            // 카메라 Fit
            append("if(allPos.length>=2){ map.setBounds(bounds,80,60,260,60); }\n")
            append("else if(allPos.length===1){ map.setCenter(allPos[0]); map.setLevel(5); }\n")
            append("});\n")
            append("</script>\n</body>\n</html>")
        }
    }
}
