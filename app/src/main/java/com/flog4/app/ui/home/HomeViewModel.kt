package com.flog4.app.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flog4.app.ai.GpuAiInferenceEngine
import com.flog4.app.data.AirportDatabase
import com.flog4.app.data.FlightDao
import com.flog4.app.data.FlightRecord
import com.flog4.app.data.FlightStatus
import com.flog4.app.service.SimulatedFlight
import com.flog4.app.service.SpeedAndTelemetryAnalyzer
import com.flog4.app.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: FlightDao,
    private val gpuAiEngine: GpuAiInferenceEngine
) : ViewModel() {

    val flights: StateFlow<List<FlightRecord>> = dao.getAllFlights()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isGeneratingDemo = MutableStateFlow(false)
    val isGeneratingDemo: StateFlow<Boolean> = _isGeneratingDemo

    fun deleteFlight(flight: FlightRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteFlight(flight)
        }
    }

    fun createInstantDemoFlight(onCreated: (Long) -> Unit) {
        if (_isGeneratingDemo.value) return
        viewModelScope.launch {
            _isGeneratingDemo.value = true
            try {
                val flightId = withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    val dateStr = TimeUtils.formatDate(now)
                    val zh = com.flog4.app.ui.i18n.AppLocaleManager.isChinese
                    val initial = FlightRecord(
                        flightNumber = "CA1501",
                        airline = "CA",
                        airlineName = if (zh) "中国国际航空 (Air China)" else "Air China (中国国际航空)",
                        origin = "PEK",
                        originName = if (zh) "北京首都国际机场" else "Beijing Capital Intl",
                        destination = "SHA",
                        destinationName = if (zh) "上海虹桥国际机场" else "Shanghai Hongqiao Intl",
                        date = dateStr,
                        seatNumber = "12A",
                        passengerName = "LIN YUHANG",
                        cabinClass = if (zh) "Business 公务舱" else "Business Class",
                        boardingGate = "C32",
                        aircraftType = "Airbus A350-900",
                        registration = "B-308M",
                        maxPassengers = 314,
                        status = FlightStatus.COMPLETED,
                        createdAt = now
                    )
                    val id = dao.insertFlight(initial)

                    val origAirport = AirportDatabase.getByCode(context, "PEK")
                    val destAirport = AirportDatabase.getByCode(context, "SHA")
                    val points = SimulatedFlight.generateCompleteFlightData(
                        flightId = id,
                        originAirport = origAirport,
                        destAirport = destAirport,
                        endTimeMs = now
                    )
                    dao.insertSensorDataBatch(points)

                    val analyzer = SpeedAndTelemetryAnalyzer()
                    val analyzed = analyzer.analyzeCompleteFlight(
                        context = context,
                        data = points,
                        originIata = "PEK",
                        destinationIata = "SHA",
                        fallbackStartMs = now - 6900_000L
                    )

                    val populated = initial.copy(
                        id = id,
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
                        departureRunway = analyzed.departureRunway.ifBlank { "RWY 36R" },
                        arrivalRunway = analyzed.arrivalRunway.ifBlank { "RWY 18L" },
                        departureTerminal = analyzed.departureTerminal.ifBlank { "T3" },
                        arrivalTerminal = analyzed.arrivalTerminal.ifBlank { "T2" },
                        maxAltitudeM = analyzed.maxAltitudeM,
                        maxSpeedKnots = analyzed.maxSpeedKnots,
                        avgSpeedKnots = analyzed.avgSpeedKnots,
                        minCabinPressureHpa = analyzed.minCabinPressureHpa,
                        maxCabinPressureHpa = analyzed.maxCabinPressureHpa,
                        maxPitchDeg = analyzed.maxPitchDeg,
                        maxRollDeg = analyzed.maxRollDeg,
                        touchdownG = analyzed.touchdownG,
                        maxGForce = analyzed.maxGForce,
                        turbulenceIndex = analyzed.turbulenceIndex
                    )

                    val aiResult = gpuAiEngine.generateFlightReport(
                        flight = populated,
                        sensorData = points,
                        preferChinese = zh
                    )

                    dao.updateFlight(
                        populated.copy(
                            aiReport = aiResult.reportText,
                            aiGpuInfo = aiResult.gpuDeviceInfo,
                            aiScore = aiResult.overallScore
                        )
                    )
                    id
                }
                onCreated(flightId)
            } finally {
                _isGeneratingDemo.value = false
            }
        }
    }
}
