package com.example.routeon

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
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
        private const val KAKAO_JS_KEY = "362ecd9f0f9f8cd9b1a4840ae84c6021"
        private const val MAP_BASE_URL = "http://168.138.45.63:3000"
    }

    private lateinit var webViewMap: WebView

    private val isNightModeNow: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setContentView(R.layout.activity_trip_preview)

        val ic = WindowInsetsControllerCompat(window, window.decorView)
        ic.isAppearanceLightStatusBars = !isNightModeNow
        ic.isAppearanceLightNavigationBars = !isNightModeNow

        val tripId = intent.getStringExtra("trip_id") ?: run { finish(); return }
        val distKm = intent.getFloatExtra("distance_km", 0f)
        val durMin = intent.getFloatExtra("duration_min", 0f)
        val waypointsJson = intent.getStringExtra("waypoints_json") ?: "[]"
        val pickupName = intent.getStringExtra("pickup_name") ?: ""
        val destName = intent.getStringExtra("dest_name") ?: ""
        val status = intent.getStringExtra("status") ?: ""

        val btnBack = findViewById<FrameLayout>(R.id.btnBack)
        val tvBackArrow = findViewById<TextView>(R.id.tvBackArrow)
        val tvPickup = findViewById<TextView>(R.id.tvPickup)
        val tvDest = findViewById<TextView>(R.id.tvDest)
        val tvDistance = findViewById<TextView>(R.id.tvDistance)
        val tvDuration = findViewById<TextView>(R.id.tvDuration)
        val tvEstLabel = findViewById<TextView>(R.id.tvEstimateLabel)
        webViewMap = findViewById(R.id.webViewMap)
        val loadingOverlay = findViewById<LinearLayout>(R.id.loadingOverlay)
        val bottomCard = findViewById<LinearLayout>(R.id.bottomCard)
        val cardContent = findViewById<LinearLayout>(R.id.cardContent)

        ViewCompat.setOnApplyWindowInsetsListener(btnBack) { view, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (view.layoutParams as FrameLayout.LayoutParams).topMargin = sb.top + dpToPx(8)
            view.requestLayout()
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(bottomCard) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            cardContent.setPadding(dpToPx(20), dpToPx(20), dpToPx(20), sb.bottom + dpToPx(20))
            insets
        }

        btnBack.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (isNightModeNow) Color.parseColor("#2A2A35") else Color.WHITE)
        }
        btnBack.elevation = dpToPx(4).toFloat()
        tvBackArrow.setTextColor(if (isNightModeNow) Color.WHITE else Color.parseColor("#333333"))
        btnBack.setOnClickListener { finish() }

        val r = dpToPx(20).toFloat()
        bottomCard.background = GradientDrawable().apply {
            setColor(if (isNightModeNow) Color.parseColor("#1E1E2A") else Color.WHITE)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        bottomCard.elevation = dpToPx(8).toFloat()

        val waypoints = try {
            JSONArray(waypointsJson)
        } catch (e: Exception) {
            JSONArray()
        }
        val loadingPts = mutableListOf<JSONObject>()
        val unloadingPts = mutableListOf<JSONObject>()
        for (i in 0 until waypoints.length()) {
            val wp = waypoints.getJSONObject(i)
            if (wp.optString("type", "unloading") == "loading") loadingPts.add(wp)
            else unloadingPts.add(wp)
        }

        tvPickup.text = pickupName.ifEmpty {
            loadingPts.firstOrNull()?.optString("name", "")
                ?.takeIf { it.isNotEmpty() } ?: "픽업지 정보 없음"
        }
        tvDest.text = destName.ifEmpty {
            (unloadingPts.lastOrNull() ?: loadingPts.lastOrNull())
                ?.optString("name", "")?.takeIf { it.isNotEmpty() } ?: "도착지 정보 없음"
        }

        val hasRealData = distKm > 0f
        val computedDistKm = if (hasRealData) distKm.toDouble() else computeRouteDistance(waypoints)
        val computedDurMin = when {
            hasRealData -> durMin.toDouble()
            computedDistKm > 0.0 -> computedDistKm * 1.35 / 40.0 * 60.0
            else -> 0.0
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
        tvEstLabel.visibility = if (!hasRealData && computedDistKm > 0) View.VISIBLE else View.GONE

        val btnAccept =
            findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnAcceptDispatch)
        if (status == "scheduled") {
            btnAccept.background = GradientDrawable().apply {
                setColor(Color.parseColor("#2E7D32"))
                cornerRadius = dpToPx(14).toFloat()
            }
            btnAccept.visibility = View.VISIBLE
            btnAccept.setOnClickListener {
                setResult(
                    android.app.Activity.RESULT_OK,
                    android.content.Intent().putExtra("accepted_trip_id", tripId)
                )
                finish()
            }
        } else {
            btnAccept.visibility = View.GONE
        }

        setupWebView()

        // ── 현재 위치 취득 후 지도 로드 ─────────────────────────────────────────
        loadingOverlay.visibility = View.VISIBLE
        val currentLoc = getLastLocation()
        webViewMap.loadDataWithBaseURL(
            MAP_BASE_URL,
            buildMapHtml(waypointsJson, isNightModeNow, currentLoc),
            "text/html", "UTF-8", null
        )
        Handler(Looper.getMainLooper()).postDelayed(
            { loadingOverlay.visibility = View.GONE }, 2000L
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webViewMap.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            @Suppress("DEPRECATION")
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowContentAccess = true
            allowFileAccess = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            blockNetworkImage = false
            loadsImagesAutomatically = true
        }
        webViewMap.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webViewMap.webChromeClient = WebChromeClient()
        webViewMap.webViewClient = WebViewClient()
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()

    // ── GPS 마지막 위치 취득 (LocationManager) ───────────────────────────────
    @SuppressLint("MissingPermission")
    private fun getLastLocation(): Pair<Double, Double>? {
        return try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            if (loc != null && loc.accuracy < 500f) Pair(loc.latitude, loc.longitude) else null
        } catch (e: Exception) {
            null
        }
    }

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
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    // ── Kakao Maps JS HTML ────────────────────────────────────────────────────
    // ── Kakao Maps JS HTML ────────────────────────────────────────────────────
    private fun buildMapHtml(
        waypointsJson: String,
        isDark: Boolean,
        currentLoc: Pair<Double, Double>? = null
    ): String {
        val mapBg = if (isDark) "#1a1a2e" else "#e8e8e8"
        val labelBg = if (isDark) "rgba(30,30,42,0.92)" else "rgba(255,255,255,0.95)"
        val labelColor = if (isDark) "#EEEEEE" else "#333333"
        val labelBorder = if (isDark) "#555555" else "#DDDDDD"
        val curLat = currentLoc?.first ?: 0.0
        val curLon = currentLoc?.second ?: 0.0

        return buildString {
            append("<!DOCTYPE html>\n<html>\n<head>\n")
            append("<meta charset=\"utf-8\">\n")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,maximum-scale=5.0\">\n")
            append("<style>\n")
            append("* { margin:0; padding:0; box-sizing:border-box; }\n")
            append("html, body { width:100%; height:100%; background:$mapBg; }\n")
            append("#map { width:100%; height:100vh; }\n")
            append(".kakao_widget,.wrap_map_type,.map_type_btn,")
            append("[class*='map_type'],[class*='maptype'],[class*='widget']{display:none!important;}\n")
            append("</style>\n</head>\n<body>\n")
            append("<div id=\"map\"></div>\n")
            append("<script src=\"https://dapi.kakao.com/v2/maps/sdk.js?appkey=$KAKAO_JS_KEY&autoload=false\"></script>\n")
            append("<script>\n")
            append("kakao.maps.load(function(){\n")
            append("'use strict';\n")

            append("var RAW_WP=$waypointsJson;\n")
            append("var CUR_LAT=$curLat, CUR_LON=$curLon;\n")

            append("var map=new kakao.maps.Map(document.getElementById('map'),")
            append("{center:new kakao.maps.LatLng(37.5665,126.9780),level:8});\n")
            append("var bounds=new kakao.maps.LatLngBounds();\n")

            append("var allPos=[],items=[];\n")
            append("for(var i=0;i<RAW_WP.length;i++){\n")
            append("  var wp=RAW_WP[i];\n")
            append("  var lat=wp.lat||wp.latitude||0, lon=wp.lon||wp.lng||wp.longitude||0;\n")
            append("  if(!lat||!lon)continue;\n")
            append("  var pos=new kakao.maps.LatLng(lat,lon);\n")
            append("  allPos.push(pos); bounds.extend(pos);\n")
            append("  items.push({pos:pos,type:wp.type||'unloading',name:wp.name||''});\n")
            append("}\n")

            // ── 현위치 핀 (\\u 이스케이프 적용)
            append("if(CUR_LAT&&CUR_LON){\n")
            append("  var curPos=new kakao.maps.LatLng(CUR_LAT,CUR_LON);\n")
            append("  bounds.extend(curPos);\n")
            append("  var curHtml='<div style=\"display:flex;flex-direction:column;align-items:center;pointer-events:none\">'\n")
            append("    +'<div style=\"background:#66BB6A;color:#fff;font-weight:700;font-size:13px;'\n")
            append("    +'padding:5px 14px;border-radius:20px 20px 4px 20px;'\n")
            append("    +'white-space:nowrap;line-height:1.5;box-shadow:0 3px 10px rgba(0,0,0,0.28)\">\\u2022 \\ud604\\uc704\\uce58</div>'\n")
            append("    +'<div style=\"width:2px;height:9px;background:#66BB6A\"></div>'\n")
            append("    +'<div style=\"width:8px;height:8px;border-radius:50%;background:#66BB6A;'\n")
            append("    +'box-shadow:0 1px 4px rgba(0,0,0,0.22)\"></div>'\n")
            append("    +'</div>';\n")
            append("  new kakao.maps.CustomOverlay({map:map,position:curPos,content:curHtml,xAnchor:0.5,yAnchor:1.0});\n")
            append("}\n")

            append("if(allPos.length>=2){\n")
            append("  new kakao.maps.Polyline({map:map,path:allPos,")
            append("strokeWeight:6,strokeColor:'#F97316',strokeOpacity:0.92,strokeStyle:'dashed'});\n")
            append("}\n")

            append("var ldCnt=0,ulCnt=0;\n")
            append("for(var k=0;k<items.length;k++){if(items[k].type==='loading')ldCnt++;else ulCnt++;}\n")
            append("var ldIdx=0,ulIdx=0;\n")

            // ── 상/하차 핀 (\\u 이스케이프 복구)
            append("for(var j=0;j<items.length;j++){\n")
            append("  var it=items[j];\n")
            append("  var isLoad=(it.type==='loading');\n")
            append("  var bg=isLoad?'#F97316':'#0277BD';\n")
            append("  var base=isLoad?'\\uc0c1\\ucc28':'\\ud558\\ucc28';\n")
            append("  var cnt=isLoad?ldCnt:ulCnt;\n")
            append("  var idx=isLoad?(++ldIdx):(++ulIdx);\n")
            append("  var label=(cnt>1)?(base+idx):base;\n")
            append("  var pin='<div style=\"display:flex;flex-direction:column;align-items:center;pointer-events:none\">'\n")
            append("    +'<div style=\"background:'+bg+';color:#fff;font-weight:700;font-size:13px;'\n")
            append("    +'padding:5px 16px;border-radius:20px 20px 20px 4px;white-space:nowrap;'\n")
            append("    +'line-height:1.5;box-shadow:0 3px 10px rgba(0,0,0,0.28)\">'+label+'</div>'\n")
            append("    +'<div style=\"width:2px;height:9px;background:'+bg+'\"></div>'\n")
            append("    +'<div style=\"width:8px;height:8px;border-radius:50%;background:'+bg\n")
            append("    +';box-shadow:0 1px 4px rgba(0,0,0,0.22)\"></div>'\n")
            append("    +'</div>';\n")
            append("  new kakao.maps.CustomOverlay({map:map,position:it.pos,content:pin,xAnchor:0.5,yAnchor:1.0});\n")
            append("  if(it.name){\n")
            append("    var nm=it.name.length>13?it.name.substr(0,13)+'\\u2026':it.name;\n")
            append("    var nlabel='<div style=\"background:$labelBg;color:$labelColor;font-size:11px;'\n")
            append("      +'padding:3px 8px;border-radius:8px;border:1px solid $labelBorder;'\n")
            append("      +'white-space:nowrap;box-shadow:0 1px 4px rgba(0,0,0,0.12);pointer-events:none\">'+nm+'</div>';\n")
            append("    new kakao.maps.CustomOverlay({map:map,position:it.pos,content:nlabel,xAnchor:0.5,yAnchor:-0.35});\n")
            append("  }\n")
            append("}\n")

            append("if(allPos.length>=2){ map.setBounds(bounds,80,60,260,60); }\n")
            append("else if(allPos.length===1){ map.setCenter(allPos[0]); map.setLevel(5); }\n")
            append("else if(CUR_LAT&&CUR_LON){ map.setCenter(new kakao.maps.LatLng(CUR_LAT,CUR_LON)); map.setLevel(6); }\n")

            // ── Switch 컨트롤 제거 로직 (호환성 높은 일반 for 루프로 복구)
            append("function _rm(){\n")
            append("  var m=document.getElementById('map');if(!m)return;\n")
            append("  var all=m.querySelectorAll('*');\n")
            append("  for(var i=0;i<all.length;i++){\n")
            append("    var el=all[i];\n")
            append("    if(el.children.length===0){\n")
            append("      var t=(el.textContent||'').trim();\n")
            append("      if(t==='Switch'||t==='\\uc9c0\\ub3c4'||t==='\\uc2a4\\uce74\\uc774\\ubdf0'||t==='\\uc704\\uc131\\uc9c0\\ub3c4'){\n")
            append("        var p=el.parentElement;\n")
            append("        while(p&&p!==m){if(p.style&&p.style.position==='absolute'){p.style.display='none';break;}p=p.parentElement;}\n")
            append("      }\n")
            append("    }\n")
            append("  }\n")
            append("}\n")
            append("setTimeout(_rm,300);setTimeout(_rm,1000);\n")
            append("});\n")
            append("</script>\n</body>\n</html>")
        }
    }
}