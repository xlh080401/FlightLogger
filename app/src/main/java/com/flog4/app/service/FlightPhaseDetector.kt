package com.flog4.app.service

import com.flog4.app.data.FlightPhase
import kotlin.math.abs

/**
 * Multi-sensor Flight Phase Detector with hysteresis.
 * Combines GPS ground speed, altitude above departure field, vertical climb/descent rate,
 * and level sensor pitch angle (俯仰角) to classify flight phases reliably.
 */
class FlightPhaseDetector {
    private var currentPhase: FlightPhase = FlightPhase.GROUND
    private var lastAltitude = 0.0
    private var lastTime = 0L
    private var candidatePhase: FlightPhase? = null
    private var candidateCount = 0
    private var referenceAltitudeM = Double.NaN
    private val confirmSamples = 3

    private companion object {
        const val GROUND_CEILING_M = 100.0
        const val CLIMB_RATE_MPS = 2.8
        const val DESCENT_RATE_MPS = 2.0
        const val GROUND_SPEED_KT = 5f
        const val TAXI_SPEED_KT = 32f
        const val LANDING_SPEED_KT = 175f
        const val LANDING_DESCENT_RATE_MPS = 0.5
        const val CRUISE_SPEED_KT = 150f
    }

    private val allowedTransitions: Map<FlightPhase, Set<FlightPhase>> = mapOf(
        FlightPhase.GROUND to setOf(FlightPhase.GROUND, FlightPhase.TAXI),
        FlightPhase.TAXI to setOf(FlightPhase.TAXI, FlightPhase.GROUND, FlightPhase.TAKEOFF),
        FlightPhase.TAKEOFF to setOf(FlightPhase.TAKEOFF, FlightPhase.CLIMB, FlightPhase.TAXI),
        FlightPhase.CLIMB to setOf(FlightPhase.CLIMB, FlightPhase.CRUISE, FlightPhase.DESCENT),
        FlightPhase.CRUISE to setOf(FlightPhase.CRUISE, FlightPhase.DESCENT, FlightPhase.CLIMB),
        FlightPhase.DESCENT to setOf(FlightPhase.DESCENT, FlightPhase.LANDING, FlightPhase.CRUISE, FlightPhase.CLIMB),
        FlightPhase.LANDING to setOf(FlightPhase.LANDING, FlightPhase.TAXI, FlightPhase.GROUND)
    )

    fun currentPhase(): FlightPhase = currentPhase

    fun detectPhase(
        accelX: Float,
        accelY: Float,
        accelZ: Float,
        speedMps: Float,
        altitudeM: Double,
        pressureHpa: Float,
        timestampMs: Long = System.currentTimeMillis(),
        pitchDeg: Float = 0f
    ): FlightPhase {
        val dt = (timestampMs - lastTime) / 1000.0
        val altitudeChangeRate = if (lastTime > 0 && dt > 0) (altitudeM - lastAltitude) / dt else 0.0
        if (referenceAltitudeM.isNaN()) referenceAltitudeM = altitudeM
        val aboveGroundM = altitudeM - referenceAltitudeM

        lastAltitude = altitudeM
        lastTime = timestampMs

        val speedKnots = speedMps * 1.94384f
        val raw = classifyRaw(speedKnots, aboveGroundM, altitudeChangeRate, pitchDeg)
        val constrained = constrainTransition(raw)

        if (constrained == currentPhase) {
            candidatePhase = null
            candidateCount = 0
            return currentPhase
        }

        if (constrained == candidatePhase) {
            candidateCount++
        } else {
            candidatePhase = constrained
            candidateCount = 1
        }

        if (candidateCount >= confirmSamples) {
            currentPhase = constrained
            candidatePhase = null
            candidateCount = 0
        }

        return currentPhase
    }

    fun reset() {
        currentPhase = FlightPhase.GROUND
        lastAltitude = 0.0
        lastTime = 0L
        referenceAltitudeM = Double.NaN
        candidatePhase = null
        candidateCount = 0
    }

    private fun classifyRaw(
        speedKnots: Float,
        aboveGroundM: Double,
        altitudeChangeRate: Double,
        pitchDeg: Float
    ): FlightPhase {
        val onGround = aboveGroundM < GROUND_CEILING_M

        if (onGround) {
            return when {
                speedKnots < GROUND_SPEED_KT -> FlightPhase.GROUND
                speedKnots < TAXI_SPEED_KT -> FlightPhase.TAXI
                else -> FlightPhase.TAKEOFF
            }
        }

        return when {
            speedKnots < TAXI_SPEED_KT -> FlightPhase.TAXI
            speedKnots < LANDING_SPEED_KT &&
                (altitudeChangeRate < -LANDING_DESCENT_RATE_MPS || aboveGroundM < 250.0) -> FlightPhase.LANDING
            altitudeChangeRate > CLIMB_RATE_MPS || (pitchDeg > 5.5f && altitudeChangeRate > 1.2) -> FlightPhase.CLIMB
            altitudeChangeRate < -DESCENT_RATE_MPS -> FlightPhase.DESCENT
            speedKnots > CRUISE_SPEED_KT && abs(altitudeChangeRate) <= CLIMB_RATE_MPS -> FlightPhase.CRUISE
            aboveGroundM > 500 -> FlightPhase.CRUISE
            else -> FlightPhase.CLIMB
        }
    }

    private fun constrainTransition(raw: FlightPhase): FlightPhase {
        val allowed = allowedTransitions[currentPhase] ?: return raw
        if (raw in allowed) return raw
        return when {
            currentPhase == FlightPhase.TAKEOFF && raw == FlightPhase.CRUISE -> FlightPhase.CLIMB
            currentPhase == FlightPhase.GROUND && raw == FlightPhase.TAKEOFF -> FlightPhase.TAXI
            currentPhase == FlightPhase.TAXI && raw == FlightPhase.CLIMB -> FlightPhase.TAKEOFF
            currentPhase == FlightPhase.LANDING && (raw == FlightPhase.CLIMB || raw == FlightPhase.CRUISE) -> FlightPhase.TAXI
            else -> currentPhase
        }
    }
}
