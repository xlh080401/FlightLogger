package com.flog4.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class AircraftInfo(
    val icao: String,
    val name: String,
    val maxPax: Int,
    val category: String
)

data class AirlineInfo(
    val iata: String,
    val name: String,
    val nameZh: String,
    val logoRes: String
) {
    fun displayName(preferChinese: Boolean = true): String {
        return if (preferChinese && nameZh.isNotBlank()) "$nameZh ($name)" else name
    }
}

object AircraftDatabase {
    @Volatile
    private var aircraft: List<AircraftInfo>? = null

    fun load(context: Context): List<AircraftInfo> {
        aircraft?.let { return it }
        synchronized(this) {
            aircraft?.let { return it }
            val json = context.assets.open("aircraft_data.json").bufferedReader().use { it.readText() }
            val type = object : TypeToken<List<AircraftInfo>>() {}.type
            val list: List<AircraftInfo> = Gson().fromJson(json, type)
            aircraft = list
            return list
        }
    }

    fun getMaxPassengers(context: Context, query: String): Int {
        if (query.isBlank()) return 180
        val q = query.trim()
        val list = load(context)
        return list.find {
            it.icao.equals(q, ignoreCase = true) ||
                it.name.equals(q, ignoreCase = true) ||
                it.name.contains(q, ignoreCase = true) ||
                q.contains(it.icao, ignoreCase = true)
        }?.maxPax ?: 180
    }

    fun searchAircraft(context: Context, query: String): List<AircraftInfo> {
        val list = load(context)
        if (query.isBlank()) return list
        return list.filter {
            it.icao.contains(query, ignoreCase = true) ||
                it.name.contains(query, ignoreCase = true) ||
                it.category.contains(query, ignoreCase = true)
        }
    }
}

object AirlineDatabase {
    @Volatile
    private var airlines: List<AirlineInfo>? = null
    private var byIata: Map<String, AirlineInfo> = emptyMap()

    fun load(context: Context): List<AirlineInfo> {
        airlines?.let { return it }
        synchronized(this) {
            airlines?.let { return it }
            val json = context.assets.open("airlines.json").bufferedReader().use { it.readText() }
            val type = object : TypeToken<List<AirlineInfo>>() {}.type
            val list: List<AirlineInfo> = Gson().fromJson(json, type)
            byIata = list.associateBy { it.iata.uppercase() }
            airlines = list
            return list
        }
    }

    fun getByIata(context: Context, iata: String): AirlineInfo? {
        if (iata.isBlank()) return null
        load(context)
        return byIata[iata.trim().take(2).uppercase()]
    }

    fun matchAirlineInText(context: Context, text: String): AirlineInfo? {
        val list = load(context)
        val upper = text.uppercase()
        // First check Chinese airline names (e.g. "中国国际航空", "东方航空", "南方航空", "海南航空")
        for (airline in list) {
            if (airline.nameZh.length >= 2 && text.contains(airline.nameZh)) {
                return airline
            }
        }
        // Check English airline names (at least 5 chars to avoid short acronym false positives)
        for (airline in list) {
            if (airline.name.length >= 5 && upper.contains(airline.name.uppercase())) {
                return airline
            }
        }
        return null
    }
}
