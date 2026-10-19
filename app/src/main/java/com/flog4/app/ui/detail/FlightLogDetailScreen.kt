package com.flog4.app.ui.detail

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.flog4.app.data.AirlineDatabase
import com.flog4.app.data.AirportDatabase
import com.flog4.app.data.FlightRecord
import com.flog4.app.ui.i18n.AppLocaleManager
import com.flog4.app.ui.i18n.LocalAppLanguage
import com.flog4.app.ui.i18n.Md3LanguageSwitchPill
import com.flog4.app.ui.i18n.s
import com.flog4.app.util.AirlineLogoRenderer
import com.flog4.app.util.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightLogDetailScreen(
    flightId: Long,
    onBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel()
) {
    val flightLog by viewModel.flightLog.collectAsState()
    val a4PreviewBitmap by viewModel.a4PreviewBitmap.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val aiGenerating by viewModel.aiGenerating.collectAsState()
    val pdfFile by viewModel.pdfFile.collectAsState()
    val statusToast by viewModel.statusToast.collectAsState()
    val context = LocalContext.current
    val isZh = LocalAppLanguage.current.isChinese

    var showEditAllDialog by remember { mutableStateOf(false) }

    LaunchedEffect(flightId) {
        viewModel.loadFlight(flightId)
    }

    LaunchedEffect(statusToast) {
        statusToast?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.consumeStatusToast()
        }
    }

    LaunchedEffect(pdfFile) {
        pdfFile?.let { file ->
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooserTitle = AppLocaleManager.tr(
                    "导出 / 分享单页 A4 飞行日志 PDF",
                    "Export / Share Single-Page A4 Flight Log PDF"
                )
                context.startActivity(Intent.createChooser(intent, chooserTitle))
            } catch (_: Exception) {
                Toast.makeText(context, "PDF: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            } finally {
                viewModel.consumePdfFile()
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = s("飞行日志与单页 A4 PDF", "Flight Log & Single-Page A4"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = s("返回", "Back")
                        )
                    }
                },
                actions = {
                    Md3LanguageSwitchPill(
                        onLanguageChanged = { newLang ->
                            viewModel.onLanguageSwitched(newLang)
                        }
                    )
                    IconButton(onClick = { showEditAllDialog = true }) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = s("手动编辑全部数据", "Edit All Data")
                        )
                    }
                    IconButton(onClick = { viewModel.exportPdf() }) {
                        Icon(
                            Icons.Default.PictureAsPdf,
                            contentDescription = s("导出单页A4 PDF", "Export Single-Page A4 PDF")
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val log = flightLog ?: return@Scaffold
            val f = log.flight
            val airlineCode = f.airline.ifBlank { f.flightNumber.take(2) }
            val airlineLogo by produceState<Bitmap?>(initialValue = null, airlineCode) {
                value = withContext(Dispatchers.IO) {
                    AirlineLogoRenderer.render(context, airlineCode, AirlineLogoRenderer.UI_SIZE_PX)
                }
            }

            if (showEditAllDialog) {
                FullFlightLogManualEditDialog(
                    initial = f,
                    onDismiss = { showEditAllDialog = false },
                    onSave = { edited ->
                        viewModel.saveManualEdits(edited)
                        showEditAllDialog = false
                    }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Top Action Bar: Manual Edit All Data + Export A4 PDF
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = { showEditAllDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Icon(Icons.Default.EditNote, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = s("手动编辑所有数据", "Edit All Data"),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                    Button(
                        onClick = { viewModel.exportPdf() },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = s("导出单页 A4 PDF", "Export A4 PDF"),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }

                // =========================================================================
                // 1. HEADER SUMMARY CARD (FLIGHT, AIRLINE, ROUTE, AIRCRAFT & REG)
                // =========================================================================
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                airlineLogo?.let { bmp ->
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(MaterialTheme.shapes.small)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                }
                                Column {
                                    Text(
                                        text = f.flightNumber.ifBlank { s("航班 #${f.id}", "Flight #${f.id}") },
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val airlineObj = AirlineDatabase.getByIata(context, airlineCode)
                                    val airlineDisplay = if (isZh) {
                                        f.airlineName.ifBlank { airlineObj?.displayName(true) ?: airlineCode }
                                    } else {
                                        airlineObj?.displayName(false)?.ifBlank { f.airlineName } ?: f.airlineName.ifBlank { airlineCode }
                                    }
                                    Text(
                                        text = airlineDisplay,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = f.date,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = f.aircraftType.ifBlank { s("未填机型", "Aircraft TBD") },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${s("注册号", "Reg")}: ${f.registration.ifBlank { "—" }}",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))

                        val origAirport = AirportDatabase.getByCode(context, f.origin)
                        val destAirport = AirportDatabase.getByCode(context, f.destination)
                        val origNameDisplay = if (isZh) {
                            f.originName.ifBlank { origAirport?.shortCityOrName(true) ?: "出发" }
                        } else {
                            origAirport?.shortCityOrName(false)?.ifBlank { f.originName } ?: f.originName.ifBlank { "Origin" }
                        }
                        val destNameDisplay = if (isZh) {
                            f.destinationName.ifBlank { destAirport?.shortCityOrName(true) ?: "目的" }
                        } else {
                            destAirport?.shortCityOrName(false)?.ifBlank { f.destinationName } ?: f.destinationName.ifBlank { "Dest" }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            DetailMetricItem(
                                label = s("航线 Route", "Flight Route"),
                                value = "${f.origin.ifBlank { "DEP" }} ($origNameDisplay) → ${f.destination.ifBlank { "ARR" }} ($destNameDisplay)",
                                modifier = Modifier.weight(1f)
                            )
                            DetailMetricItem(
                                label = s("座位 / 登机口", "Seat / Gate"),
                                value = "${f.seatNumber.ifBlank { "—" }} / ${f.boardingGate.ifBlank { "—" }}",
                                horizontalAlignment = Alignment.End
                            )
                        }
                    }
                }

                // =========================================================================
                // 2. TAKEOFF/LANDING TIME, DISTANCE, TOTAL TIME & RUNWAYS CARD
                // =========================================================================
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = s("核心飞行参数 · 时间 / 里程 / 跑道", "Core Flight Metrics · Time / Distance / Runway"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { showEditAllDialog = true }) {
                                Text(s("点击修改", "Edit"))
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DetailMetricItem(
                                label = s("起飞时间 Takeoff", "Takeoff Time"),
                                value = f.takeoffTimeDisplay.ifBlank { TimeUtils.formatLocalTime(f.takeoffTimeUtc) },
                                modifier = Modifier.weight(1f)
                            )
                            DetailMetricItem(
                                label = s("降落时间 Landing", "Landing Time"),
                                value = f.landingTimeDisplay.ifBlank { TimeUtils.formatLocalTime(f.landingTimeUtc) },
                                modifier = Modifier.weight(1f)
                            )
                            DetailMetricItem(
                                label = s("飞行总时间 Duration", "Total Duration"),
                                value = f.totalFlightTimeDisplay.ifBlank { TimeUtils.formatDuration(f.totalFlightTimeMs) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DetailMetricItem(
                                label = s("飞行总里程 Distance", "Total Distance"),
                                value = String.format(
                                    Locale.US,
                                    "%.0f km (%d NM)",
                                    f.totalDistanceKm,
                                    (f.totalDistanceKm * 0.539957).roundToInt()
                                ),
                                modifier = Modifier.weight(1.3f)
                            )
                            DetailMetricItem(
                                label = s("起飞跑道 Dep Rwy", "Dep Runway"),
                                value = f.departureRunway.ifBlank { "—" },
                                modifier = Modifier.weight(0.85f)
                            )
                            DetailMetricItem(
                                label = s("降落跑道 Arr Rwy", "Landing Rwy"),
                                value = f.arrivalRunway.ifBlank { "—" },
                                highlight = true,
                                modifier = Modifier.weight(0.85f)
                            )
                        }
                    }
                }

                // =========================================================================
                // 3. V1 / V2 / Vapp SPEEDS & SENSOR EXTREMES CARD
                // =========================================================================
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = s(
                                "特征速度与四传感器极值 (水平仪 + 加速度计 + 气压计 + GPS)",
                                "V-Speeds & 4-Sensor Extremes (Level + Accel + Baro + GPS)"
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SpeedBadgeItem(
                                modifier = Modifier.weight(1f),
                                title = s("V1 决断速度", "V1 Decision"),
                                mainVal = "${f.v1Knots.roundToInt()} kts",
                                subVal = "${(f.v1Knots * 1.852).roundToInt()} km/h",
                                color = MaterialTheme.colorScheme.primary
                            )
                            SpeedBadgeItem(
                                modifier = Modifier.weight(1f),
                                title = s("V2 抬轮速度", "V2 Rotation"),
                                mainVal = "${f.v2Knots.roundToInt()} kts",
                                subVal = String.format(Locale.US, s("仰角 %.1f°", "Pitch %.1f°"), f.maxPitchDeg),
                                color = MaterialTheme.colorScheme.secondary
                            )
                            SpeedBadgeItem(
                                modifier = Modifier.weight(1f),
                                title = s("Vapp 接地速度", "Vapp Touchdown"),
                                mainVal = "${f.vappKnots.roundToInt()} kts",
                                subVal = String.format(Locale.US, s("接地 %.2fG", "%.2fG TD"), f.touchdownG),
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            SpeedBadgeItem(
                                modifier = Modifier.weight(1f),
                                title = s("最大地速", "Max Speed"),
                                mainVal = "${f.maxSpeedKnots.roundToInt()} kts",
                                subVal = s("均速 ${f.avgSpeedKnots.roundToInt()} kts", "Avg ${f.avgSpeedKnots.roundToInt()} kts"),
                                color = Color(0xFFEA580C)
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DetailMetricItem(
                                label = s("最大高度 Max Alt", "Max Altitude"),
                                value = "${f.maxAltitudeM.roundToInt()} m (${(f.maxAltitudeM * 3.28084).roundToInt()} ft)",
                                modifier = Modifier.weight(1.25f)
                            )
                            DetailMetricItem(
                                label = s("机舱最低气压", "Min Cabin hPa"),
                                value = String.format(Locale.US, "%.1f hPa", f.minCabinPressureHpa),
                                modifier = Modifier.weight(0.85f)
                            )
                            DetailMetricItem(
                                label = s("最大俯仰 / 坡度", "Max Pitch / Roll"),
                                value = String.format(Locale.US, "%.1f° / %.1f°", f.maxPitchDeg, f.maxRollDeg),
                                modifier = Modifier.weight(0.9f)
                            )
                        }
                    }
                }

                // =========================================================================
                // 4. THREE COMPLETE FLIGHT CURVE CHARTS
                // =========================================================================
                InteractiveProfileChartCard(
                    title = s(
                        "① 飞行过程中高度变化曲线图 · Altitude Profile (m)",
                        "① Flight Altitude Profile · 高度变化曲线图 (m)"
                    ),
                    subtitle = String.format(
                        Locale.US,
                        s("峰值高度: %.0f m (%.0f ft)", "Peak Altitude: %.0f m (%.0f ft)"),
                        f.maxAltitudeM,
                        f.maxAltitudeM * 3.28084
                    ),
                    data = log.altitudePoints,
                    lineColor = Color(0xFF0284C7),
                    fixedMinZero = true
                )

                InteractiveProfileChartCard(
                    title = s(
                        "② 机舱内气压变化曲线图 · Cabin Pressure Profile (hPa)",
                        "② Cabin Pressure Profile · 机舱内气压变化曲线图 (hPa)"
                    ),
                    subtitle = String.format(
                        Locale.US,
                        s("气压区间: %.1f hPa – %.1f hPa", "Pressure Range: %.1f hPa – %.1f hPa"),
                        f.minCabinPressureHpa,
                        f.maxCabinPressureHpa
                    ),
                    data = log.pressurePoints,
                    lineColor = Color(0xFF0D9488),
                    fixedMinZero = false
                )

                InteractiveProfileChartCard(
                    title = s(
                        "③ 飞机速度变化曲线图 · Ground Speed Profile (kts)",
                        "③ Aircraft Ground Speed Profile · 飞机速度曲线图 (kts)"
                    ),
                    subtitle = String.format(
                        Locale.US,
                        s(
                            "V1: %d kts · V2: %d kts · 巡航峰值: %d kts · Vapp: %d kts",
                            "V1: %d kts · V2: %d kts · Peak: %d kts · Vapp: %d kts"
                        ),
                        f.v1Knots.roundToInt(),
                        f.v2Knots.roundToInt(),
                        f.maxSpeedKnots.roundToInt(),
                        f.vappKnots.roundToInt()
                    ),
                    data = log.speedPoints,
                    lineColor = Color(0xFFEA580C),
                    fixedMinZero = true
                )

                // =========================================================================
                // 5. BUILT-IN SMALL-PARAMETER AI MODEL (LOCAL PHONE GPU INFERENCE)
                // =========================================================================
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    ),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = s(
                                        "✦ 本地手机 GPU AI 飞行分析报告",
                                        "✦ On-Device Mobile GPU AI Flight Report"
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = f.aiGpuInfo.ifBlank {
                                        s(
                                            "内置轻量化神经网络 FlightNano-v1 · 手机 GPU 本地推理",
                                            "Built-in FlightNano-v1 Neural Network · Mobile GPU Compute"
                                        )
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            FilledTonalButton(
                                onClick = { viewModel.regenerateGpuAiReport(preferChinese = isZh) },
                                enabled = !aiGenerating,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                shape = MaterialTheme.shapes.small
                            ) {
                                if (aiGenerating) {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        Icons.Default.Memory,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = s("GPU重推理", "GPU Infer"),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }

                        Text(
                            text = f.aiReport.ifBlank {
                                s(
                                    "点击右上角「GPU重推理」由手机本地 GPU 运行内置小参数 AI 模型生成飞行报告。",
                                    "Tap 'GPU Infer' above to run the built-in small-parameter neural model on your phone's GPU."
                                )
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                // =========================================================================
                // 6. LIVE SINGLE-PAGE A4 PDF PREVIEW & EXPORT
                // =========================================================================
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column {
                            Text(
                                text = s(
                                    "单页 A4 PDF 实时排版预览 (595 × 842 pt · 严格一页以内)",
                                    "Single-Page A4 PDF Live Preview (595 × 842 pt · Strictly 1 Page)"
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = s(
                                    "修改任意数据或切换中英语言后此 A4 预览会自动重绘，点击下方按钮即可导出标准单页 A4 PDF",
                                    "Automatically updates when editing any field or switching language; tap below to export the single-page A4 PDF"
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        a4PreviewBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = s("单页 A4 PDF 预览", "Single-page A4 PDF preview"),
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                            )
                        }

                        Button(
                            onClick = { viewModel.exportPdf() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = s(
                                    "生成并分享/保存单页 A4 PDF 飞行日志",
                                    "Export & Share Single-Page A4 PDF Flight Log"
                                ),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DetailMetricItem(
    label: String,
    value: String,
    highlight: Boolean = false,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start
) {
    Column(modifier = modifier, horizontalAlignment = horizontalAlignment) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.SemiBold,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SpeedBadgeItem(
    modifier: Modifier = Modifier,
    title: String,
    mainVal: String,
    subVal: String,
    color: Color
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = mainVal,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1
            )
            Text(
                text = subVal,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun InteractiveProfileChartCard(
    title: String,
    subtitle: String,
    data: List<Pair<Long, Double>>,
    lineColor: Color,
    fixedMinZero: Boolean
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = lineColor,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            ProfileCurveCanvas(
                data = data,
                lineColor = lineColor,
                fixedMinZero = fixedMinZero,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
            )
        }
    }
}

@Composable
private fun ProfileCurveCanvas(
    data: List<Pair<Long, Double>>,
    lineColor: Color,
    fixedMinZero: Boolean,
    modifier: Modifier = Modifier
) {
    if (data.size < 2) return
    val rawMinV = data.minOf { it.second }
    val rawMaxV = data.maxOf { it.second }
    val minV = if (fixedMinZero) 0.0 else (rawMinV - (rawMaxV - rawMinV).coerceAtLeast(10.0) * 0.12)
    val maxV = if (fixedMinZero) {
        (rawMaxV * 1.08).coerceAtLeast(10.0)
    } else {
        rawMaxV + (rawMaxV - rawMinV).coerceAtLeast(10.0) * 0.12
    }
    val valRange = (maxV - minV).coerceAtLeast(1.0)
    val minT = data.first().first
    val maxT = data.last().first
    val timeRange = (maxT - minT).coerceAtLeast(1L)
    val gridLineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val chartH = h - 12f

        for (i in 0..4) {
            val gy = chartH * i / 4f
            drawLine(
                color = gridLineColor,
                start = Offset(0f, gy),
                end = Offset(w, gy),
                strokeWidth = 1f
            )
        }

        val linePath = Path()
        val fillPath = Path()
        val step = maxOf(1, data.size / 200)
        var first = true
        var lastPx = 0f

        for (i in data.indices step step) {
            val px = (((data[i].first - minT).toDouble() / timeRange) * w).toFloat()
            val py = (chartH - ((data[i].second - minV) / valRange) * chartH).toFloat()
                .coerceIn(0f, chartH)
            if (first) {
                linePath.moveTo(px, py)
                fillPath.moveTo(px, chartH)
                fillPath.lineTo(px, py)
                first = false
            } else {
                linePath.lineTo(px, py)
                fillPath.lineTo(px, py)
            }
            lastPx = px
        }
        fillPath.lineTo(lastPx, chartH)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                listOf(lineColor.copy(alpha = 0.28f), lineColor.copy(alpha = 0.02f)),
                startY = 0f,
                endY = chartH
            )
        )
        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(width = 3.5f, cap = StrokeCap.Round)
        )
    }
}

/**
 * Full Manual Editor Dialog allowing the user to manually edit EVERY single field of the Flight Log.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullFlightLogManualEditDialog(
    initial: FlightRecord,
    onDismiss: () -> Unit,
    onSave: (FlightRecord) -> Unit
) {
    var flightNumber by remember { mutableStateOf(initial.flightNumber) }
    var airline by remember { mutableStateOf(initial.airline) }
    var airlineName by remember { mutableStateOf(initial.airlineName) }
    var date by remember { mutableStateOf(initial.date) }
    var origin by remember { mutableStateOf(initial.origin) }
    var originName by remember { mutableStateOf(initial.originName) }
    var destination by remember { mutableStateOf(initial.destination) }
    var destinationName by remember { mutableStateOf(initial.destinationName) }
    var aircraftType by remember { mutableStateOf(initial.aircraftType) }
    var registration by remember { mutableStateOf(initial.registration) }
    var seatNumber by remember { mutableStateOf(initial.seatNumber) }
    var boardingGate by remember { mutableStateOf(initial.boardingGate) }
    var passengerName by remember { mutableStateOf(initial.passengerName) }

    var takeoffTimeDisplay by remember {
        mutableStateOf(initial.takeoffTimeDisplay.ifBlank { TimeUtils.formatLocalTime(initial.takeoffTimeUtc) })
    }
    var landingTimeDisplay by remember {
        mutableStateOf(initial.landingTimeDisplay.ifBlank { TimeUtils.formatLocalTime(initial.landingTimeUtc) })
    }
    var totalFlightTimeDisplay by remember {
        mutableStateOf(initial.totalFlightTimeDisplay.ifBlank { TimeUtils.formatDuration(initial.totalFlightTimeMs) })
    }
    var totalDistanceKm by remember { mutableStateOf(String.format(Locale.US, "%.0f", initial.totalDistanceKm)) }

    var v1Knots by remember { mutableStateOf(String.format(Locale.US, "%.0f", initial.v1Knots)) }
    var v2Knots by remember { mutableStateOf(String.format(Locale.US, "%.0f", initial.v2Knots)) }
    var vappKnots by remember { mutableStateOf(String.format(Locale.US, "%.0f", initial.vappKnots)) }
    var departureRunway by remember { mutableStateOf(initial.departureRunway) }
    var arrivalRunway by remember { mutableStateOf(initial.arrivalRunway) }
    var departureTerminal by remember { mutableStateOf(initial.departureTerminal) }
    var arrivalTerminal by remember { mutableStateOf(initial.arrivalTerminal) }

    var maxAltitudeM by remember { mutableStateOf(String.format(Locale.US, "%.0f", initial.maxAltitudeM)) }
    var maxSpeedKnots by remember { mutableStateOf(String.format(Locale.US, "%.0f", initial.maxSpeedKnots)) }
    var avgSpeedKnots by remember { mutableStateOf(String.format(Locale.US, "%.0f", initial.avgSpeedKnots)) }
    var minCabinPressureHpa by remember { mutableStateOf(String.format(Locale.US, "%.1f", initial.minCabinPressureHpa)) }
    var maxCabinPressureHpa by remember { mutableStateOf(String.format(Locale.US, "%.1f", initial.maxCabinPressureHpa)) }
    var maxPitchDeg by remember { mutableStateOf(String.format(Locale.US, "%.1f", initial.maxPitchDeg)) }
    var maxRollDeg by remember { mutableStateOf(String.format(Locale.US, "%.1f", initial.maxRollDeg)) }
    var touchdownG by remember { mutableStateOf(String.format(Locale.US, "%.2f", initial.touchdownG)) }
    var aiReport by remember { mutableStateOf(initial.aiReport) }

    fun buildUpdatedRecord(): FlightRecord = initial.copy(
        flightNumber = flightNumber.trim(),
        airline = airline.trim(),
        airlineName = airlineName.trim(),
        date = date.trim(),
        origin = origin.trim().uppercase(),
        originName = originName.trim(),
        destination = destination.trim().uppercase(),
        destinationName = destinationName.trim(),
        aircraftType = aircraftType.trim(),
        registration = registration.trim().uppercase(),
        seatNumber = seatNumber.trim().uppercase(),
        boardingGate = boardingGate.trim().uppercase(),
        passengerName = passengerName.trim(),
        takeoffTimeDisplay = takeoffTimeDisplay.trim(),
        landingTimeDisplay = landingTimeDisplay.trim(),
        totalFlightTimeDisplay = totalFlightTimeDisplay.trim(),
        totalDistanceKm = totalDistanceKm.toDoubleOrNull() ?: initial.totalDistanceKm,
        v1Knots = v1Knots.toDoubleOrNull() ?: initial.v1Knots,
        v2Knots = v2Knots.toDoubleOrNull() ?: initial.v2Knots,
        vappKnots = vappKnots.toDoubleOrNull() ?: initial.vappKnots,
        departureRunway = departureRunway.trim(),
        arrivalRunway = arrivalRunway.trim(),
        departureTerminal = departureTerminal.trim(),
        arrivalTerminal = arrivalTerminal.trim(),
        maxAltitudeM = maxAltitudeM.toDoubleOrNull() ?: initial.maxAltitudeM,
        maxSpeedKnots = maxSpeedKnots.toDoubleOrNull() ?: initial.maxSpeedKnots,
        avgSpeedKnots = avgSpeedKnots.toDoubleOrNull() ?: initial.avgSpeedKnots,
        minCabinPressureHpa = minCabinPressureHpa.toFloatOrNull() ?: initial.minCabinPressureHpa,
        maxCabinPressureHpa = maxCabinPressureHpa.toFloatOrNull() ?: initial.maxCabinPressureHpa,
        maxPitchDeg = maxPitchDeg.toFloatOrNull() ?: initial.maxPitchDeg,
        maxRollDeg = maxRollDeg.toFloatOrNull() ?: initial.maxRollDeg,
        touchdownG = touchdownG.toFloatOrNull() ?: initial.touchdownG,
        aiReport = aiReport.trim()
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = s("手动编辑全部飞行日志数据", "Edit All Flight Log Data"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = s("关闭", "Close"))
                        }
                    },
                    actions = {
                        TextButton(onClick = { onSave(buildUpdatedRecord()) }) {
                            Text(
                                text = s("保存", "Save"),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = s("1. 航班、航司、机型与注册号", "1. Flight, Airline, Aircraft & Registration"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(flightNumber, { flightNumber = it }, label = { Text(s("航班号", "Flight No.")) }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(airline, { airline = it }, label = { Text(s("航司代码", "Airline Code")) }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(date, { date = it }, label = { Text(s("日期", "Date")) }, modifier = Modifier.weight(1.2f), singleLine = true)
                }
                OutlinedTextField(airlineName, { airlineName = it }, label = { Text(s("航空公司名称", "Airline Name")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(aircraftType, { aircraftType = it }, label = { Text(s("机型 Aircraft Type", "Aircraft Type")) }, modifier = Modifier.weight(1.2f), singleLine = true)
                    OutlinedTextField(registration, { registration = it }, label = { Text(s("注册号 Reg", "Registration")) }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(origin, { origin = it }, label = { Text(s("出发代码", "Origin IATA")) }, modifier = Modifier.weight(0.8f), singleLine = true)
                    OutlinedTextField(originName, { originName = it }, label = { Text(s("出发机场名称", "Origin Airport")) }, modifier = Modifier.weight(1.4f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(destination, { destination = it }, label = { Text(s("目的代码", "Dest IATA")) }, modifier = Modifier.weight(0.8f), singleLine = true)
                    OutlinedTextField(destinationName, { destinationName = it }, label = { Text(s("目的机场名称", "Dest Airport")) }, modifier = Modifier.weight(1.4f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(seatNumber, { seatNumber = it }, label = { Text(s("座位 Seat", "Seat")) }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(boardingGate, { boardingGate = it }, label = { Text(s("登机口 Gate", "Gate")) }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(passengerName, { passengerName = it }, label = { Text(s("乘机人 Pax", "Passenger")) }, modifier = Modifier.weight(1.3f), singleLine = true)
                }

                HorizontalDivider()
                Text(
                    text = s("2. 起降时间、总时间、总里程与跑道", "2. Times, Duration, Distance & Runways"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(takeoffTimeDisplay, { takeoffTimeDisplay = it }, label = { Text(s("起飞时间", "Takeoff Time")) }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(landingTimeDisplay, { landingTimeDisplay = it }, label = { Text(s("降落时间", "Landing Time")) }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(totalFlightTimeDisplay, { totalFlightTimeDisplay = it }, label = { Text(s("飞行总时间", "Total Duration")) }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(totalDistanceKm, { totalDistanceKm = it }, label = { Text(s("飞行总里程 (km)", "Distance (km)")) }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(departureRunway, { departureRunway = it }, label = { Text(s("起飞跑道", "Dep Runway")) }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(arrivalRunway, { arrivalRunway = it }, label = { Text(s("降落跑道", "Landing Runway")) }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(departureTerminal, { departureTerminal = it }, label = { Text(s("出发航站楼", "Dep Terminal")) }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(arrivalTerminal, { arrivalTerminal = it }, label = { Text(s("到达航站楼", "Arr Terminal")) }, modifier = Modifier.weight(1f), singleLine = true)
                }

                HorizontalDivider()
                Text(
                    text = s("3. V1 / V2 / Vapp 速度与四传感器极值", "3. V1 / V2 / Vapp Speeds & Sensor Extremes"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(v1Knots, { v1Knots = it }, label = { Text(s("V1 决断 (kts)", "V1 (kts)")) }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(v2Knots, { v2Knots = it }, label = { Text(s("V2 抬轮 (kts)", "V2 (kts)")) }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(vappKnots, { vappKnots = it }, label = { Text(s("Vapp 接地 (kts)", "Vapp (kts)")) }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(maxAltitudeM, { maxAltitudeM = it }, label = { Text(s("最大高度 (m)", "Max Alt (m)")) }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(maxSpeedKnots, { maxSpeedKnots = it }, label = { Text(s("最大地速 (kts)", "Max Spd (kts)")) }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(avgSpeedKnots, { avgSpeedKnots = it }, label = { Text(s("平均地速 (kts)", "Avg Spd (kts)")) }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(minCabinPressureHpa, { minCabinPressureHpa = it }, label = { Text(s("最低气压 (hPa)", "Min Cabin (hPa)")) }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(maxCabinPressureHpa, { maxCabinPressureHpa = it }, label = { Text(s("最高气压 (hPa)", "Max Cabin (hPa)")) }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(maxPitchDeg, { maxPitchDeg = it }, label = { Text(s("最大俯仰角 (°)", "Max Pitch (°)")) }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(maxRollDeg, { maxRollDeg = it }, label = { Text(s("最大坡度角 (°)", "Max Roll (°)")) }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(touchdownG, { touchdownG = it }, label = { Text(s("接地G值 (G)", "Touchdown (G)")) }, modifier = Modifier.weight(1f), singleLine = true)
                }

                HorizontalDivider()
                Text(
                    text = s("4. AI 飞行报告正文 (可手工编辑)", "4. AI Flight Report Text (Editable)"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = aiReport,
                    onValueChange = { aiReport = it },
                    label = { Text(s("AI 飞行报告内容", "AI Flight Report Content")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                )

                Button(
                    onClick = { onSave(buildUpdatedRecord()) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = s("保存全部修改并刷新单页 A4 PDF", "Save All Edits & Refresh Single-Page A4 PDF"),
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
