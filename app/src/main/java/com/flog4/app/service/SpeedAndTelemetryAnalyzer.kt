package com.flog4.app.service

import android.content.Context
import com.flog4.app.data.AirportDatabase
import com.flog4.app.data.FlightPhase
import com.flog4.app.data.SensorDataPoint
import com.flog4.app.util.TimeUtils
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class AnalyzedFlightTelemetry(
    val takeoffTimeUtc: Long,
    val takeoffTimeDisplay: String,
    val landingTimeUtc: Long,
    val landingTimeDisplay: String,
    val totalFlightTimeMs: Long,
    val totalFlightTimeDisplay: String,
    val totalDistanceKm: Double,
    val v1Knots: Double,
    val vrKnots: Double,
    val v2Knots: Double,
    val vappKnots: Double,
    val departureRunway: String,
    val arrivalRunway: String,
    val departureTerminal: String,
    val arrivalTerminal: String,
    val maxAltitudeM: Double,
    val maxSpeedKnots: Double,
    val avgSpeedKnots: Double,
    val minCabinPressureHpa: Float,
    val maxCabinPressureHpa: Float,
    val maxPitchDeg: Float,
    val maxRollDeg: Float,
    val touchdownG: Float,
    val maxGForce: Float,
    val turbulenceIndex: Double
)

/**
 * Analyzes multi-sensor flight telemetry (Level Pitch/Roll, 3-Axis Accelerometer G-Force,
 * Barometric Cabin Pressure, and GPS Ground Speed / Altitude / Coordinates) to derive
 * V1, V2, Vapp, Takeoff/Landing Times, Takeoff/Landing Runways, Distance, and Extremes.
 */
class SpeedAndTelemetryAnalyzer {

    companion object {
        const val MS_TO_KNOTS = 1.94384
    }

    fun analyzeCompleteFlight(
        context: Context?,
        data: List<SensorDataPoint>,
        originIata: String = "",
        destinationIata: String = "",
        fallbackStartMs: Long = System.currentTimeMillis()
    ): AnalyzedFlightTelemetry {
        if (data.isEmpty()) {
            val fallbackDist = if (context != null && originIata.isNotBlank() && destinationIata.isNotBlank()) {
                val orig = AirportDatabase.getByCode(context, originIata)
                val dest = AirportDatabase.getByCode(context, destinationIata)
                if (orig != null && dest != null) {
                    AirportDatabase.distanceKm(orig.lat, orig.lon, dest.lat, dest.lon)
                } else 0.0
            } else 0.0

            return AnalyzedFlightTelemetry(
                takeoffTimeUtc = fallbackStartMs,
                takeoffTimeDisplay = TimeUtils.formatLocalTime(fallbackStartMs),
                landingTimeUtc = fallbackStartMs,
                landingTimeDisplay = TimeUtils.formatLocalTime(fallbackStartMs),
                totalFlightTimeMs = 0L,
                totalFlightTimeDisplay = "0h 00m",
                totalDistanceKm = fallbackDist,
                v1Knots = 0.0,
                vrKnots = 0.0,
                v2Knots = 0.0,
                vappKnots = 0.0,
                departureRunway = "",
                arrivalRunway = "",
                departureTerminal = "",
                arrivalTerminal = "",
                maxAltitudeM = 0.0,
                maxSpeedKnots = 0.0,
                avgSpeedKnots = 0.0,
                minCabinPressureHpa = 1013.25f,
                maxCabinPressureHpa = 1013.25f,
                maxPitchDeg = 0f,
                maxRollDeg = 0f,
                touchdownG = 1.0f,
                maxGForce = 1.0f,
                turbulenceIndex = 0.0
            )
        }

        // 1. Identify Takeoff Time & Landing Time from phase transitions and speed/altitude thresholds
        val firstTakeoffPoint = data.firstOrNull {
            it.phase == FlightPhase.TAKEOFF || it.phase == FlightPhase.CLIMB || it.gpsSpeedMps * MS_TO_KNOTS > 80.0
        } ?: data.first()

        val lastAirbornePoint = data.lastOrNull {
            it.phase == FlightPhase.LANDING || it.phase == FlightPhase.DESCENT ||
                it.phase == FlightPhase.CRUISE || it.gpsSpeedMps * MS_TO_KNOTS > 80.0
        } ?: data.last()

        val takeoffTimeUtc = firstTakeoffPoint.timestamp
        val landingTimeUtc = max(takeoffTimeUtc, lastAirbornePoint.timestamp)
        val rawFlightDurationMs = (landingTimeUtc - takeoffTimeUtc).takeIf { it > 60_000L }
            ?: (data.last().timestamp - data.first().timestamp).coerceAtLeast(0L)

        // 2. V1, Vr, V2, Vapp analysis combining GPS speed, Level Sensor (pitchDeg), and Accelerometer (gForce)
        val speeds = analyzeFlightSpeeds(data)

        // 3. Total flight distance (integrated GPS trajectory distance, or airport great-circle fallback)
        var totalDistKm = AirportDatabase.calculateTrackDistanceKm(data)
        if (totalDistKm < 1.0 && context != null && originIata.isNotBlank() && destinationIata.isNotBlank()) {
            val orig = AirportDatabase.getByCode(context, originIata)
            val dest = AirportDatabase.getByCode(context, destinationIata)
            if (orig != null && dest != null) {
                totalDistKm = AirportDatabase.distanceKm(orig.lat, orig.lon, dest.lat, dest.lon)
            }
        }

        // 4. Offline GPS Runway & Terminal matching
        val depRunway = if (context != null) detectRunway(context, data, isTakeoff = true) else ""
        val arrRunway = if (context != null) detectRunway(context, data, isTakeoff = false) else ""
        val depTerminal = if (context != null) detectTerminal(context, data, isDepart = true) else ""
        val arrTerminal = if (context != null) detectTerminal(context, data, isDepart = false) else ""

        // 5. Altitude, Speed, Cabin Pressure, Level Attitude, and G-Force extremes
        val maxAltM = data.maxOfOrNull { it.gpsAltitudeM } ?: 0.0
        val maxSpdKts = (data.maxOfOrNull { it.gpsSpeedMps }?.toDouble() ?: 0.0) * MS_TO_KNOTS
        val airbornePoints = data.filter {
            it.phase in setOf(FlightPhase.CLIMB, FlightPhase.CRUISE, FlightPhase.DESCENT) ||
                it.gpsSpeedMps * MS_TO_KNOTS > 120.0
        }
        val avgSpdKts = if (airbornePoints.isNotEmpty()) {
            airbornePoints.map { it.gpsSpeedMps * MS_TO_KNOTS }.average()
        } else {
            data.map { it.gpsSpeedMps * MS_TO_KNOTS }.average()
        }

        val validPressures = data.map { it.pressureHpa }.filter { it > 100f }
        val minPressure = validPressures.minOrNull() ?: 780f
        val maxPressure = validPressures.maxOrNull() ?: 1013.25f

        val maxPitch = data.maxOfOrNull { abs(it.pitchDeg) } ?: 0f
        val maxRoll = data.maxOfOrNull { abs(it.rollDeg) } ?: 0f
        val maxG = data.maxOfOrNull { it.gForce } ?: 1.0f

        val landingSlice = data.filter { it.phase == FlightPhase.LANDING }.ifEmpty {
            data.takeLast((data.size / 6).coerceAtLeast(5))
        }
        val touchdownG = landingSlice.maxOfOrNull { it.gForce }?.coerceAtLeast(1.0f) ?: 1.12f
        val turbulence = calculateTurbulenceIndex(data)

        return AnalyzedFlightTelemetry(
            takeoffTimeUtc = takeoffTimeUtc,
            takeoffTimeDisplay = TimeUtils.formatLocalTime(takeoffTimeUtc),
            landingTimeUtc = landingTimeUtc,
            landingTimeDisplay = TimeUtils.formatLocalTime(landingTimeUtc),
            totalFlightTimeMs = rawFlightDurationMs,
            totalFlightTimeDisplay = TimeUtils.formatDuration(rawFlightDurationMs),
            totalDistanceKm = totalDistKm,
            v1Knots = speeds.v1,
            vrKnots = speeds.vr,
            v2Knots = speeds.v2,
            vappKnots = speeds.vLanding,
            departureRunway = depRunway,
            arrivalRunway = arrRunway,
            departureTerminal = depTerminal,
            arrivalTerminal = arrTerminal,
            maxAltitudeM = maxAltM,
            maxSpeedKnots = maxSpdKts,
            avgSpeedKnots = avgSpdKts,
            minCabinPressureHpa = minPressure,
            maxCabinPressureHpa = maxPressure,
            maxPitchDeg = maxPitch,
            maxRollDeg = maxRoll,
            touchdownG = touchdownG,
            maxGForce = maxG,
            turbulenceIndex = turbulence
        )
    }

    fun analyzeFlightSpeeds(data: List<SensorDataPoint>): FlightSpeeds {
        val takeoffData = data.filter { it.phase == FlightPhase.TAKEOFF }
        val climbData = data.filter { it.phase == FlightPhase.CLIMB }
        val landingData = data.filter { it.phase == FlightPhase.LANDING }
        val descentData = data.filter { it.phase == FlightPhase.DESCENT }

        val v1 = findV1(takeoffData)
        val vr = findVr(takeoffData, v1)
        val v2 = findV2(takeoffData, climbData, vr)
        val vLanding = findLandingSpeed(landingData, descentData)

        return FlightSpeeds(
            v1 = v1,
            vr = vr,
            v2 = v2,
            vLanding = vLanding
        )
    }

    /**
     * V1 (Decision Speed): Detected during the high-speed takeoff roll right before nose-up
     * rotation begins on the level sensor, or at peak acceleration in the upper roll regime.
     */
    private fun findV1(takeoffData: List<SensorDataPoint>): Double {
        if (takeoffData.size < 3) return 0.0
        val maxRollSpeed = takeoffData.maxOf { it.gpsSpeedMps }.coerceAtLeast(1f)

        // 1. Check transition point right before nose-up rotation starts (pitch rises past 2.5 deg)
        val rotationIdx = takeoffData.indexOfFirst { it.pitchDeg >= 2.5f && it.gpsSpeedMps >= maxRollSpeed * 0.65f }
        if (rotationIdx > 0) {
            return takeoffData[rotationIdx - 1].gpsSpeedMps.toDouble() * MS_TO_KNOTS
        }

        // 2. Fallback: Peak acceleration in the upper high-speed roll band (>= 75% of max takeoff roll speed)
        var maxAccelRate = -1.0
        var v1SpeedMps = 0.0
        val minV1SpeedMps = maxRollSpeed * 0.75f
        for (i in 1 until takeoffData.size - 1) {
            if (takeoffData[i].gpsSpeedMps < minV1SpeedMps) continue
            val dt = (takeoffData[i + 1].timestamp - takeoffData[i - 1].timestamp) / 1000.0
            if (dt <= 0) continue
            val dv = (takeoffData[i + 1].gpsSpeedMps - takeoffData[i - 1].gpsSpeedMps).toDouble()
            val accelRate = dv / dt
            if (accelRate >= maxAccelRate) {
                maxAccelRate = accelRate
                v1SpeedMps = takeoffData[i].gpsSpeedMps.toDouble()
            }
        }
        if (v1SpeedMps <= 0.0) {
            v1SpeedMps = (maxRollSpeed * 0.85f).toDouble()
        }
        return v1SpeedMps * MS_TO_KNOTS
    }

    /**
     * Vr (Rotation Speed): Detected by the phone's Level Sensor (pitchDeg nose-up rotation >= 6°)
     * or pitch/Y-axis acceleration change during takeoff roll above 60 kts.
     */
    private fun findVr(takeoffData: List<SensorDataPoint>, v1Knots: Double): Double {
        if (takeoffData.size < 3) return if (v1Knots > 0) v1Knots * 1.04 else 0.0

        // 1. Primary: Level sensor pitch-up rotation detection (pitchDeg >= 6.0° while speed > 55 m/s)
        val pitchRotationPoint = takeoffData.firstOrNull {
            it.pitchDeg >= 6.0f && it.gpsSpeedMps > 55f
        }
        if (pitchRotationPoint != null) {
            return (pitchRotationPoint.gpsSpeedMps * MS_TO_KNOTS).coerceAtLeast(v1Knots)
        }

        // 2. Fallback: Peak pitch/longitudinal acceleration transition
        var maxPitchChange = 0f
        var vrSpeedMps = 0.0
        for (i in 2 until takeoffData.size) {
            val pitchDelta = abs(takeoffData[i].pitchDeg - takeoffData[i - 2].pitchDeg) +
                abs(takeoffData[i].accelY - takeoffData[i - 2].accelY) * 2f
            if (pitchDelta > maxPitchChange && takeoffData[i].gpsSpeedMps > 30f) {
                maxPitchChange = pitchDelta
                vrSpeedMps = takeoffData[i].gpsSpeedMps.toDouble()
            }
        }
        val computed = vrSpeedMps * MS_TO_KNOTS
        return if (computed > 0) max(computed, v1Knots) else v1Knots * 1.05
    }

    /**
     * V2 (Takeoff Safety / Liftoff Climb Speed): Speed when the aircraft rotates past +8° pitch
     * and achieves positive climb altitude (+10m ~ 15m (~35 ft) above runway elevation).
     */
    private fun findV2(
        takeoffData: List<SensorDataPoint>,
        climbData: List<SensorDataPoint>,
        vrKnots: Double
    ): Double {
        val combined = takeoffData + climbData.take(12)
        if (combined.isEmpty()) return if (vrKnots > 0) vrKnots * 1.06 else 0.0
        val baseAlt = combined.first().gpsAltitudeM
        val liftoffPoint = combined.firstOrNull {
            (it.gpsAltitudeM - baseAlt > 10.0) || (it.pitchDeg >= 8.5f && it.gpsSpeedMps > 60f)
        } ?: combined.last()
        val rawV2 = liftoffPoint.gpsSpeedMps.toDouble() * MS_TO_KNOTS
        return max(rawV2, vrKnots * 1.03)
    }

    /**
     * Vapp (Approach / Touchdown Speed): Speed during final approach and flare at the moment
     * the accelerometer detects the touchdown G-force spike on the runway.
     */
    private fun findLandingSpeed(
        landingData: List<SensorDataPoint>,
        descentData: List<SensorDataPoint>
    ): Double {
        val candidates = if (landingData.isNotEmpty()) landingData else descentData.takeLast(15)
        if (candidates.isEmpty()) return 0.0

        var maxAccelSpike = 0f
        var touchdownSpeedMps = candidates.first().gpsSpeedMps
        for (point in candidates) {
            val accelMag = sqrt(
                point.accelX * point.accelX +
                    point.accelY * point.accelY +
                    point.accelZ * point.accelZ
            )
            if (accelMag > maxAccelSpike && point.gpsSpeedMps > 25f) {
                maxAccelSpike = accelMag
                touchdownSpeedMps = point.gpsSpeedMps
            }
        }
        return touchdownSpeedMps.toDouble() * MS_TO_KNOTS
    }

    fun calculateTurbulenceIndex(data: List<SensorDataPoint>): Double {
        val cruiseData = data.filter { it.phase == FlightPhase.CRUISE }.ifEmpty { data }
        if (cruiseData.size < 5) return 0.0
        val magnitudes = cruiseData.map {
            sqrt(it.accelX * it.accelX + it.accelY * it.accelY + it.accelZ * it.accelZ).toDouble() / 9.80665
        }
        val mean = magnitudes.average()
        val variance = magnitudes.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance)
    }

    private fun detectRunway(
        context: Context,
        data: List<SensorDataPoint>,
        isTakeoff: Boolean
    ): String {
        val targetPhase = if (isTakeoff) FlightPhase.TAKEOFF else FlightPhase.LANDING
        val phasePoints = data.filter {
            it.phase == targetPhase && (it.latitude != 0.0 || it.longitude != 0.0)
        }.ifEmpty {
            val valid = data.filter { it.latitude != 0.0 || it.longitude != 0.0 }
            if (valid.isEmpty()) return ""
            if (isTakeoff) valid.take(15) else valid.takeLast(15)
        }

        val sample = phasePoints[phasePoints.size / 2]
        val heading = when {
            sample.bearingDeg > 0f -> sample.bearingDeg.toDouble()
            phasePoints.size >= 2 -> AirportDatabase.bearingDeg(
                phasePoints.first().latitude,
                phasePoints.first().longitude,
                phasePoints.last().latitude,
                phasePoints.last().longitude
            )
            else -> sample.yawDeg.toDouble()
        }

        val match = AirportDatabase.identifyRunway(
            context = context,
            lat = sample.latitude,
            lon = sample.longitude,
            headingDeg = heading
        )
        return match?.formattedRunway ?: ""
    }

    private fun detectTerminal(
        context: Context,
        data: List<SensorDataPoint>,
        isDepart: Boolean
    ): String {
        val valid = data.filter { it.latitude != 0.0 || it.longitude != 0.0 }
        if (valid.isEmpty()) return ""
        val point = if (isDepart) valid.first() else valid.last()
        return AirportDatabase.identifyTerminal(context, point.latitude, point.longitude) ?: ""
    }
}

data class FlightSpeeds(
    val v1: Double = 0.0,
    val vr: Double = 0.0,
    val v2: Double = 0.0,
    val vLanding: Double = 0.0
)
