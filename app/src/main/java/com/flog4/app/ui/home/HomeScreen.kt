package com.flog4.app.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.flog4.app.data.FlightRecord
import com.flog4.app.data.FlightStatus
import com.flog4.app.service.SensorCollectionService
import com.flog4.app.ui.i18n.Md3LanguageSwitchPill
import com.flog4.app.ui.i18n.s
import com.flog4.app.util.AirlineLogoRenderer
import com.flog4.app.util.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStartWithBoardingPass: () -> Unit,
    onStartDirectRecording: () -> Unit,
    onOpenFlightDetail: (Long) -> Unit,
    onResumeActiveRecording: (Long) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val flights by viewModel.flights.collectAsState()
    val isGeneratingDemo by viewModel.isGeneratingDemo.collectAsState()
    val activeFlightId by SensorCollectionService.recordingFlightId.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.FlightTakeoff,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = s("AeroLog · 飞行日志", "AeroLog · Flight Log"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = s(
                                    "100% 离线传感器融合 · 手机 GPU AI",
                                    "Offline Sensor Fusion · Local GPU AI"
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    Md3LanguageSwitchPill(modifier = Modifier.padding(end = 12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Active recording MD3 tonal banner if recording is in progress
            if (activeFlightId != null) {
                item {
                    ElevatedCard(
                        onClick = { onResumeActiveRecording(activeFlightId!!) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.Sensors,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = s(
                                            "正在后台采集飞行传感器数据…",
                                            "Recording Flight Telemetry in Background…"
                                        ),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = s(
                                            "点击返回实时航空传感器仪表盘",
                                            "Tap to return to live avionics sensor cockpit"
                                        ),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        }
                    }
                }
            }

            // MD3 Hero Action Card
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = s("创建新飞行日志", "Create New Flight Log"),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = s("NEW FLIGHT LOGBOOK ENTRY", "ON-DEVICE SENSOR & AI"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                shape = CircleShape
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.AirplanemodeActive,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = s("禁止联网 · 纯离线", "Zero Network · Offline"),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        Text(
                            text = s(
                                "融合手机水平仪(俯仰/横滚)、加速度计(G值)、气压计(hPa)与GPS卫星数据，离线匹配全球 4,562 座机场跑道，并通过手机 GPU 本地推理生成 AI 飞行报告与单页 A4 PDF。",
                                "Fuses phone level (Pitch/Roll), 3-axis accelerometer (G-Force), barometer (hPa), and GNSS data, matches 4,562 global airport runways offline, and runs a local GPU neural model to generate a single-page A4 PDF flight log."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.88f)
                        )

                        // Primary MD3 Filled Button: Boarding Pass OCR + Guided Aircraft/Reg Input
                        Button(
                            onClick = onStartWithBoardingPass,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp),
                            shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(Icons.Default.DocumentScanner, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = s(
                                    "上传识别登机牌 (OCR) + 填写机型/注册号",
                                    "Scan Boarding Pass (OCR) + Aircraft/Reg"
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Secondary MD3 Action Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            FilledTonalButton(
                                onClick = onStartDirectRecording,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                shape = MaterialTheme.shapes.small,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            ) {
                                Icon(
                                    Icons.Default.Explore,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = s("直接实机记录", "Live Cockpit"),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1
                                )
                            }

                            OutlinedButton(
                                onClick = { viewModel.createInstantDemoFlight(onOpenFlightDetail) },
                                enabled = !isGeneratingDemo,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                shape = MaterialTheme.shapes.small,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            ) {
                                if (isGeneratingDemo) {
                                    CircularProgressIndicator(
                                        Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = s("一键示例日志", "Instant Demo"),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // MD3 3-Column Sensor & AI Capability Cards
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Md3CapabilityCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Speed,
                        title = s("水平仪/加速度", "Attitude & G"),
                        subtitle = s("V1/V2/Vapp/过载", "V1/V2/Vapp/G")
                    )
                    Md3CapabilityCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Public,
                        title = s("GPS/气压计", "GNSS & Baro"),
                        subtitle = s("4562跑道/气压图", "4,562 Rwys/hPa")
                    )
                    Md3CapabilityCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Memory,
                        title = s("手机GPU推理", "Mobile GPU AI"),
                        subtitle = s("本地AI/单页A4", "AI & A4 PDF")
                    )
                }
            }

            // Section Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = s("历史飞行日志 (${flights.size})", "Flight Logbook (${flights.size})"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = s("支持全字段二次编辑与单页A4导出", "Editable · Single-Page A4 PDF"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (flights.isEmpty()) {
                item {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                shape = CircleShape,
                                modifier = Modifier.size(56.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Flight,
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = s("暂无飞行日志记录", "No Flight Logs Yet"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = s(
                                    "点击上方「上传识别登机牌 (OCR)」开始新航班，或点击「一键示例日志」立即体验包含三条完整传感器曲线、GPU AI 简报、全字段手动编辑与单页 A4 PDF 导出的完整功能。",
                                    "Tap 'Scan Boarding Pass (OCR)' above to start a new flight, or tap 'Instant Demo' to explore the 3 sensor curves, on-device GPU AI report, full manual editor, and single-page A4 PDF export."
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(flights, key = { it.id }) { flight ->
                    Md3FlightHistoryCard(
                        flight = flight,
                        onClick = {
                            if (flight.status == FlightStatus.RECORDING && activeFlightId == flight.id) {
                                onResumeActiveRecording(flight.id)
                            } else {
                                onOpenFlightDetail(flight.id)
                            }
                        },
                        onDelete = { viewModel.deleteFlight(flight) }
                    )
                }
            }
        }
    }
}

@Composable
private fun Md3CapabilityCard(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    OutlinedCard(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun Md3FlightHistoryCard(
    flight: FlightRecord,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val airlineCode = flight.airline.ifBlank { flight.flightNumber.take(2) }
    val logoBitmap by produceState<Bitmap?>(initialValue = null, airlineCode) {
        value = withContext(Dispatchers.IO) {
            AirlineLogoRenderer.render(context, airlineCode, AirlineLogoRenderer.UI_SIZE_PX)
        }
    }

    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                    logoBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(MaterialTheme.shapes.small)
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = flight.flightNumber.ifBlank { s("航班 #${flight.id}", "Flight #${flight.id}") },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    text = "${flight.origin.ifBlank { "DEP" }} → ${flight.destination.ifBlank { "ARR" }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = flight.date.ifBlank { TimeUtils.formatDate(flight.createdAt) },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        Text(
                            text = "${flight.aircraftType.ifBlank { s("机型待填", "Aircraft TBD") }} · ${s("注册号", "Reg")} ${flight.registration.ifBlank { "—" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = s("删除", "Delete"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // MD3 2x2 Tonal Metric Grid (prevents any horizontal crowding in both Chinese & English)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MiniMetricCell(
                            modifier = Modifier.weight(1f),
                            label = s("起降时间", "Takeoff / Landing"),
                            value = "${flight.takeoffTimeDisplay.ifBlank { "—" }} → ${flight.landingTimeDisplay.ifBlank { "—" }}"
                        )
                        MiniMetricCell(
                            modifier = Modifier.weight(1f),
                            label = s("总里程 / 总时间", "Distance / Duration"),
                            value = "${flight.totalDistanceKm.roundToInt()} km · ${flight.totalFlightTimeDisplay.ifBlank { TimeUtils.formatDuration(flight.totalFlightTimeMs) }}"
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MiniMetricCell(
                            modifier = Modifier.weight(1f),
                            label = s("特征速度 V1 / V2 / Vapp", "Speeds V1 / V2 / Vapp"),
                            value = "${flight.v1Knots.roundToInt()} / ${flight.v2Knots.roundToInt()} / ${flight.vappKnots.roundToInt()} kts"
                        )
                        MiniMetricCell(
                            modifier = Modifier.weight(1f),
                            label = s("降落跑道", "Landing Runway"),
                            value = flight.arrivalRunway.ifBlank { "—" },
                            highlight = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniMetricCell(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    highlight: Boolean = false
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
