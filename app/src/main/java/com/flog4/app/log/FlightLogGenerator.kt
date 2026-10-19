package com.flog4.app.log

import com.flog4.app.data.FlightDatabase
import com.flog4.app.data.FlightPhase
import com.flog4.app.data.FlightRecord
import com.flog4.app.data.SensorDataPoint
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

data class FlightLog(
    val flight: FlightRecord,
    val sensorData: List<SensorDataPoint>,
    val altitudePoints: List<Pair<Long, Double>>,
    val pressurePoints: List<Pair<Long, Double>>,
    val speedPoints: List<Pair<Long, Double>>,
    val pitchPoints: List<Pair<Long, Double>>,
    val phaseTimeline: List<Pair<FlightPhase, Long>>
)

@Singleton
class FlightLogGenerator @Inject constructor(
    private val database: FlightDatabase
) {
    suspend fun generateLog(flightId: Long): FlightLog? {
        val flight = database.flightDao().getFlightById(flightId) ?: return null
        val sensorData = database.flightDao().getSensorDataForFlight(flightId)
        return buildFlightLog(flight, sensorData)
    }

    fun buildFlightLog(flight: FlightRecord, sensorData: List<SensorDataPoint>): FlightLog {
        if (sensorData.size >= 2) {
            val baseTime = sensorData.first().timestamp
            val rawDuration = (sensorData.last().timestamp - baseTime).coerceAtLeast(1L)
            val targetDuration = if (flight.totalFlightTimeMs > 60_000L) flight.totalFlightTimeMs else rawDuration
            val timeScale = targetDuration.toDouble() / rawDuration.toDouble()

            val recordedMaxAlt = (sensorData.maxOfOrNull { it.gpsAltitudeM } ?: 1.0).coerceAtLeast(1.0)
            val altScale = if (flight.maxAltitudeM > 100.0 && recordedMaxAlt > 10.0) {
                flight.maxAltitudeM / recordedMaxAlt
            } else 1.0

            val recordedMaxSpd = (sensorData.maxOfOrNull { it.gpsSpeedMps * 1.94384 } ?: 1.0).coerceAtLeast(1.0)
            val spdScale = if (flight.maxSpeedKnots > 50.0 && recordedMaxSpd > 10.0) {
                flight.maxSpeedKnots / recordedMaxSpd
            } else 1.0

            val altPoints = sensorData.map {
                ((it.timestamp - baseTime) * timeScale).toLong() to (it.gpsAltitudeM * altScale)
            }
            val spdPoints = sensorData.map {
                ((it.timestamp - baseTime) * timeScale).toLong() to (it.gpsSpeedMps * 1.94384 * spdScale)
            }
            val presPoints = sensorData.map {
                val p = if (it.pressureHpa > 100f) {
                    it.pressureHpa.toDouble()
                } else {
                    val cAlt = (it.gpsAltitudeM * 0.20).coerceIn(0.0, 2400.0)
                    1013.25 * (1.0 - cAlt / 44330.0).pow(5.255)
                }
                ((it.timestamp - baseTime) * timeScale).toLong() to p
            }
            val pitchPoints = sensorData.map {
                ((it.timestamp - baseTime) * timeScale).toLong() to it.pitchDeg.toDouble()
            }

            val phaseTimeline = mutableListOf<Pair<FlightPhase, Long>>()
            var lastPhase: FlightPhase? = null
            for (dp in sensorData) {
                if (dp.phase != lastPhase) {
                    phaseTimeline.add(dp.phase to ((dp.timestamp - baseTime) * timeScale).toLong())
                    lastPhase = dp.phase
                }
            }

            return FlightLog(
                flight = flight,
                sensorData = sensorData,
                altitudePoints = altPoints,
                pressurePoints = presPoints,
                speedPoints = spdPoints,
                pitchPoints = pitchPoints,
                phaseTimeline = phaseTimeline
            )
        }

        // Synthesize smooth curves from flight summary parameters if no raw sensor stream exists yet
        val durationMs = flight.totalFlightTimeMs.takeIf { it > 60_000L } ?: (110L * 60_000L)
        val maxAlt = flight.maxAltitudeM.takeIf { it > 100.0 } ?: 10200.0
        val maxSpd = flight.maxSpeedKnots.takeIf { it > 100.0 } ?: 455.0
        val minPress = flight.minCabinPressureHpa.takeIf { it in 500f..1000f }?.toDouble() ?: 785.0
        val maxPress = flight.maxCabinPressureHpa.takeIf { it > 950f }?.toDouble() ?: 1013.25
        val v2 = flight.v2Knots.takeIf { it > 80.0 } ?: 155.0
        val vapp = flight.vappKnots.takeIf { it > 80.0 } ?: 138.0

        val steps = 120
        val altPts = ArrayList<Pair<Long, Double>>(steps + 1)
        val presPts = ArrayList<Pair<Long, Double>>(steps + 1)
        val spdPts = ArrayList<Pair<Long, Double>>(steps + 1)
        val pitchPts = ArrayList<Pair<Long, Double>>(steps + 1)

        for (i in 0..steps) {
            val u = i.toDouble() / steps
            val tMs = (u * durationMs).toLong()
            val alt = when {
                u < 0.05 -> 30.0
                u < 0.22 -> 30.0 + (maxAlt - 30.0) * smooth((u - 0.05) / 0.17)
                u < 0.78 -> maxAlt + 25.0 * sin((u - 0.22) * 4 * PI)
                u < 0.95 -> maxAlt * (1.0 - smooth((u - 0.78) / 0.17)).coerceAtLeast(0.01)
                else -> 15.0
            }
            val press = maxPress - (maxPress - minPress) * ((alt / maxAlt).coerceIn(0.0, 1.0))
            val spd = when {
                u < 0.04 -> 15.0 * (u / 0.04)
                u < 0.08 -> 15.0 + (v2 - 15.0) * ((u - 0.04) / 0.04)
                u < 0.20 -> v2 + (maxSpd - v2) * ((u - 0.08) / 0.12)
                u < 0.78 -> maxSpd - 8.0 * sin((u - 0.20) * 3 * PI)
                u < 0.93 -> vapp + (maxSpd - vapp) * (1.0 - (u - 0.78) / 0.15)
                u < 0.96 -> vapp * (1.0 - (u - 0.93) / 0.03 * 0.8)
                else -> 15.0 * (1.0 - (u - 0.96) / 0.04).coerceAtLeast(0.0)
            }
            val pitch = when {
                u in 0.05..0.09 -> 11.0
                u in 0.09..0.22 -> 7.0
                u in 0.22..0.78 -> 2.1
                u in 0.78..0.92 -> -2.5
                u in 0.92..0.95 -> 4.2
                else -> 0.0
            }
            altPts.add(tMs to alt)
            presPts.add(tMs to press)
            spdPts.add(tMs to spd)
            pitchPts.add(tMs to pitch)
        }

        val phases = listOf(
            FlightPhase.TAXI to 0L,
            FlightPhase.TAKEOFF to (durationMs * 0.05).toLong(),
            FlightPhase.CLIMB to (durationMs * 0.08).toLong(),
            FlightPhase.CRUISE to (durationMs * 0.22).toLong(),
            FlightPhase.DESCENT to (durationMs * 0.78).toLong(),
            FlightPhase.LANDING to (durationMs * 0.93).toLong()
        )

        return FlightLog(
            flight = flight,
            sensorData = emptyList(),
            altitudePoints = altPts,
            pressurePoints = presPts,
            speedPoints = spdPts,
            pitchPoints = pitchPts,
            phaseTimeline = phases
        )
    }

    private fun smooth(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
