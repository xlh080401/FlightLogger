package com.flog4.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.*

data class Airport(
    val iata: String,
    val icao: String,
    val name: String,
    val city: String,
    val country: String,
    val lat: Double,
    val lon: Double,
    val elevationM: Int = 0,
    val runways: List<Runway> = emptyList(),
    val terminals: List<Terminal> = emptyList(),
    val nameZh: String = "",
    val cityZh: String = ""
) {
    fun displayLabel(preferChinese: Boolean = true): String {
        val label = if (preferChinese && nameZh.isNotBlank()) nameZh else name
        return if (iata.isNotBlank()) "$iata · $label" else label
    }

    fun shortCityOrName(preferChinese: Boolean = true): String {
        if (preferChinese && nameZh.isNotBlank()) return nameZh
        if (preferChinese && cityZh.isNotBlank()) return cityZh
        return city.ifBlank { name }
    }
}

data class Runway(
    val id: String,
    val t1Lat: Double,
    val t1Lon: Double,
    val t2Lat: Double,
    val t2Lon: Double
) {
    /** Forward true bearing from threshold 1 (t1) to threshold 2 (t2), in degrees [0, 360). */
    val headingDeg: Double
        get() = AirportDatabase.bearingDeg(t1Lat, t1Lon, t2Lat, t2Lon)

    val centerLat: Double get() = (t1Lat + t2Lat) / 2.0
    val centerLon: Double get() = (t1Lon + t2Lon) / 2.0
}

data class Terminal(val name: String, val lat: Double, val lon: Double)

data class RunwayMatchResult(
    val airport: Airport,
    val runway: Runway,
    val designator: String,
    val distanceKm: Double,
    val headingDiffDeg: Double
) {
    val formattedRunway: String
        get() = "RWY $designator"

    val formattedWithAirport: String
        get() = "${airport.iata.ifBlank { airport.icao }} RWY $designator"
}

object AirportDatabase {
    @Volatile
    private var airports: List<Airport>? = null
    private var byIata: Map<String, Airport> = emptyMap()
    private var byIcao: Map<String, Airport> = emptyMap()

    /**
     * Built-in Chinese names for major domestic and international airports so offline OCR
     * and search work seamlessly in both Chinese and English.
     */
    private val chineseAirportNames: Map<String, Pair<String, String>> = mapOf(
        "PEK" to ("北京首都国际机场" to "北京"),
        "PKX" to ("北京大兴国际机场" to "北京"),
        "SHA" to ("上海虹桥国际机场" to "上海"),
        "PVG" to ("上海浦东国际机场" to "上海"),
        "CAN" to ("广州白云国际机场" to "广州"),
        "SZX" to ("深圳宝安国际机场" to "深圳"),
        "CTU" to ("成都双流国际机场" to "成都"),
        "TFU" to ("成都天府国际机场" to "成都"),
        "CKG" to ("重庆江北国际机场" to "重庆"),
        "HGH" to ("杭州萧山国际机场" to "杭州"),
        "XIY" to ("西安咸阳国际机场" to "西安"),
        "KMG" to ("昆明长水国际机场" to "昆明"),
        "WUH" to ("武汉天河国际机场" to "武汉"),
        "NKG" to ("南京禄口国际机场" to "南京"),
        "CSX" to ("长沙黄花国际机场" to "长沙"),
        "XMN" to ("厦门高崎国际机场" to "厦门"),
        "TAO" to ("青岛胶东国际机场" to "青岛"),
        "HAK" to ("海口美兰国际机场" to "海口"),
        "SYX" to ("三亚凤凰国际机场" to "三亚"),
        "TSN" to ("天津滨海国际机场" to "天津"),
        "CGO" to ("郑州新郑国际机场" to "郑州"),
        "DLC" to ("大连周水子国际机场" to "大连"),
        "SHE" to ("沈阳桃仙国际机场" to "沈阳"),
        "HRB" to ("哈尔滨太平国际机场" to "哈尔滨"),
        "URC" to ("乌鲁木齐地窝堡国际机场" to "乌鲁木齐"),
        "TNA" to ("济南遥墙国际机场" to "济南"),
        "FOC" to ("福州长乐国际机场" to "福州"),
        "NNG" to ("南宁吴圩国际机场" to "南宁"),
        "KWE" to ("贵阳龙洞堡国际机场" to "贵阳"),
        "KWL" to ("桂林两江国际机场" to "桂林"),
        "LHW" to ("兰州中川国际机场" to "兰州"),
        "TYN" to ("太原武宿国际机场" to "太原"),
        "CGQ" to ("长春龙嘉国际机场" to "长春"),
        "HET" to ("呼和浩特白塔国际机场" to "呼和浩特"),
        "INC" to ("银川河东国际机场" to "银川"),
        "XNN" to ("西宁曹家堡国际机场" to "西宁"),
        "LXA" to ("拉萨贡嘎国际机场" to "拉萨"),
        "KHN" to ("南昌昌北国际机场" to "南昌"),
        "HFE" to ("合肥新桥国际机场" to "合肥"),
        "WNZ" to ("温州龙湾国际机场" to "温州"),
        "NGB" to ("宁波栎社国际机场" to "宁波"),
        "WUX" to ("苏南硕放国际机场" to "无锡"),
        "ZUH" to ("珠海金湾机场" to "珠海"),
        "SJW" to ("石家庄正定国际机场" to "石家庄"),
        "YNT" to ("烟台蓬莱国际机场" to "烟台"),
        "LJG" to ("丽江三义国际机场" to "丽江"),
        "JHG" to ("西双版纳嘎洒国际机场" to "西双版纳"),
        "HKG" to ("香港国际机场" to "香港"),
        "MFM" to ("澳门国际机场" to "澳门"),
        "TPE" to ("台北桃园国际机场" to "台北"),
        "TSA" to ("台北松山机场" to "台北"),
        "KHH" to ("高雄国际机场" to "高雄"),
        "NRT" to ("东京成田国际机场" to "东京"),
        "HND" to ("东京羽田国际机场" to "东京"),
        "KIX" to ("大阪关西国际机场" to "大阪"),
        "ICN" to ("首尔仁川国际机场" to "首尔"),
        "GMP" to ("首尔金浦国际机场" to "首尔"),
        "SIN" to ("新加坡樟宜机场" to "新加坡"),
        "BKK" to ("曼谷素万那普国际机场" to "曼谷"),
        "KUL" to ("吉隆坡国际机场" to "吉隆坡"),
        "DXB" to ("迪拜国际机场" to "迪拜"),
        "DOH" to ("多哈哈马德国际机场" to "多哈"),
        "LHR" to ("伦敦希思罗机场" to "伦敦"),
        "CDG" to ("巴黎戴高乐机场" to "巴黎"),
        "FRA" to ("法兰克福机场" to "法兰克福"),
        "AMS" to ("阿姆斯特丹史基浦机场" to "阿姆斯特丹"),
        "LAX" to ("洛杉矶国际机场" to "洛杉矶"),
        "SFO" to ("旧金山国际机场" to "旧金山"),
        "JFK" to ("纽约肯尼迪国际机场" to "纽约"),
        "ORD" to ("芝加哥奥黑尔国际机场" to "芝加哥"),
        "SEA" to ("西雅图塔科马国际机场" to "西雅图"),
        "YVR" to ("温哥华国际机场" to "温哥华"),
        "YYZ" to ("多伦多皮尔逊国际机场" to "多伦多"),
        "SYD" to ("悉尼金斯福德·史密斯机场" to "悉尼"),
        "MEL" to ("墨尔本机场" to "墨尔本")
    )

    fun load(context: Context): List<Airport> {
        airports?.let { return it }
        synchronized(this) {
            airports?.let { return it }
            val json = context.assets.open("airports.json").bufferedReader().use { it.readText() }
            val type = object : TypeToken<List<AirportJsonEntry>>() {}.type
            val entries: List<AirportJsonEntry> = Gson().fromJson(json, type)
            val loaded = entries.map { entry ->
                val zh = chineseAirportNames[entry.iata.uppercase()]
                entry.toAirport(
                    nameZh = zh?.first ?: "",
                    cityZh = zh?.second ?: ""
                )
            }
            byIata = loaded.filter { it.iata.isNotBlank() }.associateBy { it.iata.uppercase() }
            byIcao = loaded.filter { it.icao.isNotBlank() }.associateBy { it.icao.uppercase() }
            airports = loaded
            return loaded
        }
    }

    fun getByCode(context: Context, code: String): Airport? {
        if (code.isBlank()) return null
        load(context)
        val upper = code.trim().uppercase()
        return byIata[upper] ?: byIcao[upper]
    }

    fun findByTextQuery(context: Context, query: String, limit: Int = 15): List<Airport> {
        val all = load(context)
        val q = query.trim()
        if (q.isEmpty()) {
            // Return popular airports first
            val topIatas = listOf("PEK", "PKX", "SHA", "PVG", "CAN", "SZX", "CTU", "TFU", "HGH", "XIY", "HKG", "SIN", "HND", "LAX", "LHR")
            return topIatas.mapNotNull { byIata[it] }.take(limit)
        }
        val upper = q.uppercase()
        val exactIata = byIata[upper]
        val exactIcao = byIcao[upper]
        val matches = all.filter {
            it.iata.equals(upper, ignoreCase = true) ||
                it.icao.equals(upper, ignoreCase = true) ||
                it.nameZh.contains(q) ||
                it.cityZh.contains(q) ||
                it.city.contains(q, ignoreCase = true) ||
                it.name.contains(q, ignoreCase = true)
        }
        val ordered = LinkedHashSet<Airport>()
        if (exactIata != null) ordered.add(exactIata)
        if (exactIcao != null) ordered.add(exactIcao)
        ordered.addAll(matches)
        return ordered.take(limit)
    }

    /**
     * Resolves a Chinese or English city/airport name from a boarding pass into its Airport entry.
     */
    fun matchAirportInText(context: Context, text: String): List<Airport> {
        load(context)
        val found = mutableListOf<Airport>()
        // 1. Check Chinese airport/city aliases ordered by specificity
        val aliasPairs = chineseAirportNames.entries.sortedByDescending { it.value.first.length }
        for ((iata, names) in aliasPairs) {
            val (fullNameZh, cityZh) = names
            val shortAirportZh = fullNameZh.removeSuffix("国际机场").removeSuffix("机场")
            if (text.contains(shortAirportZh) || (cityZh.length >= 2 && text.contains(cityZh))) {
                byIata[iata]?.let { airport ->
                    if (found.none { it.iata == airport.iata }) {
                        found.add(airport)
                    }
                }
            }
        }
        return found
    }

    fun findNearestAirport(context: Context, lat: Double, lon: Double, maxDistKm: Double = 35.0): Airport? {
        if (lat == 0.0 && lon == 0.0) return null
        return load(context)
            .asSequence()
            .map { it to distanceKm(lat, lon, it.lat, it.lon) }
            .filter { it.second <= maxDistKm }
            .minByOrNull { it.second }
            ?.first
    }

    /**
     * Matches the active takeoff or landing runway using pre-stored global airport GPS coordinates
     * and runway threshold endpoints (t1, t2) + aircraft true track bearing.
     */
    fun identifyRunway(
        context: Context,
        lat: Double,
        lon: Double,
        headingDeg: Double,
        maxAirportDistKm: Double = 35.0
    ): RunwayMatchResult? {
        val airport = findNearestAirport(context, lat, lon, maxAirportDistKm) ?: return null
        if (airport.runways.isEmpty()) return null

        var bestRunway: Runway? = null
        var bestScore = Double.MAX_VALUE
        var bestDistKm = 0.0
        var bestHeadingDiff = 0.0

        for (rwy in airport.runways) {
            val distToCenter = distanceKm(lat, lon, rwy.centerLat, rwy.centerLon)
            val distToT1 = distanceKm(lat, lon, rwy.t1Lat, rwy.t1Lon)
            val distToT2 = distanceKm(lat, lon, rwy.t2Lat, rwy.t2Lon)
            val minDistKm = minOf(distToCenter, distToT1, distToT2)

            val fwdDiff = angleDiffDeg(headingDeg, rwy.headingDeg)
            val revDiff = angleDiffDeg(headingDeg, (rwy.headingDeg + 180.0) % 360.0)
            val minHeadingDiff = minOf(fwdDiff, revDiff)

            // Combined score: distance in meters + heading mismatch penalty
            val score = minDistKm * 1000.0 + minHeadingDiff * 35.0
            if (score < bestScore) {
                bestScore = score
                bestRunway = rwy
                bestDistKm = minDistKm
                bestHeadingDiff = minHeadingDiff
            }
        }

        val matched = bestRunway ?: return null
        val designator = identifyRunwayDesignator(matched, headingDeg)
        return RunwayMatchResult(
            airport = airport,
            runway = matched,
            designator = designator,
            distanceKm = bestDistKm,
            headingDiffDeg = bestHeadingDiff
        )
    }

    /**
     * Identifies the runway designator (e.g. "36R" from "18L/36R") using the aircraft's
     * movement direction along the runway.
     *
     * Note on aviation runway numbering:
     * Runway numbers are magnetic azimuth / 10 (e.g., RWY 36 is ~360° north, RWY 18 is ~180° south).
     * We first check the numeric runway designators against the aircraft's bearing; if non-standard,
     * we fall back to threshold t1->t2 vector alignment.
     */
    fun identifyRunwayDesignator(runway: Runway, headingDeg: Double): String {
        val parts = runway.id.split("/")
        if (parts.size != 2) return runway.id.trim()

        val normHeading = ((headingDeg % 360.0) + 360.0) % 360.0
        val num0 = parts[0].filter { it.isDigit() }.toIntOrNull()
        val num1 = parts[1].filter { it.isDigit() }.toIntOrNull()
        if (num0 != null && num1 != null && num0 in 1..36 && num1 in 1..36) {
            val mag0 = (num0 * 10) % 360
            val mag1 = (num1 * 10) % 360
            val diff0 = angleDiffDeg(normHeading, mag0.toDouble())
            val diff1 = angleDiffDeg(normHeading, mag1.toDouble())
            return if (diff0 <= diff1) parts[0].trim() else parts[1].trim()
        }

        val rwyHeading = runway.headingDeg
        val reverseHeading = (rwyHeading + 180.0) % 360.0
        val diffFwd = angleDiffDeg(normHeading, rwyHeading)
        val diffRev = angleDiffDeg(normHeading, reverseHeading)
        return if (diffFwd <= diffRev) parts[0].trim() else parts[1].trim()
    }

    fun identifyTerminal(context: Context, lat: Double, lon: Double): String? {
        val airport = findNearestAirport(context, lat, lon, 10.0) ?: return null
        val terminal = airport.terminals.minByOrNull { distanceKm(lat, lon, it.lat, it.lon) }
        return terminal?.name
    }

    fun angleDiffDeg(a: Double, b: Double): Double {
        val diff = abs(((a - b) % 360.0 + 360.0) % 360.0)
        return if (diff > 180.0) 360.0 - diff else diff
    }

    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val y = sin(dLon) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Integrates total flight path distance (in km) across valid GPS sensor points.
     */
    fun calculateTrackDistanceKm(points: List<SensorDataPoint>): Double {
        val valid = points.filter { it.latitude != 0.0 || it.longitude != 0.0 }
        if (valid.size < 2) return 0.0
        var totalKm = 0.0
        var prev = valid.first()
        for (i in 1 until valid.size) {
            val cur = valid[i]
            val segKm = distanceKm(prev.latitude, prev.longitude, cur.latitude, cur.longitude)
            // Ignore GPS glitches (> 500 km jump in a single sample)
            if (segKm in 0.005..500.0) {
                totalKm += segKm
                prev = cur
            }
        }
        return totalKm
    }
}

data class AirportJsonEntry(
    val iata: String = "",
    val icao: String = "",
    val name: String = "",
    val city: String = "",
    val country: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val elevationM: Int = 0,
    val runways: List<RunwayJson>? = null,
    val terminals: List<TerminalJson>? = null
) {
    fun toAirport(nameZh: String = "", cityZh: String = "") = Airport(
        iata = iata,
        icao = icao,
        name = name,
        city = city,
        country = country,
        lat = lat,
        lon = lon,
        elevationM = elevationM,
        runways = runways?.mapNotNull {
            if (it.t1.size >= 2 && it.t2.size >= 2) {
                Runway(it.id, it.t1[0], it.t1[1], it.t2[0], it.t2[1])
            } else null
        } ?: emptyList(),
        terminals = terminals?.map { Terminal(it.name, it.lat, it.lon) } ?: emptyList(),
        nameZh = nameZh,
        cityZh = cityZh
    )
}

data class RunwayJson(val id: String = "", val t1: List<Double> = emptyList(), val t2: List<Double> = emptyList())
data class TerminalJson(val name: String = "", val lat: Double = 0.0, val lon: Double = 0.0)
