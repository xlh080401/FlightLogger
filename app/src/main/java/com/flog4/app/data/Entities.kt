package com.flog4.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class FlightPhase {
    GROUND,
    TAXI,
    TAKEOFF,
    CLIMB,
    CRUISE,
    DESCENT,
    LANDING
}

enum class FlightStatus {
    SETUP,
    RECORDING,
    COMPLETED
}

@Entity(tableName = "flights")
data class FlightRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // Boarding Pass / Route Info (OCR + Editable)
    val flightNumber: String = "",
    val airline: String = "",
    val airlineName: String = "",
    val origin: String = "",
    val originName: String = "",
    val destination: String = "",
    val destinationName: String = "",
    val date: String = "",
    val seatNumber: String = "",
    val passengerName: String = "",
    val cabinClass: String = "Economy",
    val boardingGate: String = "",
    // Aircraft Info (Guided Manual Input + Editable)
    val aircraftType: String = "",
    val registration: String = "",
    val maxPassengers: Int = 0,
    // Timing & Distance (Sensor Computed + Editable)
    val takeoffTimeUtc: Long = 0L,
    val takeoffTimeDisplay: String = "",
    val landingTimeUtc: Long = 0L,
    val landingTimeDisplay: String = "",
    val totalFlightTimeMs: Long = 0L,
    val totalFlightTimeDisplay: String = "",
    val totalDistanceKm: Double = 0.0,
    // V-Speeds (kts, Sensor Computed + Editable)
    val v1Knots: Double = 0.0,
    val vrKnots: Double = 0.0,
    val v2Knots: Double = 0.0,
    val vappKnots: Double = 0.0,
    // Runway & Terminal (Offline GPS Matched + Editable)
    val departureRunway: String = "",
    val arrivalRunway: String = "",
    val departureTerminal: String = "",
    val arrivalTerminal: String = "",
    // Altitude, Speed, Pressure & Attitude Extremes (Sensor Computed + Editable)
    val maxAltitudeM: Double = 0.0,
    val maxSpeedKnots: Double = 0.0,
    val avgSpeedKnots: Double = 0.0,
    val minCabinPressureHpa: Float = 0f,
    val maxCabinPressureHpa: Float = 1013.25f,
    val maxPitchDeg: Float = 0f,
    val maxRollDeg: Float = 0f,
    val touchdownG: Float = 1.0f,
    val maxGForce: Float = 1.0f,
    val turbulenceIndex: Double = 0.0,
    // On-Device GPU AI Flight Report (Editable)
    val aiReport: String = "",
    val aiGpuInfo: String = "",
    val aiScore: Int = 0,
    val status: FlightStatus = FlightStatus.SETUP,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "sensor_data",
    foreignKeys = [ForeignKey(
        entity = FlightRecord::class,
        parentColumns = ["id"],
        childColumns = ["flightId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("flightId"), Index("timestamp")]
)
data class SensorDataPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val flightId: Long,
    val timestamp: Long,
    // Level / Attitude Sensor (水平仪)
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val yawDeg: Float = 0f,
    // Accelerometer (加速度计)
    val accelX: Float = 0f,
    val accelY: Float = 0f,
    val accelZ: Float = 9.81f,
    val gForce: Float = 1.0f,
    // Barometer (气压计)
    val pressureHpa: Float = 1013.25f,
    val cabinAltitudeM: Double = 0.0,
    // GPS (卫星定位)
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val gpsAltitudeM: Double = 0.0,
    val gpsSpeedMps: Float = 0f,
    val bearingDeg: Float = 0f,
    val phase: FlightPhase = FlightPhase.GROUND
)
