package com.flog4.app.ui.recording

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flog4.app.data.FlightDao
import com.flog4.app.data.FlightRecord
import com.flog4.app.data.FlightStatus
import com.flog4.app.data.SensorDataPoint
import com.flog4.app.service.GpsSignalInfo
import com.flog4.app.service.SensorAvailability
import com.flog4.app.service.SensorCollectionService
import com.flog4.app.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class RecordingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: FlightDao
) : ViewModel() {

    val latestSensorPoint: StateFlow<SensorDataPoint?> = SensorCollectionService.sensorStream
    val gpsSignal: StateFlow<GpsSignalInfo> = SensorCollectionService.gpsSignalStream
    val sensorAvailability: StateFlow<SensorAvailability> = SensorCollectionService.sensorAvailability
    val activeRecordingFlightId: StateFlow<Long?> = SensorCollectionService.recordingFlightId
    val isSimulating: StateFlow<Boolean> = SensorCollectionService.isSimulatingFlow

    private val _flightRecord = MutableStateFlow<FlightRecord?>(null)
    val flightRecord: StateFlow<FlightRecord?> = _flightRecord

    private var currentFlightId: Long = -1L

    fun attachOrCreateFlight(flightIdArg: Long) {
        if (currentFlightId > 0L && flightIdArg == currentFlightId) return
        viewModelScope.launch {
            val resolvedId = withContext(Dispatchers.IO) {
                if (flightIdArg > 0L) {
                    flightIdArg
                } else {
                    val active = SensorCollectionService.recordingFlightId.value
                    if (active != null && active > 0L) {
                        active
                    } else {
                        val now = System.currentTimeMillis()
                        dao.insertFlight(
                            FlightRecord(
                                flightNumber = "CA1501",
                                airline = "CA",
                                airlineName = "中国国际航空 (Air China)",
                                origin = "PEK",
                                originName = "北京首都国际机场",
                                destination = "SHA",
                                destinationName = "上海虹桥国际机场",
                                date = TimeUtils.formatDate(now),
                                aircraftType = "Airbus A350-900",
                                registration = "B-308M",
                                maxPassengers = 314,
                                status = FlightStatus.SETUP,
                                createdAt = now
                            )
                        )
                    }
                }
            }
            currentFlightId = resolvedId
            dao.getFlightByIdFlow(resolvedId).collect { record ->
                _flightRecord.value = record
            }
        }
    }

    fun startRecording(simulate: Boolean = false) {
        val id = currentFlightId.takeIf { it > 0L } ?: return
        val intent = Intent(context, SensorCollectionService::class.java).apply {
            action = SensorCollectionService.ACTION_START
            putExtra(SensorCollectionService.EXTRA_FLIGHT_ID, id)
            putExtra(SensorCollectionService.EXTRA_SIMULATE, simulate)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun switchToSimulation() {
        val id = currentFlightId.takeIf { it > 0L } ?: return
        if (activeRecordingFlightId.value == null) {
            startRecording(simulate = true)
        } else {
            val intent = Intent(context, SensorCollectionService::class.java).apply {
                action = SensorCollectionService.ACTION_SIMULATE
                putExtra(SensorCollectionService.EXTRA_FLIGHT_ID, id)
            }
            context.startService(intent)
        }
    }

    fun stopRecording(onFinished: (Long) -> Unit) {
        val id = currentFlightId.takeIf { it > 0L } ?: return
        val intent = Intent(context, SensorCollectionService::class.java).apply {
            action = SensorCollectionService.ACTION_STOP
        }
        context.startService(intent)
        onFinished(id)
    }
}
