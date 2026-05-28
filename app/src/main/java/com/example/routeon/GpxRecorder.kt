package com.example.routeon

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * RouteOn GPX 생성기 (폴리라인 기반)
 *
 * 실주행 기록이 아닌 서버의 실도로 폴리라인을 그대로 GPX 로 변환한다.
 *
 * GPX 구조:
 *   <wpt>  — /optimize 응답의 경유지 (상차지·하차지·휴게소·도착지)
 *            → DemoScenarioPlayer 의 checkProximityToStops 용
 *   <trkpt> — GET /trips/{id}/polyline 의 실도로 좌표 배열
 *            → DemoScenarioPlayer 의 이동 경로 (실제 도로 곡선 따라 이동)
 *
 * 저장 경로: getExternalFilesDir(null)/gpx/
 */
class GpxRecorder(private val context: Context) {

    data class GpxPoint(
        val lat: Double,
        val lon: Double,
        val name: String = "",
        val type: String = "",
        val timestampMs: Long = System.currentTimeMillis()
    )

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val fileFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    val gpxDir: File
        get() = File(context.getExternalFilesDir(null), "gpx").also { it.mkdirs() }

    // ─────────────────────────────────────────────────────────────────────
    // 핵심: 경유지(wpt) + 서버 폴리라인(trkpt) 조합 GPX 저장
    // ─────────────────────────────────────────────────────────────────────

    /**
     * /optimize 응답의 route 배열과 /trips/{id}/polyline 의 좌표를 하나의 GPX 로 저장한다.
     *
     * @param tripId        운행 ID (파일명에 사용)
     * @param routeArray    /optimize 응답 route JSON 배열 (경유지 정보 — wpt 로 저장)
     * @param polylineArray /trips/{id}/polyline 응답 배열 (실도로 좌표 — trkpt 로 저장)
     *                      비어 있으면 routeArray 의 좌표를 trkpt 로 대체 사용
     * @return 저장된 GPX 파일, 실패 시 null
     */
    fun saveRouteWithPolyline(
        tripId: String,
        routeArray: JSONArray,
        polylineArray: JSONArray
    ): File? {
        // ── 1. 경유지 wpt 파싱 ──────────────────────────────────────────
        val stops = mutableListOf<GpxPoint>()
        var stopT = System.currentTimeMillis()
        for (i in 0 until routeArray.length()) {
            val pt  = routeArray.optJSONObject(i) ?: continue
            val lat = pt.optDouble("lat", 0.0)
            val lon = pt.optDouble("lon", pt.optDouble("lng", 0.0))
            val name = pt.optString("name", "")
            val type = pt.optString("type", pt.optString("node_type", "waypoint"))
            if (lat == 0.0 && lon == 0.0) continue
            stops.add(GpxPoint(lat, lon, name, type, stopT))
            stopT += 3_000L
        }
        if (stops.isEmpty()) {
            Log.w("GpxRecorder", "경유지 없음 — GPX 저장 스킵"); return null
        }

        // ── 2. 폴리라인 trkpt 파싱 (서버 실도로 좌표) ────────────────────
        val track = parsePolylineArray(polylineArray)
        val finalTrack = if (track.size >= 2) {
            Log.i("GpxRecorder", "폴리라인 trkpt ${track.size}개 사용")
            track
        } else {
            // 폴리라인이 없으면 경유지 좌표만으로 트랙 구성
            Log.w("GpxRecorder", "폴리라인 없음 — 경유지 좌표를 trkpt 로 대체")
            stops
        }

        return writeGpx("route_${tripId.take(8)}", stops, finalTrack).also { file ->
            if (file != null)
                Log.i("GpxRecorder", "✅ GPX 저장: ${file.name} | wpt=${stops.size}, trkpt=${finalTrack.size}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 폴리라인 응답 파싱 (여러 포맷 지원)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * /trips/{id}/polyline 응답을 GpxPoint 리스트로 변환한다.
     *
     * 지원 포맷:
     *   A. [[lat, lon], [lat, lon], ...]          — 숫자 배열의 배열
     *   B. [{"lat": x, "lon": y}, ...]            — 객체 배열
     *   C. [{"lat": x, "lng": y}, ...]            — lng 키 변형
     *   D. {"points": [...]}                       — points 래핑
     */
    fun parsePolylineArray(raw: JSONArray): List<GpxPoint> {
        val pts = mutableListOf<GpxPoint>()
        if (raw.length() == 0) return pts
        var t = System.currentTimeMillis()
        for (i in 0 until raw.length()) {
            val item = raw.opt(i) ?: continue
            val lat: Double; val lon: Double
            when (item) {
                is JSONArray -> {
                    // 포맷 A: [lat, lon]
                    lat = item.optDouble(0, Double.NaN)
                    lon = item.optDouble(1, Double.NaN)
                }
                is JSONObject -> {
                    // 포맷 B/C: {lat, lon} or {lat, lng}
                    lat = item.optDouble("lat", Double.NaN)
                    lon = item.optDouble("lon", item.optDouble("lng", Double.NaN))
                }
                else -> continue
            }
            if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) continue
            pts.add(GpxPoint(lat, lon, timestampMs = t))
            t += 1_500L  // trkpt 간격 1.5초 (2초/스텝 × 인터폴레이션 없이 사용)
        }
        return pts
    }

    /**
     * JSONObject 래핑 형식 지원:
     *   {"points": [[lat,lon],...]} 또는 {"coordinates": [...]}
     */
    fun parsePolylineObject(obj: JSONObject): List<GpxPoint> {
        val arr = obj.optJSONArray("points")
            ?: obj.optJSONArray("coordinates")
            ?: return emptyList()
        return parsePolylineArray(arr)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 파일 목록 / 파싱 (시나리오 재생용)
    // ─────────────────────────────────────────────────────────────────────

    fun listSavedFiles(): List<File> =
        gpxDir.listFiles { f -> f.extension.equals("gpx", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /**
     * GPX 파일에서 트랙 포인트와 경유지를 파싱한다.
     *
     * 재생 경로  → <trkpt> (실도로 좌표, 있으면 우선)
     * 없으면     → <wpt>  (경유지 좌표로 대체)
     * 경유지 stops → <wpt> (DemoScenarioPlayer.stops 구성용)
     */
    fun parseGpxTrack(file: File): List<GpxPoint> {
        if (!file.exists()) return emptyList()
        return try {
            val content = file.readText()
            val useTrack = content.contains("<trkpt")
            val regex = if (useTrack)
                Regex("""<trkpt\s+lat="([^"]+)"\s+lon="([^"]+)"[^>]*>(.*?)</trkpt>""", RegexOption.DOT_MATCHES_ALL)
            else
                Regex("""<wpt\s+lat="([^"]+)"\s+lon="([^"]+)"[^>]*>(.*?)</wpt>""", RegexOption.DOT_MATCHES_ALL)

            val timeRe = Regex("""<time>(.*?)</time>""")
            var baseT   = System.currentTimeMillis()

            regex.findAll(content).mapIndexed { i, m ->
                val lat = m.groupValues[1].toDoubleOrNull() ?: return@mapIndexed null
                val lon = m.groupValues[2].toDoubleOrNull() ?: return@mapIndexed null
                val inner = m.groupValues[3]
                val ts = timeRe.find(inner)?.groupValues?.getOrNull(1)?.let {
                    try { isoFmt.parse(it)?.time } catch (_: Exception) { null }
                } ?: (baseT + i * 1_500L).also { baseT = it }
                GpxPoint(lat, lon, timestampMs = ts)
            }.filterNotNull().toList()
        } catch (e: Exception) {
            Log.e("GpxRecorder", "GPX 파싱 실패: ${e.message}"); emptyList()
        }
    }

    /** GPX 에서 경유지(<wpt>) 만 파싱해 DemoScenarioPlayer.ScenarioStop 목록을 만든다 */
    fun parseGpxStops(file: File): List<DemoScenarioPlayer.ScenarioStop> {
        if (!file.exists()) return emptyList()
        return try {
            val content = file.readText()
            val regex   = Regex("""<wpt\s+lat="([^"]+)"\s+lon="([^"]+)"[^>]*>(.*?)</wpt>""", RegexOption.DOT_MATCHES_ALL)
            val nameRe  = Regex("""<name>(.*?)</name>""")
            val typeRe  = Regex("""<type>(.*?)</type>""")
            regex.findAll(content).mapNotNull { m ->
                val lat  = m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
                val lon  = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
                val name = nameRe.find(m.groupValues[3])?.groupValues?.getOrNull(1)?.unescapeXml() ?: ""
                val type = typeRe.find(m.groupValues[3])?.groupValues?.getOrNull(1) ?: "unloading"
                if (type == "origin") return@mapNotNull null  // 출발지는 stop 으로 불필요
                DemoScenarioPlayer.ScenarioStop(name, lat, lon, type)
            }.toList()
        } catch (e: Exception) {
            Log.e("GpxRecorder", "wpt 파싱 실패: ${e.message}"); emptyList()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Private
    // ─────────────────────────────────────────────────────────────────────

    /**
     * GPX XML 파일을 작성한다.
     *
     * @param stops  경유지 → <wpt> 태그
     * @param track  실도로 좌표 → <trkpt> 태그
     */
    private fun writeGpx(namePrefix: String, stops: List<GpxPoint>, track: List<GpxPoint>): File? {
        return try {
            val file = File(gpxDir, "${namePrefix}_${fileFmt.format(Date())}.gpx")
            val sb = StringBuilder()
            sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            sb.appendLine("""<gpx version="1.1" creator="RouteOn" xmlns="http://www.topografix.com/GPX/1/1">""")
            sb.appendLine("""  <metadata><name>RouteOn ${esc(namePrefix)}</name><time>${isoFmt.format(Date())}</time></metadata>""")

            // ── 경유지 wpt ────────────────────────────────────────────────
            for (pt in stops) {
                sb.appendLine("""  <wpt lat="${pt.lat}" lon="${pt.lon}">""")
                if (pt.name.isNotEmpty()) sb.appendLine("""    <name>${esc(pt.name)}</name>""")
                if (pt.type.isNotEmpty()) sb.appendLine("""    <type>${pt.type}</type>""")
                sb.appendLine("""    <time>${isoFmt.format(Date(pt.timestampMs))}</time>""")
                sb.appendLine("""  </wpt>""")
            }

            // ── 실도로 트랙 trkpt ─────────────────────────────────────────
            sb.appendLine("""  <trk><name>${esc(namePrefix)}</name><trkseg>""")
            for (pt in track) {
                sb.appendLine("""    <trkpt lat="${pt.lat}" lon="${pt.lon}"><time>${isoFmt.format(Date(pt.timestampMs))}</time></trkpt>""")
            }
            sb.appendLine("""  </trkseg></trk>""")
            sb.appendLine("""</gpx>""")

            file.writeText(sb.toString())
            file
        } catch (e: Exception) {
            Log.e("GpxRecorder", "파일 쓰기 실패: ${e.message}"); null
        }
    }

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")

    private fun String.unescapeXml() = this
        .replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"")
}
