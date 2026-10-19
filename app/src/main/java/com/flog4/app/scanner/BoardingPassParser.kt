package com.flog4.app.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.flog4.app.data.AirlineDatabase
import com.flog4.app.data.AirportDatabase
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class ParsedBoardingPass(
    val passengerName: String = "",
    val flightNumber: String = "",
    val airline: String = "",
    val airlineName: String = "",
    val origin: String = "",
    val originName: String = "",
    val destination: String = "",
    val destinationName: String = "",
    val date: String = "",
    val seatNumber: String = "",
    val gate: String = "",
    val boardingTime: String = "",
    val cabinClass: String = "Economy"
) {
    fun hasAnyField(): Boolean = listOf(
        passengerName, flightNumber, airline, origin, destination, date, seatNumber, gate
    ).any { it.isNotBlank() }
}

/**
 * Offline Boarding Pass OCR & IATA BCBP Barcode Parser.
 * Extracts flight number, airline, origin, destination, date, seat, gate, and passenger name
 * from Chinese & English boarding pass text or BCBP strings, cross-referencing the offline
 * airport and airline databases.
 */
object BoardingPassParser {

    private val airportStopWords = setOf(
        "THE", "AND", "FOR", "NOT", "ALL", "ANY", "NEW", "OLD", "ONE", "TWO", "SIX", "TEN",
        "MR", "MRS", "MS", "MST", "MISS", "DR", "PAS", "SEQ", "ETK", "PNR", "REF", "TKT",
        "GATE", "SEAT", "CLASS", "DATE", "TIME", "FLIGHT", "FLT", "FROM", "TO", "VIA", "STOP",
        "TERMINAL", "TERM", "ZONE", "GROUP", "GRP", "BOARD", "BOARDING", "PASS", "PASSENGER",
        "NAME", "SMOKING", "NONSMOKING", "ECONOMY", "BUSINESS", "FIRST", "PREMIUM", "INFANT",
        "ADULT", "CHILD", "BAGGAGE", "BAG", "EXIT", "ROW", "AISLE", "WINDOW", "TOTAL",
        "WELCOME", "THANK", "YOU", "SERVICE", "AIRLINE", "AIRWAYS", "AIRPORT", "INT", "DOM",
        "DOMESTIC", "TRANSFER", "ARRIVAL", "DEPARTURE", "ARR", "DEP", "CODE", "NO", "ETKT",
        "VIP", "CIP", "AIR", "CHN", "USA", "end"
    )

    private val nameStopWords = setOf(
        "PASS", "BOARDING", "BOARD", "PASSENGER", "GATE", "SEAT", "CLASS", "DATE", "TIME",
        "FLIGHT", "FROM", "TO", "THE", "AND", "TICKET", "BAGGAGE", "TERMINAL", "ARRIVAL",
        "DEPARTURE", "ECONOMY", "BUSINESS", "FIRST", "SMOKING", "NONSMOKING", "CARRIER",
        "SEQUENCE", "SEQ", "ETKT", "ELECTRONIC", "WELCOME", "THANK", "YOU", "AIRLINE",
        "AIR", "CHINA", "EASTERN", "SOUTHERN", "HAINAN"
    )

    fun parseBCBP(data: String, context: Context? = null): ParsedBoardingPass? {
        if (data.length < 60 || data[0] != 'M') return null
        return try {
            val legCount = data[1].digitToIntOrNull() ?: return null
            if (legCount < 1) return null
            val name = data.substring(2, 22).trim()
            val fromCity = data.substring(30, 33).trim()
            val toCity = data.substring(33, 36).trim()
            val airline = data.substring(36, 39).trim().take(2)
            val flightNum = data.substring(39, 44).trim()
            val julianDate = data.substring(44, 47)
            val compartment = data[47]
            val seat = data.substring(48, 52).trim()

            val fullFlightNum = airline + flightNum.trimStart('0')
            val dateStr = julianDateToString(julianDate)
            val cabin = when (compartment) {
                'F', 'A' -> "First 头等舱"
                'C', 'J', 'D', 'I', 'Z' -> "Business 公务舱"
                'W', 'E' -> "Premium Economy 超经舱"
                else -> "Economy 经济舱"
            }

            val airlineInfo = context?.let { AirlineDatabase.getByIata(it, airline) }
            val origAirport = context?.let { AirportDatabase.getByCode(it, fromCity) }
            val destAirport = context?.let { AirportDatabase.getByCode(it, toCity) }

            ParsedBoardingPass(
                passengerName = formatName(name),
                flightNumber = fullFlightNum,
                airline = airline,
                airlineName = airlineInfo?.displayName(true) ?: airline,
                origin = fromCity,
                originName = origAirport?.shortCityOrName(true) ?: "",
                destination = toCity,
                destinationName = destAirport?.shortCityOrName(true) ?: "",
                date = dateStr,
                seatNumber = formatSeat(seat),
                cabinClass = cabin
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parseOcrText(text: String, context: Context? = null): ParsedBoardingPass {
        val rawLines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val lines = rawLines.map { it.uppercase() }
        val allText = lines.joinToString(" ")

        val flightNum = findFlightNumber(allText)
        val airlineIata = flightNum.take(2).takeIf { it.length == 2 } ?: ""
        val airlineFromText = context?.let {
            AirlineDatabase.getByIata(it, airlineIata) ?: AirlineDatabase.matchAirlineInText(it, text)
        }
        val finalAirlineCode = airlineFromText?.iata ?: airlineIata
        val finalAirlineName = airlineFromText?.displayName(true) ?: finalAirlineCode

        val seatNum = Regex("(?:SEAT|座位|座號|座位号)\\s*[:：]?\\s*(\\d{1,3}\\s?[A-Z])\\b")
            .find(allText)?.groupValues?.get(1)?.replace(" ", "")
            ?: Regex("\\b(\\d{1,2}[A-K])\\b").find(allText)?.value
            ?: ""

        val gate = Regex("(?:GATE|登机口|闸口|登機門)\\s*[:：]?\\s*([A-Z]?\\d{1,3}[A-Z]?)")
            .find(allText)?.groupValues?.get(1) ?: ""

        var (origin, destination) = findRoute(allText, context)
        if ((origin.isBlank() || destination.isBlank()) && context != null) {
            val matchedAirports = AirportDatabase.matchAirportInText(context, text)
            if (origin.isBlank() && matchedAirports.isNotEmpty()) {
                origin = matchedAirports[0].iata
            }
            if (destination.isBlank() && matchedAirports.size >= 2) {
                destination = matchedAirports[1].iata
            }
        }

        val origAirport = context?.let { AirportDatabase.getByCode(it, origin) }
        val destAirport = context?.let { AirportDatabase.getByCode(it, destination) }

        val datePattern = Regex("\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}|\\d{1,2}\\s?[A-Z]{3}\\s?\\d{2,4}|\\d{2}[-/]\\d{2}[-/]\\d{2,4}")
        val dateRaw = datePattern.find(allText)?.value ?: ""
        val date = dateRaw.replace("年", "-").replace("月", "-").replace("日", "")

        val boardingTime = Regex("(?:BOARDING|登机|登機|BOARD)\\s*(?:TIME|时间|時間)?\\s*[:：]?\\s*(\\d{1,2}:\\d{2})")
            .find(allText)?.groupValues?.get(1)
            ?: Regex("\\b(\\d{1,2}:\\d{2})\\b").find(allText)?.groupValues?.get(1) ?: ""

        val cabinClass = when {
            allText.contains("BUSINESS") || text.contains("公务舱") || text.contains("商务舱") -> "Business 公务舱"
            allText.contains("FIRST") || text.contains("头等舱") -> "First 头等舱"
            else -> "Economy 经济舱"
        }

        val name = findPassengerName(rawLines)

        return ParsedBoardingPass(
            passengerName = name,
            flightNumber = flightNum,
            airline = finalAirlineCode,
            airlineName = finalAirlineName,
            origin = origin,
            originName = origAirport?.shortCityOrName(true) ?: "",
            destination = destination,
            destinationName = destAirport?.shortCityOrName(true) ?: "",
            date = date,
            seatNumber = seatNum,
            gate = gate,
            boardingTime = boardingTime,
            cabinClass = cabinClass
        )
    }

    private fun findFlightNumber(allText: String): String {
        val labelled = Regex(
            "(?:FLIGHT|FLT|航班|航班号|航班號)\\s*(?:NO|NUMBER|号|號)?\\s*[:：]?\\s*([A-Z0-9]{2})\\s?(\\d{2,4})\\b"
        ).find(allText)
        if (labelled != null) {
            val code = labelled.groupValues[1]
            if (code.any { it.isLetter() }) return code + labelled.groupValues[2]
        }
        val generic = Regex("(?<![A-Z0-9])([A-Z]{2}|[A-Z]\\d|\\d[A-Z])\\s?(\\d{3,4})(?![A-Z0-9])")
            .find(allText)
        if (generic != null) {
            val code = generic.groupValues[1]
            val number = generic.groupValues[2]
            val candidate = code + number
            if (code !in airportStopWords && candidate !in airportStopWords) return candidate
        }
        return ""
    }

    private fun findRoute(allText: String, context: Context?): Pair<String, String> {
        // Strip passenger name tokens like LIN/YUHANG so 3-letter surnames (LIN, SUN, TAN, etc.) are not mistaken for IATA airport codes
        val textWithoutSlashNames = allText.replace(Regex("\\b[A-Z]{2,20}\\s*/\\s*[A-Z]{2,20}\\b"), " ")

        val fromCode = Regex("(?:FROM|出发地|始发地|从|從|出发|始发)(?:\\s*(?:出发地|FROM))?\\s*[:：]?\\s*([A-Z]{3})\\b")
            .findAll(textWithoutSlashNames)
            .map { it.groupValues[1] }
            .firstOrNull { it !in airportStopWords }

        val toCode = Regex("(?:TO|目的地|到达地|至|到|到达|目的)(?:\\s*(?:目的地|TO))?\\s*[:：]?\\s*([A-Z]{3})\\b")
            .findAll(textWithoutSlashNames)
            .map { it.groupValues[1] }
            .firstOrNull { it !in airportStopWords && it != fromCode }

        if (!fromCode.isNullOrBlank() && !toCode.isNullOrBlank()) {
            return fromCode to toCode
        }

        Regex("\\b([A-Z]{3})\\s*(?:→|->|–|-|—|>|TO)\\s*([A-Z]{3})\\b")
            .find(textWithoutSlashNames)?.let {
                val c1 = it.groupValues[1]
                val c2 = it.groupValues[2]
                if (c1 !in airportStopWords && c2 !in airportStopWords) {
                    return c1 to c2
                }
            }
        val codes = Regex("\\b[A-Z]{3}\\b").findAll(textWithoutSlashNames)
            .map { it.value }
            .filter { code ->
                code !in airportStopWords &&
                    (context == null || AirportDatabase.getByCode(context, code) != null)
            }
            .distinct()
            .toList()
        val finalFrom = fromCode ?: codes.getOrNull(0).orEmpty()
        val finalTo = toCode ?: codes.firstOrNull { it != finalFrom }.orEmpty()
        return finalFrom to finalTo
    }

    private fun findPassengerName(rawLines: List<String>): String {
        for (line in rawLines) {
            val upper = line.uppercase()
            val match = Regex("\\b([A-Z]{2,20})\\s*/\\s*([A-Z]{2,20})\\b").find(upper) ?: continue
            val surname = match.groupValues[1]
            val given = match.groupValues[2]
            if (surname !in nameStopWords && given !in nameStopWords) {
                return formatName("$surname/$given")
            }
        }
        val fieldStop = "(?:FLIGHT|FLT|DATE|TIME|FROM|TO|GATE|SEAT|CLASS|BOARDING|TICKET|SEQ|ETKT|PNR|航班|日期|座位|登机)"
        for (line in rawLines) {
            val upper = line.uppercase()
            val label = Regex("(?:PASSENGER|PAX|NAME|旅客|乘客|姓名)\\s*[:：]?\\s*(.+)").find(upper) ?: continue
            val value = label.groupValues[1]
                .split(Regex("\\s{2,}|\\b$fieldStop\\b"))[0]
                .replace(Regex("[^A-Z ]"), " ")
                .trim()
            val words = value.split(Regex("\\s+")).filter { it.length > 1 }
            if (words.isNotEmpty() && words[0] !in nameStopWords) {
                return words.joinToString(" ")
            }
        }
        return ""
    }

    private fun formatName(raw: String): String {
        val parts = raw.split("/")
        return if (parts.size == 2) "${parts[1].trim()} ${parts[0].trim()}" else raw
    }

    private fun formatSeat(raw: String): String {
        val seat = raw.trim()
        if (seat.length < 2) return seat
        val digits = seat.takeWhile { it.isDigit() }
        val suffix = seat.drop(digits.length)
        if (digits.isEmpty() || suffix.isEmpty()) return seat
        return "${digits.trimStart('0').ifEmpty { "0" }}$suffix"
    }

    private fun julianDateToString(julian: String): String {
        return try {
            val dayOfYear = julian.toInt()
            val cal = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_YEAR, dayOfYear)
            }
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        } catch (_: Exception) {
            julian
        }
    }

    /**
     * Generates a realistic Boarding Pass image Bitmap so the user can test ML Kit OCR extraction
     * on-device even if they don't currently have a boarding pass photo in their gallery.
     */
    fun createSampleBoardingPassBitmap(presetIndex: Int = 0): Bitmap {
        val width = 1000
        val height = 460
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Card background
        canvas.drawColor(Color.parseColor("#F5F7FA"))
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawRoundRect(RectF(20f, 20f, 980f, 440f), 24f, 24f, cardPaint)

        // Top Airline Header Banner
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when (presetIndex % 3) {
                0 -> Color.parseColor("#0A2540")
                1 -> Color.parseColor("#B71C1C")
                else -> Color.parseColor("#004D40")
            }
        }
        canvas.drawRoundRect(RectF(20f, 20f, 980f, 105f), 24f, 24f, headerPaint)
        canvas.drawRect(20f, 70f, 980f, 105f, headerPaint)

        val whiteBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 32f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#607D8B")
            textSize = 22f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#102A43")
            textSize = 34f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        when (presetIndex % 3) {
            0 -> {
                canvas.drawText("BOARDING PASS · 登机牌   AIR CHINA 中国国际航空", 50f, 75f, whiteBold)
                canvas.drawText("FLIGHT 航班号", 50f, 155f, labelPaint)
                canvas.drawText("CA1501", 50f, 200f, valuePaint)

                canvas.drawText("DATE 日期", 300f, 155f, labelPaint)
                canvas.drawText("2026-10-19", 300f, 200f, valuePaint)

                canvas.drawText("CLASS 舱位", 620f, 155f, labelPaint)
                canvas.drawText("BUSINESS 公务舱", 620f, 200f, valuePaint)

                canvas.drawText("FROM 出发地", 50f, 265f, labelPaint)
                canvas.drawText("FROM: PEK 北京首都", 50f, 310f, valuePaint)

                canvas.drawText("TO 目的地", 480f, 265f, labelPaint)
                canvas.drawText("TO: SHA 上海虹桥", 480f, 310f, valuePaint)

                canvas.drawText("PASSENGER 旅客姓名", 50f, 370f, labelPaint)
                canvas.drawText("LIN/YUHANG", 50f, 412f, valuePaint)

                canvas.drawText("GATE 登机口", 420f, 370f, labelPaint)
                canvas.drawText("GATE: C32", 420f, 412f, valuePaint)

                canvas.drawText("SEAT 座位号", 660f, 370f, labelPaint)
                canvas.drawText("SEAT: 12A", 660f, 412f, valuePaint)
            }
            1 -> {
                canvas.drawText("BOARDING PASS · 登机牌   CHINA EASTERN 中国东方航空", 50f, 75f, whiteBold)
                canvas.drawText("FLIGHT 航班号", 50f, 155f, labelPaint)
                canvas.drawText("MU5105", 50f, 200f, valuePaint)

                canvas.drawText("DATE 日期", 300f, 155f, labelPaint)
                canvas.drawText("2026-10-19", 300f, 200f, valuePaint)

                canvas.drawText("CLASS 舱位", 620f, 155f, labelPaint)
                canvas.drawText("ECONOMY 经济舱", 620f, 200f, valuePaint)

                canvas.drawText("FROM 出发地", 50f, 265f, labelPaint)
                canvas.drawText("FROM: SHA 上海虹桥", 50f, 310f, valuePaint)

                canvas.drawText("TO 目的地", 480f, 265f, labelPaint)
                canvas.drawText("TO: CAN 广州白云", 480f, 310f, valuePaint)

                canvas.drawText("PASSENGER 旅客姓名", 50f, 370f, labelPaint)
                canvas.drawText("WANG/CHEN", 50f, 412f, valuePaint)

                canvas.drawText("GATE 登机口", 420f, 370f, labelPaint)
                canvas.drawText("GATE: B18", 420f, 412f, valuePaint)

                canvas.drawText("SEAT 座位号", 660f, 370f, labelPaint)
                canvas.drawText("SEAT: 35K", 660f, 412f, valuePaint)
            }
            else -> {
                canvas.drawText("BOARDING PASS · 登机牌   CHINA SOUTHERN 中国南方航空", 50f, 75f, whiteBold)
                canvas.drawText("FLIGHT 航班号", 50f, 155f, labelPaint)
                canvas.drawText("CZ3902", 50f, 200f, valuePaint)

                canvas.drawText("DATE 日期", 300f, 155f, labelPaint)
                canvas.drawText("2026-10-19", 300f, 200f, valuePaint)

                canvas.drawText("CLASS 舱位", 620f, 155f, labelPaint)
                canvas.drawText("ECONOMY 经济舱", 620f, 200f, valuePaint)

                canvas.drawText("FROM 出发地", 50f, 265f, labelPaint)
                canvas.drawText("FROM: SZX 深圳宝安", 50f, 310f, valuePaint)

                canvas.drawText("TO 目的地", 480f, 265f, labelPaint)
                canvas.drawText("TO: TFU 成都天府", 480f, 310f, valuePaint)

                canvas.drawText("PASSENGER 旅客姓名", 50f, 370f, labelPaint)
                canvas.drawText("ZHAO/MING", 50f, 412f, valuePaint)

                canvas.drawText("GATE 登机口", 420f, 370f, labelPaint)
                canvas.drawText("GATE: A09", 420f, 412f, valuePaint)

                canvas.drawText("SEAT 座位号", 660f, 370f, labelPaint)
                canvas.drawText("SEAT: 21C", 660f, 412f, valuePaint)
            }
        }
        return bmp
    }
}
