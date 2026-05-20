package com.example.routeon

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * RouteOn 데모 시뮬레이터
 *
 * [개발자 모드 전용]
 *
 * 세 가지 내장 시나리오와 저장된 GPX 파일을 재생해,
 * Android LocationManager mock provider 를 통해 가상 GPS 를 주입한다.
 *
 * KNSDK 는 시스템 GPS 를 그대로 수신하므로 NaviView 지도 위 위치 마커도
 * 시뮬레이션 좌표를 따라 이동한다.
 *
 * 필수 조건:
 *  - 기기 개발자 옵션 → "모의 위치 허용 앱" 에 RouteOn 설정
 *  - AndroidManifest 에 ACCESS_MOCK_LOCATION 선언 (이미 있음)
 */
class DemoScenarioPlayer(private val context: Context) {

    // ─── 데이터 클래스 ─────────────────────────────────────────────────────

    /** 시나리오 내 이벤트 포인트 (상차지 / 하차지 / 휴게소 등) */
    data class ScenarioStop(
        val name: String,
        val lat: Double,
        val lon: Double,
        /** "loading" | "unloading" | "rest_stop" | "destination" */
        val type: String
    )

    data class DemoScenario(
        val id: String,
        val name: String,
        val description: String,
        /** checkProximityToStops 에 주입할 경유지 목록 */
        val stops: List<ScenarioStop>,
        /** 시뮬레이션 경로 포인트 */
        val trackPoints: List<GpxRecorder.GpxPoint>,
        val isFromFile: Boolean = false,
        val sourceFile: File? = null
    )

    // ─── 재생 상태 ────────────────────────────────────────────────────────

    private val handler = Handler(Looper.getMainLooper())
    var isPlaying = false
        private set
    private var currentIndex = 0

    /** 재생 속도 배율. 1 = 실속도(2초/스텝), 5 = 5배속(400ms/스텝) */
    var speedMultiplier: Int = 3

    /** 두 GpxPoint 사이 인터폴레이션 스텝 수 (값이 클수록 부드러운 이동) */
    private val INTERP = 6

    private val stepMs: Long get() = (2_000L / speedMultiplier / INTERP).coerceAtLeast(80L)

    // ─── 재생 제어 ────────────────────────────────────────────────────────

    /**
     * @param onLocation lat, lon, speedKmh 콜백 — MainActivity 가 서버 전송 및 근접 체크에 사용
     * @param onFinished 재생 완료 콜백
     */
    fun play(
        scenario: DemoScenario,
        onLocation: (lat: Double, lon: Double, speedKmh: Float) -> Unit,
        onFinished: () -> Unit
    ) {
        stop()
        val pts = scenario.trackPoints
        if (pts.size < 2) { onFinished(); return }
        isPlaying = true; currentIndex = 0
        Log.i("DemoPlayer", "▶ 재생 시작: ${scenario.name} (${pts.size}pt, ${speedMultiplier}x)")
        scheduleStep(pts, onLocation, onFinished)
    }

    fun stop() {
        isPlaying = false
        handler.removeCallbacksAndMessages(null)
        currentIndex = 0
        removeMockProvider()
        Log.i("DemoPlayer", "⏹ 재생 중지")
    }

    // ─── 내부 스텝 스케줄러 ───────────────────────────────────────────────

    private fun scheduleStep(
        pts: List<GpxRecorder.GpxPoint>,
        onLocation: (Double, Double, Float) -> Unit,
        onFinished: () -> Unit
    ) {
        if (!isPlaying) return

        val segIdx  = currentIndex / INTERP
        val stepInSeg = currentIndex % INTERP
        if (segIdx >= pts.size - 1) {
            // 마지막 포인트 발화 후 종료
            val last = pts.last()
            onLocation(last.lat, last.lon, 0f)
            injectMock(last.lat, last.lon, 0f)
            finishPlayback(onFinished); return
        }

        val from = pts[segIdx]; val to = pts[segIdx + 1]
        val frac = stepInSeg.toFloat() / INTERP
        val lat  = from.lat + (to.lat - from.lat) * frac
        val lon  = from.lon + (to.lon - from.lon) * frac

        // 구간 속도 추정
        val dist = FloatArray(1)
        Location.distanceBetween(from.lat, from.lon, to.lat, to.lon, dist)
        val segDurSec = stepMs * INTERP / 1000f
        val speedKmh  = (dist[0] / segDurSec * 3.6f).coerceIn(0f, 130f)

        onLocation(lat, lon, speedKmh)
        injectMock(lat, lon, speedKmh / 3.6f)

        currentIndex++
        handler.postDelayed({ scheduleStep(pts, onLocation, onFinished) }, stepMs)
    }

    private fun finishPlayback(onFinished: () -> Unit) {
        isPlaying = false
        handler.removeCallbacksAndMessages(null)
        removeMockProvider()
        Log.i("DemoPlayer", "✅ 재생 완료")
        onFinished()
    }

    // ─── Mock GPS 주입 ────────────────────────────────────────────────────

    private var mockAdded = false

    private fun injectMock(lat: Double, lon: Double, speedMs: Float) {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val prov = LocationManager.GPS_PROVIDER
            if (!mockAdded) {
                try {
                    lm.addTestProvider(
                        prov, false, false, false, false,
                        true, true, true,
                        android.location.Criteria.POWER_LOW,
                        android.location.Criteria.ACCURACY_FINE
                    )
                    lm.setTestProviderEnabled(prov, true)
                    mockAdded = true
                } catch (e: SecurityException) {
                    Log.w("DemoPlayer", "Mock provider 추가 실패 (개발자 옵션 확인): ${e.message}")
                    return
                } catch (e: Exception) {
                    Log.w("DemoPlayer", "Mock provider: ${e.message}"); return
                }
            }
            val loc = Location(prov).apply {
                latitude = lat; longitude = lon; altitude = 10.0
                speed = speedMs; accuracy = 3f
                time = System.currentTimeMillis()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            lm.setTestProviderLocation(prov, loc)
        } catch (e: Exception) {
            Log.w("DemoPlayer", "injectMock 실패: ${e.message}")
        }
    }

    private fun removeMockProvider() {
        if (!mockAdded) return
        mockAdded = false
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
            lm.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) { }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 내장 시나리오 & 헬퍼
    // ─────────────────────────────────────────────────────────────────────

    companion object {

        /** 3개 내장 시나리오 목록을 반환 */
        fun builtinScenarios(): List<DemoScenario> = listOf(
            scenario1_seoulUrban(),
            scenario2_highwayRestStop(),
            scenario3_incheonPort()
        )

        // ── 시나리오 1: 수도권 도심 배송 ───────────────────────────────
        private fun scenario1_seoulUrban() = DemoScenario(
            id = "builtin_1",
            name = "1️⃣  수도권 도심 배송",
            description = "강남 상차 → 잠실 하차 → 분당 하차\n약 22 km / 도심 코스",
            stops = listOf(
                ScenarioStop("강남 물류센터", 37.5088, 127.0630, "loading"),
                ScenarioStop("잠실 하차지",   37.5148, 127.1003, "unloading"),
                ScenarioStop("분당 하차지",   37.3844, 127.1229, "destination")
            ),
            trackPoints = interpolate(listOf(
                37.5088 to 127.0630,  // 강남역
                37.5096 to 127.0720,  // 봉은사로
                37.5112 to 127.0812,  // 삼성동
                37.5133 to 127.0891,  // 코엑스
                37.5148 to 127.1003,  // 잠실역  ← 하차지 1
                37.5120 to 127.1045,
                37.5058 to 127.1098,
                37.4950 to 127.1132,
                37.4780 to 127.1180,
                37.4620 to 127.1205,
                37.4450 to 127.1218,
                37.4200 to 127.1225,
                37.3844 to 127.1229   // 분당역   ← 하차지 2 / 도착
            ))
        )

        // ── 시나리오 2: 경부고속 + 죽전휴게소 ─────────────────────────
        private fun scenario2_highwayRestStop() = DemoScenario(
            id = "builtin_2",
            name = "2️⃣  고속도로 + 휴게소",
            description = "서울 출발 → 죽전휴게소 → 수원 도착\n약 40 km / 경부고속 코스",
            stops = listOf(
                ScenarioStop("서울 서초 출발지", 37.4640, 127.0390, "loading"),
                ScenarioStop("죽전휴게소",       37.2803, 127.1148, "rest_stop"),
                ScenarioStop("수원 하차지",      37.2631, 127.0218, "destination")
            ),
            trackPoints = interpolate(listOf(
                37.4640 to 127.0390,  // 서초IC
                37.4380 to 127.0420,  // 양재IC
                37.4100 to 127.0630,  // 청계산 IC
                37.3890 to 127.0860,  // 판교
                37.3630 to 127.1010,  // 분당수서고속
                37.3380 to 127.1100,  // 용인 북부
                37.3130 to 127.1140,  // 죽전IC
                37.2803 to 127.1148,  // 죽전휴게소  ← rest_stop
                37.2620 to 127.1050,  // 기흥IC
                37.2550 to 127.0750,  // 동수원IC
                37.2631 to 127.0218   // 수원역     ← 도착
            ), stepsPerSegment = 6)
        )

        // ── 시나리오 3: 인천 항만 배송 ─────────────────────────────────
        private fun scenario3_incheonPort() = DemoScenario(
            id = "builtin_3",
            name = "3️⃣  인천 항만 배송",
            description = "인천 남동공단 상차 → 인천 연안부두 하차\n약 25 km / 항만 코스",
            stops = listOf(
                ScenarioStop("인천 남동공단 (상차)", 37.4119, 126.7315, "loading"),
                ScenarioStop("인천항 연안부두",       37.4784, 126.5827, "destination")
            ),
            trackPoints = interpolate(listOf(
                37.4119 to 126.7315,  // 남동공단
                37.4265 to 126.7199,  // 남동구 서부
                37.4390 to 126.7050,  // 인천 구도심
                37.4520 to 126.6880,  // 주안
                37.4620 to 126.6680,  // 도화동
                37.4710 to 126.6400,  // 신포동
                37.4784 to 126.5827   // 연안부두   ← 도착
            ), stepsPerSegment = 7)
        )

        // ── 공통: 좌표 리스트 → 인터폴레이션된 GpxPoint 리스트 ─────────

        /**
         * 좌표 목록을 [stepsPerSegment] 배로 세분화해 부드러운 이동 경로를 만든다.
         * timestampMs 는 3 초 간격으로 설정된다.
         */
        fun interpolate(
            coords: List<Pair<Double, Double>>,
            stepsPerSegment: Int = 5
        ): List<GpxRecorder.GpxPoint> {
            val pts = mutableListOf<GpxRecorder.GpxPoint>()
            var t = System.currentTimeMillis()
            for (i in 0 until coords.size - 1) {
                val (lat1, lon1) = coords[i]; val (lat2, lon2) = coords[i + 1]
                for (s in 0 until stepsPerSegment) {
                    val frac = s.toFloat() / stepsPerSegment
                    pts.add(GpxRecorder.GpxPoint(
                        lat = lat1 + (lat2 - lat1) * frac,
                        lon = lon1 + (lon2 - lon1) * frac,
                        timestampMs = t
                    ))
                    t += 3_000L
                }
            }
            val last = coords.last()
            pts.add(GpxRecorder.GpxPoint(last.first, last.second, timestampMs = t))
            return pts
        }
    }
}
