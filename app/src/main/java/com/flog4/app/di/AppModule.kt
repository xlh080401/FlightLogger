package com.flog4.app.di

import android.content.Context
import androidx.room.Room
import com.flog4.app.ai.GpuAiInferenceEngine
import com.flog4.app.data.FlightDao
import com.flog4.app.data.FlightDatabase
import com.flog4.app.log.FlightLogGenerator
import com.flog4.app.log.PdfGenerator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FlightDatabase {
        return Room.databaseBuilder(
            context,
            FlightDatabase::class.java,
            "flog4_database.db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideFlightDao(db: FlightDatabase): FlightDao = db.flightDao()

    @Provides
    @Singleton
    fun provideFlightLogGenerator(db: FlightDatabase): FlightLogGenerator {
        return FlightLogGenerator(db)
    }

    @Provides
    @Singleton
    fun providePdfGenerator(@ApplicationContext context: Context): PdfGenerator {
        return PdfGenerator(context)
    }

    @Provides
    @Singleton
    fun provideGpuAiInferenceEngine(@ApplicationContext context: Context): GpuAiInferenceEngine {
        return GpuAiInferenceEngine(context)
    }
}
