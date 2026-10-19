package com.flog4.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.flog4.app.MainActivity
import com.flog4.app.R
import com.flog4.app.ai.GpuAiInferenceEngine
import com.flog4.app.data.AircraftDatabase
import com.flog4.app.data.FlightDao
import com.flog4.app.data.FlightPhase
import com.flog4.app.data.FlightStatus
import com.flog4.app.data.SensorDataPoint
import com.flog4.app.util.TimeUtils
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt

data class GpsSignalInfo(
    val satellitesInFix: Int = 0,
    val satellitesVisible: Int = 0,
    val avgSnrDb: Float = 0f,
    val signalLevel: Int = 0
)

data class SensorAvailability(
    val hasLevelSensor: Boolean = false,
    val hasAccelerometer: Boolean = false,
    val hasBarometer: Boolean = false,
    val hasGps: Boolean = true
)

/**
 * Foreground Flight Telemetry Collection Service.
 *
 * Reads and fuses 4 core phone sensors strictly offline:
 * 1. 水平仪 (Level / Attitude Sensor): Rotation Vector / Game Rotation Vector / Accel+Mag -> Pitch, Roll, Yaw (°)
 * 2. 加速度计 (Accelerometer): 3-axis acceleration (m/s²) and combined G-Force (G)
 * 3. 气压计 (Barometer): Cabin atmospheric pressure (hPa) and equivalent cabin altitude (m)
 * 4. GPS (GNSS Satellite Receiver): Latitude, Longitude, GPS Altitude (m), Ground Speed (m/s), Bearing (°)
 */
@AndroidEntryPoint
class SensorCollectionService : Service(), SensorEventListener {

    companion object {
        const val CHANNEL_ID = "flog4_flight_recorder"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_FLIGHT_ID = "flight_id"
        const val EXTRA_SIMULATE = "simulate_mode"
        const val ACTION_START = "com.flog4.app.START_RECORDING"
        const val ACTION_STOP = "com.flog4.app.STOP_RECORDING"
        const val ACTION_SIMULATE = "com.flog4.app.SIMULATE_FLIGHT"

        private const val SIMULATION_SPEED_FACTOR = 12
        private const val DEFAULT_IMU_INTERVAL_MS = 1000L
        private const val DEFAULT_GPS_INTERVAL_MS = 2000L
        private const val BATCH_SIZE = 30
        private const val WAKELOCK_TAG = "FLOG4::FlightRecorderWakeLock"
        private const val WAKELOCK_TIMEOUT_MS = 30L * 60 * 1000

        val sensorStream = MutableStateFlow<SensorDataPoint?>(null)
        val gpsSignalStream = MutableStateFlow(GpsSignalInfo())
        val sensorAvailability = MutableStateFlow(SensorAvailability())
        val recordingFlightId = MutableStateFlow<Long?>(null)
        val isSimulatingFlow = MutableStateFlow(false)
        var currentDataPointCount = 0
    }

    @Inject lateinit var dao: FlightDao
    @Inject lateinit var gpuAiEngine: GpuAiInferenceEngine

    private lateinit var sensorManager: SensorManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationManager: LocationManager
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var flightId: Long = -1
    private var isRecording = false
    private var hasGpsFix = false
    private var hasHardwareBarometer = false
    private var hasRotationSensor = false
    private var locationPermissionGranted = false

    // Live sensor state
    private var currentPitchDeg = 0f
    private var currentRollDeg = 0f
    private var currentYawDeg = 0f
    private var filteredAccelX = 0f
    private var filteredAccelY = 0f
    private var filteredAccelZ = 9.81f
    private var currentGForce = 1.0f
    private var currentPressureHpa = 1013.25f
    private var currentLatitude = 0.0
    private var currentLongitude = 0.0
    private var currentGpsAltitude = 0.0
    private var currentGpsSpeed = 0f
    private var currentBearing = 0f

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val alpha = 0.8f

    private val phaseDetector = FlightPhaseDetector()
    private val telemetryAnalyzer = SpeedAndTelemetryAnalyzer()

    private val dataBatch = mutableListOf<SensorDataPoint>()
    private val dataBatchLock = Any()
    private var lastNotificationUpdateMs = 0L

    // Simulation state
    private var simulationEnabled = false
    private var simulationStartMs = 0L
    private var simulatedElapsedMs = 0L

    private var wakeLock: PowerManager.WakeLock? = null
    private var finishingJob: Job? = null

    private val sampleHandler = Handler(Looper.getMainLooper())
    private val sampleRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                recordDataPoint()
                val delay = if (simulationEnabled) {
                    (DEFAULT_IMU_INTERVAL_MS / SIMULATION_SPEED_FACTOR).coerceAtLeast(50L)
                } else {
                    DEFAULT_IMU_INTERVAL_MS
                }
                sampleHandler.postDelayed(this, delay)
            }
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (simulationEnabled) return
            result.lastLocation?.let { loc ->
                currentLatitude = loc.latitude
                currentLongitude = loc.longitude
                currentGpsAltitude = loc.altitude
                currentGpsSpeed = loc.speed
                currentBearing = loc.bearing
                hasGpsFix = true
            }
        }
    }

    private val directGpsListener = object : android.location.LocationListener {
        override fun onLocationChanged(loc: android.location.Location) {
            if (simulationEnabled) return
            currentLatitude = loc.latitude
            currentLongitude = loc.longitude
            currentGpsAltitude = loc.altitude
            currentGpsSpeed = loc.speed
            currentBearing = loc.bearing
            hasGpsFix = true
        }
        @Deprecated("Deprecated in API")
        override fun onStatusChanged(provider: String, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            if (simulationEnabled) return
            val total = status.satelliteCount
            var inFix = 0
            var snrSum = 0f
            var snrCount = 0
            for (i in 0 until total) {
                if (status.usedInFix(i)) inFix++
                val cn0 = status.getCn0DbHz(i)
                if (cn0 > 0f) {
                    snrSum += cn0
                    snrCount++
                }
            }
            val avgSnr = if (snrCount > 0) snrSum / snrCount else 0f
            val level = when {
                inFix == 0 -> 0
                avgSnr < 15 || inFix < 3 -> 1
                avgSnr < 25 || inFix < 6 -> 2
                avgSnr < 35 || inFix < 10 -> 3
                else -> 4
            }
            gpsSignalStream.value = GpsSignalInfo(inFix, total, avgSnr, level)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val baroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        hasRotationSensor = rotSensor != null
        hasHardwareBarometer = baroSensor != null

        sensorAvailability.value = SensorAvailability(
            hasLevelSensor = rotSensor != null || accelSensor != null,
            hasAccelerometer = accelSensor != null,
            hasBarometer = baroSensor != null,
            hasGps = true
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                flightId = intent.getLongExtra(EXTRA_FLIGHT_ID, -1)
                val sim = intent.getBooleanExtra(EXTRA_SIMULATE, false)
                if (flightId > 0) {
                    startRecording(simulate = sim)
                }
            }
            ACTION_SIMULATE -> {
                enableSimulation()
            }
            ACTION_STOP -> {
                stopRecording()
            }
        }
        return START_NOT_STICKY
    }

    private fun enableSimulation() {
        simulationEnabled = true
        isSimulatingFlow.value = true
        simulationStartMs = System.currentTimeMillis()
        simulatedElapsedMs = 0L
        gpsSignalStream.value = GpsSignalInfo(
            satellitesInFix = 18,
            satellitesVisible = 24,
            avgSnrDb = 38.5f,
            signalLevel = 4
        )
    }

    private fun startRecording(simulate: Boolean) {
        if (isRecording) {
            if (simulate && !simulationEnabled) enableSimulation()
            return
        }
        isRecording = true
        hasGpsFix = false
        locationPermissionGranted = hasLocationPermission()
        phaseDetector.reset()
        currentDataPointCount = 0
        sensorStream.value = null
        lastNotificationUpdateMs = 0L
        synchronized(dataBatchLock) { dataBatch.clear() }

        if (simulate) {
            enableSimulation()
        } else {
            simulationEnabled = false
            isSimulatingFlow.value = false
            gpsSignalStream.value = GpsSignalInfo()
        }

        try {
            startForegroundCompat(buildNotification(getString(R.string.notification_recording_started)))
        } catch (_: SecurityException) {
            isRecording = false
            recordingFlightId.value = null
            stopSelf()
            return
        }

        recordingFlightId.value = flightId
        acquireWakeLock()

        serviceScope.launch {
            val now = System.currentTimeMillis()
            dao.updateRecordingStart(
                flightId = flightId,
                status = FlightStatus.RECORDING,
                takeoffTimeUtc = now,
                takeoffTimeDisplay = TimeUtils.formatLocalTime(now),
                date = TimeUtils.formatDate(now)
            )
        }

        // 1. Register Level / Attitude Sensor (水平仪: Rotation Vector / Game Rotation Vector)
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        // 2. Register Accelerometer (加速度计)
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        // 3. Register Barometer (气压计)
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        // 4. Register GPS / GNSS Location Updates
        if (locationPermissionGranted) {
            try {
                val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, DEFAULT_GPS_INTERVAL_MS)
                    .setMinUpdateIntervalMillis(1000L)
                    .setMaxUpdateDelayMillis(DEFAULT_GPS_INTERVAL_MS)
                    .setWaitForAccurateLocation(false)
                    .setGranularity(Granularity.GRANULARITY_FINE)
                    .build()
                fusedLocationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
            } catch (_: SecurityException) {}

            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    directGpsListener,
                    Looper.getMainLooper()
                )
            } catch (_: Exception) {}

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    locationManager.registerGnssStatusCallback(ContextCompat.getMainExecutor(this), gnssCallback)
                } else {
                    @Suppress("DEPRECATION")
                    locationManager.registerGnssStatusCallback(gnssCallback, Handler(Looper.getMainLooper()))
                }
            } catch (_: SecurityException) {}
        }

        sampleHandler.post(sampleRunnable)
    }

    private fun stopRecording() {
        if (!isRecording) {
            recordingFlightId.value = null
            isSimulatingFlow.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        isRecording = false
        recordingFlightId.value = null
        isSimulatingFlow.value = false
        sampleHandler.removeCallbacks(sampleRunnable)
        sensorManager.unregisterListener(this)
        try { fusedLocationClient.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
        try { locationManager.unregisterGnssStatusCallback(gnssCallback) } catch (_: Exception) {}
        try { locationManager.removeUpdates(directGpsListener) } catch (_: Exception) {}
        releaseWakeLock()

        finishingJob = serviceScope.launch {
            try {
                val finalBatch = synchronized(dataBatchLock) {
                    if (dataBatch.isNotEmpty()) {
                        val copy = ArrayList(dataBatch)
                        dataBatch.clear()
                        copy
                    } else null
                }
                if (finalBatch != null) {
                    dao.insertSensorDataBatch(finalBatch)
                }

                val flight = dao.getFlightById(flightId)
                if (flight != null) {
                    val sensorData = dao.getSensorDataForFlight(flightId)
                    val analyzed = telemetryAnalyzer.analyzeCompleteFlight(
                        context = this@SensorCollectionService,
                        data = sensorData,
                        originIata = flight.origin,
                        destinationIata = flight.destination,
                        fallbackStartMs = flight.takeoffTimeUtc.takeIf { it > 0L } ?: flight.createdAt
                    )

                    val maxPax = if (flight.maxPassengers > 0) {
                        flight.maxPassengers
                    } else {
                        AircraftDatabase.getMaxPassengers(this@SensorCollectionService, flight.aircraftType)
                    }

                    val updatedRecordForAi = flight.copy(
                        takeoffTimeUtc = analyzed.takeoffTimeUtc,
                        takeoffTimeDisplay = analyzed.takeoffTimeDisplay,
                        landingTimeUtc = analyzed.landingTimeUtc,
                        landingTimeDisplay = analyzed.landingTimeDisplay,
                        totalFlightTimeMs = analyzed.totalFlightTimeMs,
                        totalFlightTimeDisplay = analyzed.totalFlightTimeDisplay,
                        totalDistanceKm = analyzed.totalDistanceKm,
                        v1Knots = analyzed.v1Knots,
                        vrKnots = analyzed.vrKnots,
                        v2Knots = analyzed.v2Knots,
                        vappKnots = analyzed.vappKnots,
                        departureRunway = analyzed.departureRunway.ifBlank { flight.departureRunway },
                        arrivalRunway = analyzed.arrivalRunway.ifBlank { flight.arrivalRunway },
                        departureTerminal = analyzed.departureTerminal.ifBlank { flight.departureTerminal },
                        arrivalTerminal = analyzed.arrivalTerminal.ifBlank { flight.arrivalTerminal },
                        maxAltitudeM = analyzed.maxAltitudeM,
                        maxSpeedKnots = analyzed.maxSpeedKnots,
                        avgSpeedKnots = analyzed.avgSpeedKnots,
                        minCabinPressureHpa = analyzed.minCabinPressureHpa,
                        maxCabinPressureHpa = analyzed.maxCabinPressureHpa,
                        maxPitchDeg = analyzed.maxPitchDeg,
                        maxRollDeg = analyzed.maxRollDeg,
                        touchdownG = analyzed.touchdownG,
                        maxGForce = analyzed.maxGForce,
                        turbulenceIndex = analyzed.turbulenceIndex,
                        maxPassengers = maxPax
                    )

                    val aiResult = gpuAiEngine.generateFlightReport(
                        flight = updatedRecordForAi,
                        sensorData = sensorData,
                        preferChinese = com.flog4.app.ui.i18n.AppLocaleManager.isChinese
                    )

                    dao.updateFlightTelemetryAndAi(
                        flightId = flightId,
                        status = FlightStatus.COMPLETED,
                        takeoffTimeUtc = analyzed.takeoffTimeUtc,
                        takeoffTimeDisplay = analyzed.takeoffTimeDisplay,
                        landingTimeUtc = analyzed.landingTimeUtc,
                        landingTimeDisplay = analyzed.landingTimeDisplay,
                        totalFlightTimeMs = analyzed.totalFlightTimeMs,
                        totalFlightTimeDisplay = analyzed.totalFlightTimeDisplay,
                        totalDistanceKm = analyzed.totalDistanceKm,
                        v1Knots = analyzed.v1Knots,
                        vrKnots = analyzed.vrKnots,
                        v2Knots = analyzed.v2Knots,
                        vappKnots = analyzed.vappKnots,
                        departureRunway = updatedRecordForAi.departureRunway,
                        arrivalRunway = updatedRecordForAi.arrivalRunway,
                        departureTerminal = updatedRecordForAi.departureTerminal,
                        arrivalTerminal = updatedRecordForAi.arrivalTerminal,
                        maxAltitudeM = analyzed.maxAltitudeM,
                        maxSpeedKnots = analyzed.maxSpeedKnots,
                        avgSpeedKnots = analyzed.avgSpeedKnots,
                        minCabinPressureHpa = analyzed.minCabinPressureHpa,
                        maxCabinPressureHpa = analyzed.maxCabinPressureHpa,
                        maxPitchDeg = analyzed.maxPitchDeg,
                        maxRollDeg = analyzed.maxRollDeg,
                        touchdownG = analyzed.touchdownG,
                        maxGForce = analyzed.maxGForce,
                        turbulenceIndex = analyzed.turbulenceIndex,
                        aiReport = aiResult.reportText,
                        aiGpuInfo = aiResult.gpuDeviceInfo,
                        aiScore = aiResult.overallScore
                    )
                }
            } finally {
                simulationEnabled = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun recordDataPoint() {
        if (!isRecording) return

        val sampleTime = System.currentTimeMillis()
        val detectorTime: Long

        if (simulationEnabled) {
            simulatedElapsedMs = (sampleTime - simulationStartMs) * SIMULATION_SPEED_FACTOR
            val fix = SimulatedFlight.fixAt(simulatedElapsedMs)
            currentLatitude = fix.latitude
            currentLongitude = fix.longitude
            currentGpsAltitude = fix.altitudeM
            currentGpsSpeed = fix.speedMps
            currentBearing = fix.bearingDeg
            currentPitchDeg = fix.pitchDeg
            currentRollDeg = fix.rollDeg
            currentYawDeg = fix.yawDeg
            filteredAccelX = fix.accelX
            filteredAccelY = fix.accelY
            filteredAccelZ = fix.accelZ
            currentGForce = fix.gForce
            currentPressureHpa = fix.pressureHpa
            hasGpsFix = true
            detectorTime = simulationStartMs + simulatedElapsedMs
        } else {
            detectorTime = sampleTime
            // If device has no physical barometer, estimate realistic cabin pressure from GPS altitude
            if (!hasHardwareBarometer || currentPressureHpa <= 0f) {
                val estCabinAlt = (currentGpsAltitude * 0.20).coerceIn(0.0, 2400.0)
                currentPressureHpa = (1013.25 * (1.0 - estCabinAlt / 44330.0).pow(5.255)).toFloat()
            }
        }

        val altitude = if (hasGpsFix && currentGpsAltitude != 0.0) {
            currentGpsAltitude
        } else {
            pressureAltitudeM(currentPressureHpa)
        }
        val cabinAlt = pressureAltitudeM(currentPressureHpa)

        val phase = phaseDetector.detectPhase(
            accelX = filteredAccelX,
            accelY = filteredAccelY,
            accelZ = filteredAccelZ,
            speedMps = currentGpsSpeed,
            altitudeM = altitude,
            pressureHpa = currentPressureHpa,
            timestampMs = detectorTime,
            pitchDeg = currentPitchDeg
        )

        val point = SensorDataPoint(
            flightId = flightId,
            timestamp = sampleTime,
            pitchDeg = currentPitchDeg,
            rollDeg = currentRollDeg,
            yawDeg = currentYawDeg,
            accelX = filteredAccelX,
            accelY = filteredAccelY,
            accelZ = filteredAccelZ,
            gForce = currentGForce,
            pressureHpa = currentPressureHpa,
            cabinAltitudeM = cabinAlt,
            latitude = currentLatitude,
            longitude = currentLongitude,
            gpsAltitudeM = altitude,
            gpsSpeedMps = currentGpsSpeed,
            bearingDeg = currentBearing,
            phase = phase
        )

        val toFlush = synchronized(dataBatchLock) {
            dataBatch.add(point)
            currentDataPointCount++
            if (dataBatch.size >= BATCH_SIZE) {
                val copy = ArrayList(dataBatch)
                dataBatch.clear()
                copy
            } else null
        }

        sensorStream.value = point

        if (toFlush != null) {
            serviceScope.launch {
                dao.insertSensorDataBatch(toFlush)
            }
        }

        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdateMs >= 1000L) {
            lastNotificationUpdateMs = now
            val gpsInfo = gpsSignalStream.value
            updateNotification(
                getString(
                    R.string.notification_status,
                    phaseLabel(phase),
                    String.format(Locale.US, "%.0fm", altitude),
                    String.format(Locale.US, "%.0fkts", currentGpsSpeed * 1.94384f),
                    String.format(Locale.US, "%.1f°", currentPitchDeg),
                    gpsInfo.satellitesInFix
                )
            )
        }

        // Auto-complete simulated flight when it reaches end of track
        if (simulationEnabled && simulatedElapsedMs >= SimulatedFlight.durationMs()) {
            stopRecording()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (simulationEnabled) return
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                // orientationAngles: [0]=azimuth(yaw), [1]=pitch, [2]=roll in radians
                val yaw = ((Math.toDegrees(orientationAngles[0].toDouble()) + 360.0) % 360.0).toFloat()
                val pitch = (-Math.toDegrees(orientationAngles[1].toDouble())).toFloat()
                val roll = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
                currentYawDeg = yaw
                currentPitchDeg = pitch
                currentRollDeg = roll
            }
            Sensor.TYPE_ACCELEROMETER -> {
                filteredAccelX = alpha * filteredAccelX + (1 - alpha) * event.values[0]
                filteredAccelY = alpha * filteredAccelY + (1 - alpha) * event.values[1]
                filteredAccelZ = alpha * filteredAccelZ + (1 - alpha) * event.values[2]
                val mag = sqrt(
                    filteredAccelX * filteredAccelX +
                        filteredAccelY * filteredAccelY +
                        filteredAccelZ * filteredAccelZ
                )
                currentGForce = (mag / 9.80665f).coerceIn(0.1f, 6.0f)

                // Fallback level calculation (pitch & roll) from gravity vector if device lacks rotation sensor
                if (!hasRotationSensor) {
                    currentPitchDeg = Math.toDegrees(
                        atan2(
                            filteredAccelY.toDouble(),
                            sqrt((filteredAccelX * filteredAccelX + filteredAccelZ * filteredAccelZ).toDouble())
                        )
                    ).toFloat()
                    currentRollDeg = Math.toDegrees(
                        atan2(-filteredAccelX.toDouble(), filteredAccelZ.toDouble())
                    ).toFloat()
                }
            }
            Sensor.TYPE_PRESSURE -> {
                currentPressureHpa = event.values[0]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun pressureAltitudeM(hpa: Float): Double {
        if (hpa <= 0f) return 0.0
        return 44330.0 * (1.0 - (hpa / 1013.25).pow(0.1903))
    }

    private fun startForegroundCompat(notification: Notification) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                val type = if (locationPermissionGranted) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                try {
                    startForeground(NOTIFICATION_ID, notification, type)
                } catch (_: Exception) {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                if (locationPermissionGranted) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                } else {
                    startForeground(NOTIFICATION_ID, notification, 0)
                }
            }
            else -> startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
        }
        try {
            wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun phaseLabel(phase: FlightPhase): String = getString(
        when (phase) {
            FlightPhase.GROUND -> R.string.phase_ground
            FlightPhase.TAXI -> R.string.phase_taxi
            FlightPhase.TAKEOFF -> R.string.phase_takeoff
            FlightPhase.CLIMB -> R.string.phase_climb
            FlightPhase.CRUISE -> R.string.phase_cruise
            FlightPhase.DESCENT -> R.string.phase_descent
            FlightPhase.LANDING -> R.string.phase_landing
        }
    )

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_flight)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        sampleHandler.removeCallbacks(sampleRunnable)
        releaseWakeLock()
        try { locationManager.unregisterGnssStatusCallback(gnssCallback) } catch (_: Exception) {}
        try { locationManager.removeUpdates(directGpsListener) } catch (_: Exception) {}
        try { fusedLocationClient.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
        try { sensorManager.unregisterListener(this) } catch (_: Exception) {}
        serviceScope.cancel()
        super.onDestroy()
    }
}
