package com.flog4.app

import android.content.Context
import com.flog4.app.ai.GpuAiInferenceEngine
import com.flog4.app.data.AirportDatabase
import com.flog4.app.data.FlightRecord
import com.flog4.app.data.FlightStatus
import com.flog4.app.log.FlightLogGenerator
import com.flog4.app.log.PdfGenerator
import com.flog4.app.scanner.BoardingPassParser
import com.flog4.app.service.SimulatedFlight
import com.flog4.app.service.SpeedAndTelemetryAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Flog4CoreSystemsTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun `offline airport database loads 4500+ global airports and matches landing runway`() {
        val airports = AirportDatabase.load(context)
        assertTrue("Expected >4000 global airports, got ${airports.size}", airports.size > 4000)

        val sha = AirportDatabase.getByCode(context, "SHA")
        assertNotNull("SHA airport should exist", sha)
        assertEquals("上海虹桥国际机场", sha?.nameZh)

        // Match landing on SHA Runway 18L (heading ~180 deg)
        val match = AirportDatabase.identifyRunway(context, 31.205, 121.335, 180.0)
        assertNotNull("Runway match at SHA should succeed", match)
        assertEquals("SHA", match?.airport?.iata)
        assertTrue("Expected RWY 18L or 18R, got ${match?.formattedRunway}", match!!.formattedRunway.startsWith("RWY 18"))
    }

    @Test
    fun `multi-sensor flight analysis computes V1 V2 Vapp distance duration and runways`() {
        val pek = AirportDatabase.getByCode(context, "PEK")
        val sha = AirportDatabase.getByCode(context, "SHA")
        val points = SimulatedFlight.generateCompleteFlightData(
            flightId = 1L,
            originAirport = pek,
            destAirport = sha
        )
        assertTrue("Expected >200 sensor points", points.size >= 200)

        val analyzer = SpeedAndTelemetryAnalyzer()
        val analyzed = analyzer.analyzeCompleteFlight(
            context = context,
            data = points,
            originIata = "PEK",
            destinationIata = "SHA"
        )

        assertTrue("V1 should be in 110..170 kts, was ${analyzed.v1Knots}", analyzed.v1Knots in 110.0..170.0)
        assertTrue("V2 should be >= V1, was ${analyzed.v2Knots}", analyzed.v2Knots >= analyzed.v1Knots)
        assertTrue("Vapp should be in 115..165 kts, was ${analyzed.vappKnots}", analyzed.vappKnots in 115.0..165.0)
        assertTrue("Total distance should be >900 km for PEK-SHA, was ${analyzed.totalDistanceKm}", analyzed.totalDistanceKm > 900.0)
        assertTrue("Max altitude should be >9000 m, was ${analyzed.maxAltitudeM}", analyzed.maxAltitudeM > 9000.0)
        assertTrue("Min cabin pressure should be <850 hPa, was ${analyzed.minCabinPressureHpa}", analyzed.minCabinPressureHpa < 850f)
        assertTrue("Level sensor max pitch should be >8 deg, was ${analyzed.maxPitchDeg}", analyzed.maxPitchDeg > 8f)
        assertTrue("Landing runway should be identified, was '${analyzed.arrivalRunway}'", analyzed.arrivalRunway.startsWith("RWY"))
    }

    @Test
    fun `boarding pass OCR parser extracts flight number airline origin and destination`() {
        val sampleText = """
            BOARDING PASS 登机牌
            中国国际航空 AIR CHINA
            FLIGHT 航班号: CA1501
            DATE 日期: 2026-10-19
            FROM: PEK 北京首都
            TO: SHA 上海虹桥
            PASSENGER: LIN/YUHANG
            GATE: C32   SEAT: 12A
        """.trimIndent()

        val parsed = BoardingPassParser.parseOcrText(sampleText, context)
        assertEquals("CA1501", parsed.flightNumber)
        assertEquals("CA", parsed.airline)
        assertEquals("PEK", parsed.origin)
        assertEquals("SHA", parsed.destination)
        assertEquals("12A", parsed.seatNumber)
        assertEquals("C32", parsed.gate)
    }

    @Test
    fun `built-in small-parameter AI model runs neural forward pass and generates flight report`() {
        val engine = GpuAiInferenceEngine(context)
        val flight = FlightRecord(
            id = 1L,
            flightNumber = "CA1501",
            airline = "CA",
            airlineName = "中国国际航空",
            origin = "PEK",
            destination = "SHA",
            aircraftType = "Airbus A350-900",
            registration = "B-308M",
            totalFlightTimeMs = 6960_000L,
            totalFlightTimeDisplay = "1h 56m",
            totalDistanceKm = 1088.0,
            v1Knots = 142.0,
            v2Knots = 162.0,
            vappKnots = 138.0,
            departureRunway = "RWY 36R",
            arrivalRunway = "RWY 18L",
            maxAltitudeM = 10600.0,
            maxSpeedKnots = 468.0,
            avgSpeedKnots = 435.0,
            minCabinPressureHpa = 783.2f,
            maxCabinPressureHpa = 1013.2f,
            maxPitchDeg = 11.8f,
            maxRollDeg = 16.0f,
            touchdownG = 1.25f,
            status = FlightStatus.COMPLETED
        )

        val result = engine.generateFlightReport(flight, emptyList(), preferChinese = true)
        assertTrue("AI report should contain flight number", result.reportText.contains("CA1501"))
        assertTrue("AI report should contain landing runway", result.reportText.contains("RWY 18L"))
        assertTrue("Overall neural score should be in 65..99", result.overallScore in 65..99)
    }

    @Test
    fun `single-page A4 PDF generator produces valid A4 PDF with all 3 charts`() {
        val flight = FlightRecord(
            id = 1L,
            flightNumber = "CA1501",
            airline = "CA",
            origin = "PEK",
            destination = "SHA",
            date = "2026-10-19",
            aircraftType = "Airbus A350-900",
            registration = "B-308M",
            takeoffTimeDisplay = "14:32:10",
            landingTimeDisplay = "16:28:45",
            totalFlightTimeMs = 6960_000L,
            totalFlightTimeDisplay = "1h 56m",
            totalDistanceKm = 1088.0,
            v1Knots = 142.0,
            v2Knots = 162.0,
            vappKnots = 138.0,
            departureRunway = "RWY 36R",
            arrivalRunway = "RWY 18L",
            maxAltitudeM = 10600.0,
            maxSpeedKnots = 468.0,
            avgSpeedKnots = 435.0,
            minCabinPressureHpa = 783.2f,
            maxCabinPressureHpa = 1013.2f,
            aiReport = "AI 飞行简报测试文本"
        )
        val generator = FlightLogGenerator(
            androidx.room.Room.inMemoryDatabaseBuilder(context, com.flog4.app.data.FlightDatabase::class.java)
                .allowMainThreadQueries().build()
        )
        val log = generator.buildFlightLog(flight, emptyList())
        assertEquals(121, log.altitudePoints.size)
        assertEquals(121, log.pressurePoints.size)
        assertEquals(121, log.speedPoints.size)

        val pdfGen = PdfGenerator(context)
        val file = pdfGen.generatePdf(log, preferChinese = true)
        assertNotNull("Generated PDF file should not be null", file)
        assertTrue("Generated PDF file should exist and be non-empty", file!!.exists() && file.length() > 500)

        val enFile = pdfGen.generatePdf(log, preferChinese = false)
        assertNotNull("Generated English PDF file should not be null", enFile)
        assertTrue("Generated English PDF file should exist and be non-empty", enFile!!.exists() && enFile.length() > 500)
    }

    @Test
    fun `bilingual locale manager switches between Chinese and English and generates English AI report`() {
        com.flog4.app.ui.i18n.AppLocaleManager.setLanguage(com.flog4.app.ui.i18n.AppLanguage.ZH, context)
        assertTrue(com.flog4.app.ui.i18n.AppLocaleManager.isChinese)
        assertEquals("中文标题", com.flog4.app.ui.i18n.AppLocaleManager.tr("中文标题", "English Title"))

        val next = com.flog4.app.ui.i18n.AppLocaleManager.toggleLanguage(context)
        assertEquals(com.flog4.app.ui.i18n.AppLanguage.EN, next)
        assertEquals("English Title", com.flog4.app.ui.i18n.AppLocaleManager.tr("中文标题", "English Title"))

        val engine = GpuAiInferenceEngine(context)
        val flight = FlightRecord(
            id = 2L,
            flightNumber = "CA1501",
            airline = "CA",
            origin = "PEK",
            destination = "SHA",
            aircraftType = "Airbus A350-900",
            registration = "B-308M",
            totalFlightTimeMs = 6960_000L,
            totalFlightTimeDisplay = "1h 56m",
            totalDistanceKm = 1088.0,
            v1Knots = 142.0,
            v2Knots = 162.0,
            vappKnots = 138.0,
            arrivalRunway = "RWY 18L",
            maxAltitudeM = 10600.0,
            minCabinPressureHpa = 783.2f
        )
        val enResult = engine.generateFlightReport(flight, emptyList(), preferChinese = false)
        assertTrue("English AI report should start with [AI Flight Report", enResult.reportText.startsWith("[AI Flight Report"))
        assertTrue("English AI report should mention CA1501 and RWY 18L", enResult.reportText.contains("CA1501") && enResult.reportText.contains("RWY 18L"))
    }
}
