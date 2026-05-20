package com.example.routeon

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * RouteOn GPX 기록기
 *
 * 두 가지 용도:
 * 1. [saveFromRoute]  — /optimize 응답의 route 배열을 GPX 파일로 저장 (경유지 wpt + 트랙)
 * 2. [startRecording] / [addTrackPoint] / [stopAndSave]
 *                     — 실제 주행 중 GPS 좌표를 실시간으로 기록해 저장
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

    // ─── 실시간 트랙 기록 상태 ────────────────────────────────────────────
    private val liveTrackPoints = mutableListOf<GpxPoint>()
    private var liveTrackTripId: String? = null
    val isRecording: Boolean get() = liveTrackTripId != null

    // ─────────────────────────────────────────────────────────────────────
    // 1. /optimize 응답 → GPX 저장
    // ─────────────────────────────────────────────────────────────────────

    /**
     * /optimize 응답의 route 배열에서 GPX 파일을 생성한다.
     *
     * wpt   — 각 경유지 (origin / loading / unloading / rest_stop / destination)
     * trkseg — wpt를 순서대로 이은 경로선 (재생 시 인터폴레이션 기준)
     *
     * @return 저장된 파일, 실패 시 null
     */
    fun saveFromRoute(tripId: String, routeArray: JSONArray): File? {
        if (routeArray.length() == 0) return null
        val points = mutableListOf<GpxPoint>()
        var t = System.currentTimeMillis()
        for (i in 0 until routeArray.length()) {
            val pt = routeArray.optJSONObject(i) ?: continue
            val lat  = pt.optDouble("lat", 0.0)
            val lon  = pt.optDouble("lon", pt.optDouble("lng", 0.0))
            val name = pt.optString("name", "")
            val type = pt.optString("type", pt.optString("node_type", "waypoint"))
            if (lat == 0.0 && lon == 0.0) continue
            points.add(GpxPoint(lat, lon, name, type, t))
            t += 3_000L // 경유지 간격 3 초
        }
        if (points.size < 2) return null
        val file = writeGpx("route_${tripId.take(8)}", points)
        Log.i("GpxRecorder", "✅ GPX 저장: ${file?.name} (${points.size}포인트)")
        return file
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2. 실시간 주행 트랙 기록
    // ─────────────────────────────────────────────────────────────────────

    fun startRecording(tripId: String) {
        liveTrackPoints.clear()
        liveTrackTripId = tripId
        Log.i("GpxRecorder", "🔴 트랙 기록 시작: trip=$tripId")
    }

    fun addTrackPoint(lat: Double, lon: Double) {
        if (liveTrackTripId == null || (lat == 0.0 && lon == 0.0)) return
        // 마지막 포인트와 10 m 이상 떨어진 경우에만 추가 (중복 방지)
        val last = liveTrackPoints.lastOrNull()
        if (last != null) {
            val dist = FloatArray(1)
            android.location.Location.distanceBetween(last.lat, last.lon, lat, lon, dist)
            if (dist[0] < 10f) return
        }
        liveTrackPoints.add(GpxPoint(lat, lon, timestampMs = System.currentTimeMillis()))
    }

    fun stopAndSave(): File? {
        val tid = liveTrackTripId ?: return null
        liveTrackTripId = null
        if (liveTrackPoints.size < 5) {
            Log.w("GpxRecorder", "포인트 부족 (${liveTrackPoints.size}개) — 저장 스킵")
            liveTrackPoints.clear(); return null
        }
        val file = writeGpx("track_${tid.take(8)}", liveTrackPoints.toList())
        Log.i("GpxRecorder", "🟢 트랙 저장: ${file?.name} (${liveTrackPoints.size}포인트)")
        liveTrackPoints.clear()
        return file
    }

    // ─────────────────────────────────────────────────────────────────────
    // 3. 파일 목록 / 파싱
    // ─────────────────────────────────────────────────────────────────────

    fun listSavedFiles(): List<File> =
        gpxDir.listFiles { f -> f.extension.equals("gpx", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /**
     * GPX 파일을 파싱해 GpxPoint 리스트를 반환한다.
     * trkpt 가 있으면 우선 사용, 없으면 wpt 사용.
     */
    fun parseGpxFile(file: File): List<GpxPoint> {
        if (!file.exists()) return emptyList()
        return try {
            val content = file.readText()
            val hasTrk  = content.contains("<trkpt")
            val regex   = if (hasTrk)
                Regex("""<trkpt\s+lat="([^"]+)"\s+lon="([^"]+)"[^>]*>(.*?)</trkpt>""", RegexOption.DOT_MATCHES_ALL)
            else
                Regex("""<wpt\s+lat="([^"]+)"\s+lon="([^"]+)"[^>]*>(.*?)</wpt>""", RegexOption.DOT_MATCHES_ALL)

            val nameRe  = Regex("""<name>(.*?)</name>""")
            val typeRe  = Regex("""<type>(.*?)</type>""")
            val timeRe  = Regex("""<time>(.*?)</time>""")

            var baseT = System.currentTimeMillis()
            regex.findAll(content).mapIndexed { i, m ->
                val lat   = m.groupValues[1].toDoubleOrNull() ?: return@mapIndexed null
                val lon   = m.groupValues[2].toDoubleOrNull() ?: return@mapIndexed null
                val inner = m.groupValues[3]
                val name  = nameRe.find(inner)?.groupValues?.getOrNull(1) ?: ""
                val type  = typeRe.find(inner)?.groupValues?.getOrNull(1) ?: ""
                val tStr  = timeRe.find(inner)?.groupValues?.getOrNull(1)
                val ts    = tStr?.let {
                    try { isoFmt.parse(it)?.time } catch (_: Exception) { null }
                } ?: (baseT + i * 3_000L).also { baseT = it }
                GpxPoint(lat, lon, name, type, ts)
            }.filterNotNull().toList()
        } catch (e: Exception) {
            Log.e("GpxRecorder", "GPX 파싱 실패: ${e.message}"); emptyList()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Private
    // ─────────────────────────────────────────────────────────────────────

    private fun writeGpx(namePrefix: String, points: List<GpxPoint>): File? {
        return try {
            val file = File(gpxDir, "${namePrefix}_${fileFmt.format(Date())}.gpx")
            val sb = StringBuilder()
            sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            sb.appendLine("""<gpx version="1.1" creator="RouteOn" xmlns="http://www.topografix.com/GPX/1/1">""")
            sb.appendLine("""  <metadata><name>RouteOn ${escXml(namePrefix)}</name><time>${isoFmt.format(Date())}</time></metadata>""")
            // 경유지 → wpt
            for (pt in points) {
                sb.appendLine("""  <wpt lat="${pt.lat}" lon="${pt.lon}">""")
                if (pt.name.isNotEmpty()) sb.appendLine("""    <name>${escXml(pt.name)}</name>""")
                if (pt.type.isNotEmpty()) sb.appendLine("""    <type>${pt.type}</type>""")
                sb.appendLine("""    <time>${isoFmt.format(Date(pt.timestampMs))}</time>""")
                sb.appendLine("""  </wpt>""")
            }
            // 트랙 세그먼트
            sb.appendLine("""  <trk><name>${escXml(namePrefix)}</name><trkseg>""")
            for (pt in points) {
                sb.appendLine("""    <trkpt lat="${pt.lat}" lon="${pt.lon}"><time>${isoFmt.format(Date(pt.timestampMs))}</time></trkpt>""")
            }
            sb.appendLine("""  </trkseg></trk>""")
            sb.appendLine("""</gpx>""")
            file.writeText(sb.toString())
            file
        } catch (e: Exception) {
            Log.e("GpxRecorder", "GPX 저장 실패: ${e.message}"); null
        }
    }

    private fun escXml(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")
}
