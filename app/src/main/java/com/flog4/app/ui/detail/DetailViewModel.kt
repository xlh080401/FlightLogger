package com.flog4.app.ui.detail

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flog4.app.ai.GpuAiInferenceEngine
import com.flog4.app.data.AircraftDatabase
import com.flog4.app.data.FlightDao
import com.flog4.app.data.FlightRecord
import com.flog4.app.log.FlightLog
import com.flog4.app.log.FlightLogGenerator
import com.flog4.app.log.PdfGenerator
import com.flog4.app.ui.i18n.AppLanguage
import com.flog4.app.ui.i18n.AppLocaleManager
import com.flog4.app.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: FlightDao,
    private val logGenerator: FlightLogGenerator,
    private val pdfGenerator: PdfGenerator,
    private val gpuAiEngine: GpuAiInferenceEngine
) : ViewModel() {

    private val _flightLog = MutableStateFlow<FlightLog?>(null)
    val flightLog: StateFlow<FlightLog?> = _flightLog

    private val _a4PreviewBitmap = MutableStateFlow<Bitmap?>(null)
    val a4PreviewBitmap: StateFlow<Bitmap?> = _a4PreviewBitmap

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _aiGenerating = MutableStateFlow(false)
    val aiGenerating: StateFlow<Boolean> = _aiGenerating

    private val _pdfFile = MutableStateFlow<File?>(null)
    val pdfFile: StateFlow<File?> = _pdfFile

    private val _statusToast = MutableStateFlow<String?>(null)
    val statusToast: StateFlow<String?> = _statusToast

    private var loadedFlightId: Long = -1L

    fun loadFlight(flightId: Long) {
        if (loadedFlightId == flightId) return
        loadedFlightId = flightId
        _isLoading.value = true
        viewModelScope.launch {
            combine(
                dao.getFlightByIdFlow(flightId),
                dao.getSensorDataFlow(flightId),
                AppLocaleManager.languageFlow
            ) { flight, sensorPoints, lang ->
                val log = if (flight == null) null else logGenerator.buildFlightLog(flight, sensorPoints)
                log to lang
            }.collect { (log, lang) ->
                _flightLog.value = log
                _isLoading.value = false
                if (log != null) {
                    val report = log.flight.aiReport
                    val needsLangSync = (!lang.isChinese && report.startsWith("【AI 飞行简报")) ||
                        (lang.isChinese && report.startsWith("[AI Flight Report"))
                    if (needsLangSync && !_aiGenerating.value) {
                        regenerateGpuAiReport(preferChinese = lang.isChinese, showToast = false)
                    }
                    val bmp = withContext(Dispatchers.Default) {
                        runCatching {
                            pdfGenerator.renderPreviewBitmap(
                                log = log,
                                scale = 1.6f,
                                preferChinese = lang.isChinese
                            )
                        }.getOrNull()
                    }
                    _a4PreviewBitmap.value = bmp
                }
            }
        }
    }

    /**
     * Runs the built-in small-parameter AI model on the phone's GPU to generate or refresh
     * the flight report from the current (or manually edited) flight metrics.
     */
    fun regenerateGpuAiReport(
        preferChinese: Boolean = AppLocaleManager.isChinese,
        showToast: Boolean = true
    ) {
        val currentLog = _flightLog.value ?: return
        if (_aiGenerating.value) return
        viewModelScope.launch {
            _aiGenerating.value = true
            try {
                val aiResult = withContext(Dispatchers.Default) {
                    gpuAiEngine.generateFlightReport(
                        flight = currentLog.flight,
                        sensorData = currentLog.sensorData,
                        preferChinese = preferChinese
                    )
                }
                val updated = currentLog.flight.copy(
                    aiReport = aiResult.reportText,
                    aiGpuInfo = aiResult.gpuDeviceInfo,
                    aiScore = aiResult.overallScore
                )
                withContext(Dispatchers.IO) {
                    dao.updateFlight(updated)
                }
                if (showToast) {
                    val tStr = String.format(Locale.US, "%.1f ms", aiResult.inferenceTimeMs)
                    _statusToast.value = AppLocaleManager.tr(
                        "已通过本地手机 GPU 推理更新 AI 飞行报告 ($tStr)",
                        "Updated AI Flight Report via local GPU inference ($tStr)"
                    )
                }
            } catch (e: Exception) {
                if (showToast) {
                    _statusToast.value = AppLocaleManager.tr(
                        "AI 推理异常: ${e.message}",
                        "GPU AI inference error: ${e.message}"
                    )
                }
            } finally {
                _aiGenerating.value = false
            }
        }
    }

    fun onLanguageSwitched(newLang: AppLanguage) {
        val log = _flightLog.value ?: return
        val currentReport = log.flight.aiReport
        if (currentReport.startsWith("【AI 飞行简报") || currentReport.startsWith("[AI Flight Report")) {
            regenerateGpuAiReport(preferChinese = newLang.isChinese, showToast = false)
        }
    }

    /**
     * Saves manual edits to any/all FlightRecord fields, automatically syncing duration and
     * recalculating the A4 PDF preview.
     */
    fun saveManualEdits(edited: FlightRecord) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val syncedDurationMs = TimeUtils.parseDurationDisplayToMs(
                    edited.totalFlightTimeDisplay,
                    edited.totalFlightTimeMs
                )
                val maxPax = if (edited.maxPassengers > 0) {
                    edited.maxPassengers
                } else {
                    AircraftDatabase.getMaxPassengers(context, edited.aircraftType)
                }
                dao.updateFlight(
                    edited.copy(
                        totalFlightTimeMs = syncedDurationMs,
                        maxPassengers = maxPax
                    )
                )
            }
            _statusToast.value = AppLocaleManager.tr(
                "✓ 所有修改已保存，图表与单页 A4 PDF 预览已同步更新",
                "✓ All edits saved; charts and single-page A4 PDF preview updated"
            )
        }
    }

    fun exportPdf() {
        val log = _flightLog.value ?: return
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    pdfGenerator.generatePdf(log, preferChinese = AppLocaleManager.isChinese)
                }
                if (file != null) {
                    _pdfFile.value = file
                } else {
                    _statusToast.value = AppLocaleManager.tr("PDF 生成失败", "Failed to generate PDF")
                }
            } catch (e: Exception) {
                _statusToast.value = AppLocaleManager.tr(
                    "PDF 导出异常: ${e.message}",
                    "PDF export error: ${e.message}"
                )
            }
        }
    }

    fun consumePdfFile() {
        _pdfFile.value = null
    }

    fun consumeStatusToast() {
        _statusToast.value = null
    }
}
