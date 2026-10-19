package com.flog4.app.service

import com.flog4.app.data.Airport
import com.flog4.app.data.AirportDatabase
import com.flog4.app.data.FlightPhase
import com.flog4.app.data.SensorDataPoint
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object SimulatedFlight {

    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val altitudeM: Double,
        val speedMps: Float,
        val bearingDeg: Float,
        val pitchDeg: Float,
        val rollDeg: Float,
        val yawDeg: Float,
        val accelX: Float,
        val accelY: Float,
        val accelZ: Float,
        val gForce: Float,
        val pressureHpa: Float,
        val cabinAltitudeM: Double,
        val expectedPhase: FlightPhase
    )

    private const val KT_TO_MPS = 0.514444f
    private const val G = 9.80665f

    // Default PEK (ZBAA RWY 36R) -> SHA (ZSSS RWY 18L) coordinates
    private const val PEK_T1_LAT = 40.0585
    private const val PEK_T1_LON = 116.6020
    private const val PEK_T2_LAT = 40.1010
    private const val PEK_T2_LON = 116.6020

    private const val SHA_T1_LAT = 31.2160
    private const val SHA_T1_LON = 121.3350
    private const val SHA_T2_LAT = 31.1780
    private const val SHA_T2_LON = 121.3350

    private const val TOTAL_DURATION_S = 1200.0

    fun durationMs(): Long = (TOTAL_DURATION_S * 1000).toLong()

    fun fixAt(elapsedMs: Long): Fix {
        val t = (elapsedMs / 1000.0).coerceIn(0.0, TOTAL_DURATION_S)
        return computeFixAtSecond(
            t = t,
            depStartLat = PEK_T1_LAT,
            depStartLon = PEK_T1_LON,
            depEndLat = PEK_T2_LAT,
            depEndLon = PEK_T2_LON,
            arrStartLat = SHA_T1_LAT,
            arrStartLon = SHA_T1_LON,
            arrEndLat = SHA_T2_LAT,
            arrEndLon = SHA_T2_LON
        )
    }

    /**
     * Generates a complete high-resolution multi-sensor flight trajectory (Level, Accelerometer,
     * Barometer, GPS) between any departure and arrival airport in the offline database.
     */
    fun generateCompleteFlightData(
        flightId: Long,
        originAirport: Airport? = null,
        destAirport: Airport? = null,
        endTimeMs: Long = System.currentTimeMillis()
    ): List<SensorDataPoint> {
        // Use actual runway thresholds from origin & destination airports if available
        val depRwy = originAirport?.runways?.firstOrNull()
        val depStartLat = depRwy?.t2Lat ?: originAirport?.lat ?: PEK_T1_LAT
        val depStartLon = depRwy?.t2Lon ?: originAirport?.lon ?: PEK_T1_LON
        val depEndLat = depRwy?.t1Lat ?: (depStartLat + 0.035)
        val depEndLon = depRwy?.t1Lon ?: depStartLon

        val arrRwy = destAirport?.runways?.firstOrNull()
        val arrStartLat = arrRwy?.t1Lat ?: destAirport?.lat ?: SHA_T1_LAT
        val arrStartLon = arrRwy?.t1Lon ?: destAirport?.lon ?: SHA_T1_LON
        val arrEndLat = arrRwy?.t2Lat ?: (arrStartLat - 0.032)
        val arrEndLon = arrRwy?.t2Lon ?: arrStartLon

        // Realistic total flight duration scaled to route distance (e.g., 1h 55m for PEK-SHA)
        val routeDistKm = AirportDatabase.distanceKm(depStartLat, depStartLon, arrStartLat, arrStartLon)
            .coerceAtLeast(800.0)
        val realFlightDurationMs = ((routeDistKm / 760.0) * 3600_000.0).toLong().coerceIn(3600_000L, 43200_000L)
        val startTimeMs = endTimeMs - realFlightDurationMs

        val detector = FlightPhaseDetector()
        val points = ArrayList<SensorDataPoint>(241)
        val numSteps = 240
        for (i in 0..numSteps) {
            val simSec = (i.toDouble() / numSteps) * TOTAL_DURATION_S
            val timestamp = startTimeMs + ((i.toDouble() / numSteps) * realFlightDurationMs).toLong()
            val fix = computeFixAtSecond(
                t = simSec,
                depStartLat = depStartLat,
                depStartLon = depStartLon,
                depEndLat = depEndLat,
                depEndLon = depEndLon,
                arrStartLat = arrStartLat,
                arrStartLon = arrStartLon,
                arrEndLat = arrEndLat,
                arrEndLon = arrEndLon
            )
            // Feed simulated clock so vertical speed rates match physical 5-second intervals
            val detectorClock = startTimeMs + (simSec * 1000.0).toLong()
            val detectedPhase = detector.detectPhase(
                accelX = fix.accelX,
                accelY = fix.accelY,
                accelZ = fix.accelZ,
                speedMps = fix.speedMps,
                altitudeM = fix.altitudeM,
                pressureHpa = fix.pressureHpa,
                timestampMs = detectorClock,
                pitchDeg = fix.pitchDeg
            )
            val phase = if (simSec in 90.0..150.0 && fix.altitudeM < 120.0) {
                FlightPhase.TAKEOFF
            } else if (simSec in 1050.0..1125.0) {
                FlightPhase.LANDING
            } else {
                detectedPhase
            }

            points.add(
                SensorDataPoint(
                    flightId = flightId,
                    timestamp = timestamp,
                    pitchDeg = fix.pitchDeg,
                    rollDeg = fix.rollDeg,
                    yawDeg = fix.yawDeg,
                    accelX = fix.accelX,
                    accelY = fix.accelY,
                    accelZ = fix.accelZ,
                    gForce = fix.gForce,
                    pressureHpa = fix.pressureHpa,
                    cabinAltitudeM = fix.cabinAltitudeM,
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    gpsAltitudeM = fix.altitudeM,
                    gpsSpeedMps = fix.speedMps,
                    bearingDeg = fix.bearingDeg,
                    phase = phase
                )
            )
        }
        return points
    }

    private fun computeFixAtSecond(
        t: Double,
        depStartLat: Double,
        depStartLon: Double,
        depEndLat: Double,
        depEndLon: Double,
        arrStartLat: Double,
        arrStartLon: Double,
        arrEndLat: Double,
        arrEndLon: Double
    ): Fix {
        val depHeading = AirportDatabase.bearingDeg(depStartLat, depStartLon, depEndLat, depEndLon).toFloat()
        val arrHeading = AirportDatabase.bearingDeg(arrStartLat, arrStartLon, arrEndLat, arrEndLon).toFloat()
        val cruiseHeading = AirportDatabase.bearingDeg(depEndLat, depEndLon, arrStartLat, arrStartLon).toFloat()

        val lat: Double
        val lon: Double
        val altM: Double
        val speedKt: Double
        val bearing: Float
        val pitchDeg: Float
        val rollDeg: Float
        val accelX: Float
        val accelY: Float
        val accelZ: Float
        val phase: FlightPhase

        when {
            // 1. 0..30s: Gate Pushback & Ground Hold
            t < 30.0 -> {
                lat = depStartLat
                lon = depStartLon
                altM = 35.0
                speedKt = 2.0 * (t / 30.0)
                bearing = depHeading
                pitchDeg = 0.2f
                rollDeg = 0.0f
                accelX = 0.0f
                accelY = 0.05f
                accelZ = G
                phase = FlightPhase.GROUND
            }
            // 2. 30..90s: Taxi to Runway Holding Point
            t < 90.0 -> {
                val u = (t - 30.0) / 60.0
                lat = lerp(depStartLat, depStartLat + (depEndLat - depStartLat) * 0.08, u)
                lon = lerp(depStartLon, depStartLon + (depEndLon - depStartLon) * 0.08, u)
                altM = 35.0
                speedKt = 16.0 + 4.0 * sin(u * PI)
                bearing = depHeading
                pitchDeg = 0.4f
                rollDeg = (3.5 * sin(u * 2 * PI)).toFloat()
                accelX = 0.1f * sin(u * 4 * PI).toFloat()
                accelY = 0.2f
                accelZ = G
                phase = FlightPhase.TAXI
            }
            // 3. 90..150s: Takeoff Roll (V1 ~142 kts at t=122s, Vr ~149 kts at t=128s, V2 ~162 kts at t=140s)
            t < 150.0 -> {
                val dt = t - 90.0
                val u = dt / 60.0
                lat = lerp(depStartLat, depEndLat, u)
                lon = lerp(depStartLon, depEndLon, u)
                speedKt = when {
                    dt < 32.0 -> lerp(18.0, 142.0, dt / 32.0)       // Accelerating to V1 (142 kts)
                    dt < 40.0 -> lerp(142.0, 154.0, (dt - 32.0) / 8.0) // V1 -> Vr rotation
                    else -> lerp(154.0, 168.0, (dt - 40.0) / 20.0)  // Liftoff & V2 safety speed (162 kts)
                }
                altM = if (dt < 38.0) 35.0 else 35.0 + (dt - 38.0) * 3.8
                bearing = depHeading
                // Level sensor captures nose rotation from 0.5° up to +11.8°!
                pitchDeg = when {
                    dt < 32.0 -> 0.6f
                    dt < 42.0 -> lerp(0.6, 11.8, (dt - 32.0) / 10.0).toFloat()
                    else -> 11.2f
                }
                rollDeg = 0.5f
                accelX = 0.05f
                accelY = when {
                    dt < 32.0 -> 2.65f // Strong takeoff thrust longitudinal acceleration
                    dt < 42.0 -> 3.40f // Rotation surge
                    else -> 1.85f
                }
                accelZ = if (dt >= 36.0) G + 1.85f else G + 0.25f // 1.19G liftoff vertical load
                phase = FlightPhase.TAKEOFF
            }
            // 4. 150..360s: Climb to Cruise Flight Level (10,600m / FL348)
            t < 360.0 -> {
                val u = (t - 150.0) / 210.0
                lat = lerp(depEndLat, lerp(depEndLat, arrStartLat, 0.25), u)
                lon = lerp(depEndLon, lerp(depEndLon, arrStartLon, 0.25), u)
                altM = lerp(120.0, 10600.0, smoothStep(u))
                speedKt = lerp(175.0, 458.0, u)
                bearing = lerpAngle(depHeading, cruiseHeading, u.toFloat())
                pitchDeg = lerp(9.5, 3.2, u).toFloat()
                rollDeg = if (u < 0.25) (14.0 * sin(u * 4 * PI)).toFloat() else 1.2f
                accelX = 0.08f * sin(t * 0.2).toFloat()
                accelY = 0.65f
                accelZ = G + 0.35f
                phase = FlightPhase.CLIMB
            }
            // 5. 360..840s: High-Altitude Cruise at 10,600m
            t < 840.0 -> {
                val u = (t - 360.0) / 480.0
                val p1Lat = lerp(depEndLat, arrStartLat, 0.25)
                val p1Lon = lerp(depEndLon, arrStartLon, 0.25)
                val p2Lat = lerp(depEndLat, arrStartLat, 0.78)
                val p2Lon = lerp(depEndLon, arrStartLon, 0.78)
                lat = lerp(p1Lat, p2Lat, u)
                lon = lerp(p1Lon, p2Lon, u)
                altM = 10600.0 + 35.0 * sin(u * 2 * PI)
                speedKt = 465.0 + 8.0 * sin(u * 3 * PI)
                bearing = cruiseHeading
                pitchDeg = 2.1f + (0.3 * sin(t * 0.15)).toFloat()
                rollDeg = (1.8 * sin(t * 0.1)).toFloat()
                val ripple = (0.18 * sin(t * 0.7) + 0.11 * cos(t * 1.3)).toFloat()
                accelX = ripple * 0.4f
                accelY = ripple * 0.3f
                accelZ = G + ripple
                phase = FlightPhase.CRUISE
            }
            // 6. 840..1050s: Descent & Approach Alignment
            t < 1050.0 -> {
                val u = (t - 840.0) / 210.0
                val p2Lat = lerp(depEndLat, arrStartLat, 0.78)
                val p2Lon = lerp(depEndLon, arrStartLon, 0.78)
                // Final approach intercept 6km before arrival runway threshold
                val appLat = arrStartLat - (arrEndLat - arrStartLat) * 0.8
                val appLon = arrStartLon - (arrEndLon - arrStartLon) * 0.8
                lat = lerp(p2Lat, appLat, u)
                lon = lerp(p2Lon, appLon, u)
                altM = lerp(10600.0, 220.0, smoothStep(u))
                speedKt = lerp(455.0, 152.0, u)
                bearing = lerpAngle(cruiseHeading, arrHeading, u.toFloat())
                pitchDeg = lerp(-2.8, 1.5, u).toFloat()
                rollDeg = if (u > 0.65) (16.0 * sin((u - 0.65) * 3 * PI)).toFloat() else -2.0f
                accelX = 0.1f * sin(t * 0.3).toFloat()
                accelY = -0.55f
                accelZ = G - 0.2f
                phase = FlightPhase.DESCENT
            }
            // 7. 1050..1125s: Final Approach, Flare & Touchdown on Arrival Runway (Vapp ~138 kts)
            t < 1125.0 -> {
                val dt = t - 1050.0
                val u = dt / 75.0
                val appLat = arrStartLat - (arrEndLat - arrStartLat) * 0.8
                val appLon = arrStartLon - (arrEndLon - arrStartLon) * 0.8
                lat = if (dt < 25.0) {
                    lerp(appLat, arrStartLat, dt / 25.0)
                } else {
                    lerp(arrStartLat, arrEndLat, (dt - 25.0) / 50.0)
                }
                lon = if (dt < 25.0) {
                    lerp(appLon, arrStartLon, dt / 25.0)
                } else {
                    lerp(arrStartLon, arrEndLon, (dt - 25.0) / 50.0)
                }
                altM = if (dt < 28.0) lerp(220.0, 12.0, dt / 28.0) else 12.0
                speedKt = when {
                    dt < 26.0 -> lerp(150.0, 138.0, dt / 26.0) // Stabilized Vapp 138 kts
                    else -> lerp(138.0, 32.0, (dt - 26.0) / 49.0) // Reverse thrust & autobrake rollout
                }
                bearing = arrHeading
                // Flare pitch-up (+4.2°) right before touchdown, then nose gear lowering (0.3°)
                pitchDeg = when {
                    dt < 20.0 -> 2.5f
                    dt < 28.0 -> 4.4f
                    dt < 36.0 -> lerp(4.4, 0.3, (dt - 28.0) / 8.0).toFloat()
                    else -> 0.2f
                }
                rollDeg = 0.4f
                // Touchdown vertical G spike at dt in 26..29s (~1.26 G)
                val isTouchdown = dt in 25.5..29.5
                accelX = if (isTouchdown) 0.4f else 0.05f
                accelY = if (dt >= 28.0) -2.4f else -0.4f
                accelZ = if (isTouchdown) G * 1.25f else G
                phase = FlightPhase.LANDING
            }
            // 8. 1125..1200s: Runway Vacate & Taxi to Arrival Terminal
            else -> {
                val u = (t - 1125.0) / 75.0
                lat = arrEndLat
                lon = arrEndLon
                altM = 12.0
                speedKt = if (u < 0.7) 18.0 else lerp(18.0, 0.0, (u - 0.7) / 0.3)
                bearing = arrHeading
                pitchDeg = 0.1f
                rollDeg = 0.0f
                accelX = 0.0f
                accelY = 0.0f
                accelZ = G
                phase = if (u < 0.8) FlightPhase.TAXI else FlightPhase.GROUND
            }
        }

        // Realistic commercial cabin pressurization schedule:
        // External altitude goes up to 10,600m, while cabin altitude is regulated up to ~2,120m (~783 hPa)
        val cabinAltM = (altM * 0.20).coerceIn(10.0, 2150.0)
        val cabinPressureHpa = (1013.25 * (1.0 - cabinAltM / 44330.0).pow(5.255)).toFloat()
        val gForce = (sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ) / G).coerceIn(0.7f, 2.5f)

        return Fix(
            latitude = lat,
            longitude = lon,
            altitudeM = altM,
            speedMps = (speedKt * KT_TO_MPS).toFloat(),
            bearingDeg = bearing,
            pitchDeg = pitchDeg,
            rollDeg = rollDeg,
            yawDeg = bearing,
            accelX = accelX,
            accelY = accelY,
            accelZ = accelZ,
            gForce = gForce,
            pressureHpa = cabinPressureHpa,
            cabinAltitudeM = cabinAltM,
            expectedPhase = phase
        )
    }

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t.coerceIn(0.0, 1.0)

    private fun smoothStep(t: Double): Double {
        val x = t.coerceIn(0.0, 1.0)
        return x * x * (3.0 - 2.0 * x)
    }

    private fun lerpAngle(a: Float, b: Float, t: Float): Float {
        var diff = (b - a) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return (a + diff * t.coerceIn(0f, 1f) + 360f) % 360f
    }
}
