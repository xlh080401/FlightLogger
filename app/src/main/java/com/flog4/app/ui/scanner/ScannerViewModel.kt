package com.flog4.app.ui.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flog4.app.ai.GpuAiInferenceEngine
import com.flog4.app.data.AircraftDatabase
import com.flog4.app.data.AircraftInfo
import com.flog4.app.data.AirlineDatabase
import com.flog4.app.data.Airport
import com.flog4.app.data.AirportDatabase
import com.flog4.app.data.FlightDao
import com.flog4.app.data.FlightRecord
import com.flog4.app.data.FlightStatus
import com.flog4.app.scanner.BoardingPassParser
import com.flog4.app.scanner.ParsedBoardingPass
import com.flog4.app.service.SimulatedFlight
import com.flog4.app.service.SpeedAndTelemetryAnalyzer
import com.flog4.app.util.TimeUtils
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SetupFormState(
    val flightNumber: String = "",
    val airline: String = "",
    val airlineName: String = "",
    val origin: String = "",
    val originName: String = "",
    val destination: String = "",
    val destinationName: String = "",
    val date: String = TimeUtils.formatDate(System.currentTimeMillis()),
    val seatNumber: String = "",
    val boardingGate: String = "",
    val cabinClass: String = "Economy 经济舱",
    val passengerName: String = "",
    // Guided Manual Input fields
    val aircraftType: String = "",
    val registration: String = "",
    val maxPassengers: Int = 180
)

@HiltViewModel
class ScannerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: FlightDao,
    private val gpuAiEngine: GpuAiInferenceEngine
) : ViewModel() {

    private val _form = MutableStateFlow(SetupFormState())
    val form: StateFlow<SetupFormState> = _form

    private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
    val previewBitmap: StateFlow<Bitmap?> = _previewBitmap

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _ocrStatusMessage = MutableStateFlow<String?>(null)
    val ocrStatusMessage: StateFlow<String?> = _ocrStatusMessage

    private val _aircraftSuggestions = MutableStateFlow<List<AircraftInfo>>(emptyList())
    val aircraftSuggestions: StateFlow<List<AircraftInfo>> = _aircraftSuggestions

    private val _airportSuggestions = MutableStateFlow<List<Airport>>(emptyList())
    val airportSuggestions: StateFlow<List<Airport>> = _airportSuggestions

    private var samplePresetCounter = 0

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _aircraftSuggestions.value = AircraftDatabase.load(context)
        }
    }

    fun updateFlightNumber(value: String) {
        val upper = value.uppercase().trim()
        val iata = upper.take(2).takeIf { it.length == 2 && it.any { c -> c.isLetter() } } ?: _form.value.airline
        val airlineInfo = AirlineDatabase.getByIata(context, iata)
        val zh = com.flog4.app.ui.i18n.AppLocaleManager.isChinese
        _form.value = _form.value.copy(
            flightNumber = upper,
            airline = airlineInfo?.iata ?: iata,
            airlineName = airlineInfo?.displayName(zh) ?: _form.value.airlineName
        )
    }

    fun updateAirline(code: String, name: String) {
        _form.value = _form.value.copy(airline = code.uppercase().trim(), airlineName = name)
    }

    fun updateOrigin(iataOrQuery: String) {
        val upper = iataOrQuery.uppercase().trim()
        val exact = AirportDatabase.getByCode(context, upper)
        val zh = com.flog4.app.ui.i18n.AppLocaleManager.isChinese
        _form.value = _form.value.copy(
            origin = upper,
            originName = exact?.shortCityOrName(zh) ?: _form.value.originName
        )
        viewModelScope.launch(Dispatchers.IO) {
            _airportSuggestions.value = AirportDatabase.findByTextQuery(context, iataOrQuery, 10)
        }
    }

    fun selectOriginAirport(airport: Airport) {
        val zh = com.flog4.app.ui.i18n.AppLocaleManager.isChinese
        _form.value = _form.value.copy(
            origin = airport.iata.ifBlank { airport.icao },
            originName = airport.shortCityOrName(zh)
        )
    }

    fun updateDestination(iataOrQuery: String) {
        val upper = iataOrQuery.uppercase().trim()
        val exact = AirportDatabase.getByCode(context, upper)
        val zh = com.flog4.app.ui.i18n.AppLocaleManager.isChinese
        _form.value = _form.value.copy(
            destination = upper,
            destinationName = exact?.shortCityOrName(zh) ?: _form.value.destinationName
        )
        viewModelScope.launch(Dispatchers.IO) {
            _airportSuggestions.value = AirportDatabase.findByTextQuery(context, iataOrQuery, 10)
        }
    }

    fun selectDestinationAirport(airport: Airport) {
        val zh = com.flog4.app.ui.i18n.AppLocaleManager.isChinese
        _form.value = _form.value.copy(
            destination = airport.iata.ifBlank { airport.icao },
            destinationName = airport.shortCityOrName(zh)
        )
    }

    fun updateDate(value: String) {
        _form.value = _form.value.copy(date = value)
    }

    fun updateSeatAndGate(seat: String, gate: String, cabin: String, paxName: String) {
        _form.value = _form.value.copy(
            seatNumber = seat.uppercase(),
            boardingGate = gate.uppercase(),
            cabinClass = cabin,
            passengerName = paxName
        )
    }

    fun updateAircraftType(value: String) {
        val maxPax = AircraftDatabase.getMaxPassengers(context, value)
        _form.value = _form.value.copy(
            aircraftType = value,
            maxPassengers = maxPax
        )
        viewModelScope.launch(Dispatchers.IO) {
            _aircraftSuggestions.value = AircraftDatabase.searchAircraft(context, value)
        }
    }

    fun updateRegistration(value: String) {
        _form.value = _form.value.copy(registration = value.uppercase().trim())
    }

    /**
     * Loads an image URI selected from the user's photo gallery and runs offline ML Kit OCR + Barcode scanning.
     */
    fun processImageUri(uri: Uri) {
        viewModelScope.launch {
            _isScanning.value = true
            _ocrStatusMessage.value = com.flog4.app.ui.i18n.AppLocaleManager.tr(
                "正在通过端侧离线 OCR 识别登机牌图像…",
                "Scanning boarding pass image via on-device offline OCR…"
            )
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
                if (bitmap != null) {
                    _previewBitmap.value = bitmap
                    runOfflineOcrAndBarcodeOnBitmap(bitmap)
                } else {
                    _ocrStatusMessage.value = com.flog4.app.ui.i18n.AppLocaleManager.tr(
                        "无法解码所选图片，请重试或手工填写。",
                        "Could not decode image; please retry or fill in manually."
                    )
                }
            } catch (e: Exception) {
                _ocrStatusMessage.value = com.flog4.app.ui.i18n.AppLocaleManager.tr(
                    "图片读取异常：${e.message}",
                    "Image read error: ${e.message}"
                )
            } finally {
                _isScanning.value = false
            }
        }
    }

    /**
     * Processes a Bitmap (from camera capture or sample boarding pass generator) through ML Kit OCR.
     */
    fun processBitmap(bitmap: Bitmap) {
        viewModelScope.launch {
            _isScanning.value = true
            _previewBitmap.value = bitmap
            _ocrStatusMessage.value = com.flog4.app.ui.i18n.AppLocaleManager.tr(
                "正在通过端侧离线 OCR 引擎提取航班号、航司、出发地与目的地…",
                "Extracting flight number, airline, origin, and destination via offline OCR…"
            )
            try {
                runOfflineOcrAndBarcodeOnBitmap(bitmap)
            } finally {
                _isScanning.value = false
            }
        }
    }

    /**
     * Generates a sample boarding pass image and runs the offline OCR pipeline on it.
     */
    fun generateAndScanSampleBoardingPass() {
        val preset = samplePresetCounter++
        val bmp = BoardingPassParser.createSampleBoardingPassBitmap(preset)
        processBitmap(bmp)
    }

    private suspend fun runOfflineOcrAndBarcodeOnBitmap(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        var parsed: ParsedBoardingPass? = null

        // 1. Try BCBP barcode scanning first (PDF417 / Aztec / QR on boarding pass)
        runCatching {
            val scanner = BarcodeScanning.getClient()
            val barcodes = scanner.process(inputImage).await()
            for (barcode in barcodes) {
                val raw = barcode.rawValue ?: continue
                val bcbp = BoardingPassParser.parseBCBP(raw, context)
                if (bcbp != null && bcbp.hasAnyField()) {
                    parsed = bcbp
                    break
                }
            }
        }

        // 2. Run Chinese + Latin ML Kit Text Recognition
        val chineseRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        val ocrTextBuilder = StringBuilder()
        runCatching {
            val zhResult = chineseRecognizer.process(inputImage).await()
            if (zhResult.text.isNotBlank()) ocrTextBuilder.appendLine(zhResult.text)
        }
        runCatching {
            val enResult = latinRecognizer.process(inputImage).await()
            if (enResult.text.isNotBlank()) ocrTextBuilder.appendLine(enResult.text)
        }

        val combinedText = ocrTextBuilder.toString().trim()
        if (combinedText.isNotEmpty()) {
            val textParsed = BoardingPassParser.parseOcrText(combinedText, context)
            parsed = if (parsed == null) {
                textParsed
            } else {
                parsed!!.copy(
                    flightNumber = parsed!!.flightNumber.ifBlank { textParsed.flightNumber },
                    airline = parsed!!.airline.ifBlank { textParsed.airline },
                    airlineName = parsed!!.airlineName.ifBlank { textParsed.airlineName },
                    origin = parsed!!.origin.ifBlank { textParsed.origin },
                    originName = parsed!!.originName.ifBlank { textParsed.originName },
                    destination = parsed!!.destination.ifBlank { textParsed.destination },
                    destinationName = parsed!!.destinationName.ifBlank { textParsed.destinationName },
                    date = parsed!!.date.ifBlank { textParsed.date },
                    seatNumber = parsed!!.seatNumber.ifBlank { textParsed.seatNumber },
                    gate = parsed!!.gate.ifBlank { textParsed.gate }
                )
            }
        }

        val finalParsed = parsed
        if (finalParsed != null && finalParsed.hasAnyField()) {
            applyParsedBoardingPass(finalParsed)
            _ocrStatusMessage.value = com.flog4.app.ui.i18n.AppLocaleManager.tr(
                "✓ 登机牌识别成功：已提取航班 ${finalParsed.flightNumber} (${finalParsed.origin} → ${finalParsed.destination})，请继续在下方核对并手工填写机型与飞机注册号！",
                "✓ Boarding pass OCR complete: Extracted ${finalParsed.flightNumber} (${finalParsed.origin} → ${finalParsed.destination}). Please enter Aircraft Type & Registration in Step 2 below!"
            )
        } else {
            _ocrStatusMessage.value = com.flog4.app.ui.i18n.AppLocaleManager.tr(
                "已完成扫描，请核对上方提取字段并填写下方机型与注册号。",
                "Scan complete. Please review extracted fields and enter Aircraft Type & Registration below."
            )
        }
    }

    private fun applyParsedBoardingPass(bp: ParsedBoardingPass) {
        val current = _form.value
        val zh = com.flog4.app.ui.i18n.AppLocaleManager.isChinese
        val origAirport = AirportDatabase.getByCode(context, bp.origin)
        val destAirport = AirportDatabase.getByCode(context, bp.destination)
        val airlineObj = AirlineDatabase.getByIata(context, bp.airline)
        _form.value = current.copy(
            flightNumber = bp.flightNumber.ifBlank { current.flightNumber },
            airline = bp.airline.ifBlank { current.airline },
            airlineName = airlineObj?.displayName(zh)?.ifBlank { bp.airlineName } ?: bp.airlineName.ifBlank { current.airlineName },
            origin = bp.origin.ifBlank { current.origin },
            originName = origAirport?.shortCityOrName(zh)?.ifBlank { bp.originName } ?: bp.originName.ifBlank { current.originName },
            destination = bp.destination.ifBlank { current.destination },
            destinationName = destAirport?.shortCityOrName(zh)?.ifBlank { bp.destinationName } ?: bp.destinationName.ifBlank { current.destinationName },
            date = bp.date.ifBlank { current.date },
            seatNumber = bp.seatNumber.ifBlank { current.seatNumber },
            boardingGate = bp.gate.ifBlank { current.boardingGate },
            cabinClass = bp.cabinClass.ifBlank { current.cabinClass },
            passengerName = bp.passengerName.ifBlank { current.passengerName }
        )
    }

    /**
     * Saves the flight setup and proceeds to Live Sensor Recording (`RecordingScreen`).
     */
    fun saveAndStartLiveRecording(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val s = _form.value
            val id = withContext(Dispatchers.IO) {
                dao.insertFlight(
                    FlightRecord(
                        flightNumber = s.flightNumber.ifBlank { "CA1501" },
                        airline = s.airline.ifBlank { s.flightNumber.take(2).ifBlank { "CA" } },
                        airlineName = s.airlineName,
                        origin = s.origin.ifBlank { "PEK" },
                        originName = s.originName,
                        destination = s.destination.ifBlank { "SHA" },
                        destinationName = s.destinationName,
                        date = s.date.ifBlank { TimeUtils.formatDate(System.currentTimeMillis()) },
                        seatNumber = s.seatNumber,
                        passengerName = s.passengerName,
                        cabinClass = s.cabinClass,
                        boardingGate = s.boardingGate,
                        aircraftType = s.aircraftType.ifBlank { "Airbus A320neo" },
                        registration = s.registration.ifBlank { "B-308M" },
                        maxPassengers = s.maxPassengers,
                        status = FlightStatus.SETUP
                    )
                )
            }
            onCreated(id)
        }
    }

    /**
     * Saves the OCR + manually entered Aircraft/Registration info, simulates the complete multi-sensor
     * flight along the selected Origin -> Destination airport runways, runs GPU AI inference, and opens the log.
     */
    fun saveAndGenerateSimulatedFlightLog(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            _isScanning.value = true
            try {
                val s = _form.value
                val zh = com.flog4.app.ui.i18n.AppLocaleManager.isChinese
                val id = withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    val originCode = s.origin.ifBlank { "PEK" }
                    val destCode = s.destination.ifBlank { "SHA" }
                    val origAirport = AirportDatabase.getByCode(context, originCode)
                    val destAirport = AirportDatabase.getByCode(context, destCode)

                    val initial = FlightRecord(
                        flightNumber = s.flightNumber.ifBlank { "CA1501" },
                        airline = s.airline.ifBlank { s.flightNumber.take(2).ifBlank { "CA" } },
                        airlineName = s.airlineName.ifBlank {
                            AirlineDatabase.getByIata(context, s.airline.ifBlank { "CA" })?.displayName(zh) ?: ""
                        },
                        origin = originCode,
                        originName = s.originName.ifBlank { origAirport?.shortCityOrName(zh) ?: "" },
                        destination = destCode,
                        destinationName = s.destinationName.ifBlank { destAirport?.shortCityOrName(zh) ?: "" },
                        date = s.date.ifBlank { TimeUtils.formatDate(now) },
                        seatNumber = s.seatNumber.ifBlank { "12A" },
                        passengerName = s.passengerName,
                        cabinClass = s.cabinClass,
                        boardingGate = s.boardingGate.ifBlank { "C32" },
                        aircraftType = s.aircraftType.ifBlank { "Airbus A350-900" },
                        registration = s.registration.ifBlank { "B-308M" },
                        maxPassengers = s.maxPassengers,
                        status = FlightStatus.COMPLETED,
                        createdAt = now
                    )
                    val flightId = dao.insertFlight(initial)

                    val points = SimulatedFlight.generateCompleteFlightData(
                        flightId = flightId,
                        originAirport = origAirport,
                        destAirport = destAirport,
                        endTimeMs = now
                    )
                    dao.insertSensorDataBatch(points)

                    val analyzer = SpeedAndTelemetryAnalyzer()
                    val analyzed = analyzer.analyzeCompleteFlight(
                        context = context,
                        data = points,
                        originIata = originCode,
                        destinationIata = destCode,
                        fallbackStartMs = now - 6600_000L
                    )

                    val populated = initial.copy(
                        id = flightId,
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
                    flightId
                }
                onCreated(id)
            } finally {
                _isScanning.value = false
            }
        }
    }
}
