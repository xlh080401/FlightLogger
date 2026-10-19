package com.flog4.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun fromFlightPhase(value: FlightPhase): String = value.name

    @TypeConverter
    fun toFlightPhase(value: String): FlightPhase =
        runCatching { FlightPhase.valueOf(value) }.getOrDefault(FlightPhase.GROUND)

    @TypeConverter
    fun fromFlightStatus(value: FlightStatus): String = value.name

    @TypeConverter
    fun toFlightStatus(value: String): FlightStatus =
        runCatching { FlightStatus.valueOf(value) }.getOrDefault(FlightStatus.COMPLETED)
}

@Database(
    entities = [FlightRecord::class, SensorDataPoint::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class FlightDatabase : RoomDatabase() {
    abstract fun flightDao(): FlightDao
}
