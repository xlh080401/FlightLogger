package com.flog4.app

import android.app.Application
import com.flog4.app.data.AircraftDatabase
import com.flog4.app.data.AirlineDatabase
import com.flog4.app.data.AirportDatabase
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class FlogApplication : Application() {
    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        // Pre-warm offline global airports, airlines, and aircraft databases on background thread
        appScope.launch {
            runCatching {
                AirportDatabase.load(this@FlogApplication)
                AirlineDatabase.load(this@FlogApplication)
                AircraftDatabase.load(this@FlogApplication)
            }
        }
    }
}
