package com.flog4.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FlightDao {
    @Insert
    suspend fun insertFlight(flight: FlightRecord): Long

    @Update
    suspend fun updateFlight(flight: FlightRecord)

    @Delete
    suspend fun deleteFlight(flight: FlightRecord)

    @Query("SELECT * FROM flights ORDER BY createdAt DESC")
    fun getAllFlights(): Flow<List<FlightRecord>>

    @Query("SELECT * FROM flights WHERE id = :id")
    suspend fun getFlightById(id: Long): FlightRecord?

    @Query("SELECT * FROM flights WHERE id = :id")
    fun getFlightByIdFlow(id: Long): Flow<FlightRecord?>

    @Query("SELECT * FROM flights WHERE status = 'RECORDING' LIMIT 1")
    suspend fun getActiveFlight(): FlightRecord?

    @Query(
        "UPDATE flights SET status = :status, takeoffTimeUtc = :takeoffTimeUtc, " +
            "takeoffTimeDisplay = :takeoffTimeDisplay, date = :date WHERE id = :flightId"
    )
    suspend fun updateRecordingStart(
        flightId: Long,
        status: FlightStatus,
        takeoffTimeUtc: Long,
        takeoffTimeDisplay: String,
        date: String
    )

    @Query(
        "UPDATE flights SET " +
            "flightNumber = :flightNumber, " +
            "airline = :airline, " +
            "airlineName = :airlineName, " +
            "origin = :origin, " +
            "originName = :originName, " +
            "destination = :destination, " +
            "destinationName = :destinationName, " +
            "date = :date, " +
            "seatNumber = :seatNumber, " +
            "passengerName = :passengerName, " +
            "cabinClass = :cabinClass, " +
            "boardingGate = :boardingGate, " +
            "aircraftType = :aircraftType, " +
            "registration = :registration, " +
            "maxPassengers = :maxPassengers " +
            "WHERE id = :flightId"
    )
    suspend fun updateFlightMetadata(
        flightId: Long,
        flightNumber: String,
        airline: String,
        airlineName: String,
        origin: String,
        originName: String,
        destination: String,
        destinationName: String,
        date: String,
        seatNumber: String,
        passengerName: String,
        cabinClass: String,
        boardingGate: String,
        aircraftType: String,
        registration: String,
        maxPassengers: Int
    )

    @SuppressWarnings(RoomWarnings.CURSOR_MISMATCH)
    @Query(
        "UPDATE flights SET " +
            "status = :status, " +
            "takeoffTimeUtc = :takeoffTimeUtc, " +
            "takeoffTimeDisplay = :takeoffTimeDisplay, " +
            "landingTimeUtc = :landingTimeUtc, " +
            "landingTimeDisplay = :landingTimeDisplay, " +
            "totalFlightTimeMs = :totalFlightTimeMs, " +
            "totalFlightTimeDisplay = :totalFlightTimeDisplay, " +
            "totalDistanceKm = :totalDistanceKm, " +
            "v1Knots = :v1Knots, " +
            "vrKnots = :vrKnots, " +
            "v2Knots = :v2Knots, " +
            "vappKnots = :vappKnots, " +
            "departureRunway = :departureRunway, " +
            "arrivalRunway = :arrivalRunway, " +
            "departureTerminal = :departureTerminal, " +
            "arrivalTerminal = :arrivalTerminal, " +
            "maxAltitudeM = :maxAltitudeM, " +
            "maxSpeedKnots = :maxSpeedKnots, " +
            "avgSpeedKnots = :avgSpeedKnots, " +
            "minCabinPressureHpa = :minCabinPressureHpa, " +
            "maxCabinPressureHpa = :maxCabinPressureHpa, " +
            "maxPitchDeg = :maxPitchDeg, " +
            "maxRollDeg = :maxRollDeg, " +
            "touchdownG = :touchdownG, " +
            "maxGForce = :maxGForce, " +
            "turbulenceIndex = :turbulenceIndex, " +
            "aiReport = :aiReport, " +
            "aiGpuInfo = :aiGpuInfo, " +
            "aiScore = :aiScore " +
            "WHERE id = :flightId"
    )
    suspend fun updateFlightTelemetryAndAi(
        flightId: Long,
        status: FlightStatus,
        takeoffTimeUtc: Long,
        takeoffTimeDisplay: String,
        landingTimeUtc: Long,
        landingTimeDisplay: String,
        totalFlightTimeMs: Long,
        totalFlightTimeDisplay: String,
        totalDistanceKm: Double,
        v1Knots: Double,
        vrKnots: Double,
        v2Knots: Double,
        vappKnots: Double,
        departureRunway: String,
        arrivalRunway: String,
        departureTerminal: String,
        arrivalTerminal: String,
        maxAltitudeM: Double,
        maxSpeedKnots: Double,
        avgSpeedKnots: Double,
        minCabinPressureHpa: Float,
        maxCabinPressureHpa: Float,
        maxPitchDeg: Float,
        maxRollDeg: Float,
        touchdownG: Float,
        maxGForce: Float,
        turbulenceIndex: Double,
        aiReport: String,
        aiGpuInfo: String,
        aiScore: Int
    )

    @Insert
    suspend fun insertSensorDataBatch(points: List<SensorDataPoint>)

    @Query("SELECT * FROM sensor_data WHERE flightId = :flightId ORDER BY timestamp ASC")
    suspend fun getSensorDataForFlight(flightId: Long): List<SensorDataPoint>

    @Query("SELECT * FROM sensor_data WHERE flightId = :flightId ORDER BY timestamp ASC")
    fun getSensorDataFlow(flightId: Long): Flow<List<SensorDataPoint>>

    @Query("DELETE FROM sensor_data WHERE flightId = :flightId")
    suspend fun deleteSensorDataForFlight(flightId: Long)
}
