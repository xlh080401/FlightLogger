package com.flog4.app.ui.recording

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.flog4.app.data.FlightPhase
import com.flog4.app.ui.i18n.Md3LanguageSwitchPill
import com.flog4.app.ui.i18n.s
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    flightId: Long,
    onBack: () -> Unit,
    onRecordingCompleted: (Long) -> Unit,
    viewModel: RecordingViewModel = hiltViewModel()
) {
    val sensorPoint by viewModel.latestSensorPoint.collectAsState()
    val gpsSignal by viewModel.gpsSignal.collectAsState()
    val availability by viewModel.sensorAvailability.collectAsState()
    val activeFlightId by viewModel.activeRecordingFlightId.collectAsState()
    val isSimulating by viewModel.isSimulating.collectAsState()
    val flightRecord by viewModel.flightRecord.collectAsState()

    val isRecording = activeFlightId != null

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.startRecording(simulate = false)
    }

    LaunchedEffect(flightId) {
        viewModel.attachOrCreateFlight(flightId)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = flightRecord?.let {
                                s(
                                    "传感器仪表盘 · ${it.flightNumber.ifBlank { "新航班" }} (${it.origin.ifBlank { "DEP" }}→${it.destination.ifBlank { "ARR" }})",
                                    "Avionics Cockpit · ${it.flightNumber.ifBlank { "Flight" }} (${it.origin.ifBlank { "DEP" }}→${it.destination.ifBlank { "ARR" }})"
                                )
                            } ?: s("飞行传感器实时仪表盘", "Live Avionics Sensor Cockpit"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isSimulating) {
                                s("⚡ 实时物理模拟飞行中 (12x 加速)", "⚡ Live Physics Flight Simulation (12x)")
                            } else if (isRecording) {
                                s("● 正在实时采集手机传感器与 GPS 数据", "● Recording Hardware Sensors & GNSS Live")
                            } else {
                                s("待机中 · 点击下方按钮开始采集", "Standby · Tap below to start recording")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
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
                    Md3LanguageSwitchPill(modifier = Modifier.padding(end = 12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        val pt = sensorPoint
        val pitch = pt?.pitchDeg ?: 0f
        val roll = pt?.rollDeg ?: 0f
        val yaw = pt?.yawDeg ?: 0f
        val gForce = pt?.gForce ?: 1.00f
        val accelX = pt?.accelX ?: 0f
        val accelY = pt?.accelY ?: 0f
        val accelZ = pt?.accelZ ?: 9.81f
        val pressure = pt?.pressureHpa ?: 1013.25f
        val cabinAlt = pt?.cabinAltitudeM ?: 0.0
        val gpsAlt = pt?.gpsAltitudeM ?: 0.0
        val speedKts = (pt?.gpsSpeedMps ?: 0f) * 1.94384f
        val speedKmh = (pt?.gpsSpeedMps ?: 0f) * 3.6f
        val phase = pt?.phase ?: FlightPhase.GROUND

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // =========================================================================
            // 1. FLIGHT PHASE & SENSOR STATUS BAR
            // =========================================================================
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = s("当前飞行阶段 · FLIGHT PHASE", "CURRENT FLIGHT PHASE"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                        )
                        Text(
                            text = when (phase) {
                                FlightPhase.GROUND -> s("GROUND · 地面停靠", "GROUND · Parked at Gate")
                                FlightPhase.TAXI -> s("TAXI · 地面滑行", "TAXI · Ground Taxiing")
                                FlightPhase.TAKEOFF -> s("TAKEOFF · 起飞滑跑 (检测 V1/V2)", "TAKEOFF · Roll (Detecting V1/V2)")
                                FlightPhase.CLIMB -> s("CLIMB · 初始爬升", "CLIMB · Initial Climb")
                                FlightPhase.CRUISE -> s("CRUISE · 高空巡航", "CRUISE · High-Altitude Cruise")
                                FlightPhase.DESCENT -> s("DESCENT · 下降进近", "DESCENT · Approach & Descent")
                                FlightPhase.LANDING -> s("LANDING · 接地着陆 (检测 Vapp/跑道)", "LANDING · Touchdown (Vapp/Rwy)")
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = s(
                                "GNSS 卫星: ${gpsSignal.satellitesInFix}/${gpsSignal.satellitesVisible}",
                                "GNSS Sats: ${gpsSignal.satellitesInFix}/${gpsSignal.satellitesVisible}"
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // =========================================================================
            // 2. ARTIFICIAL HORIZON / LEVEL SENSOR (航空水平仪 / 姿态仪)
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
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = s(
                                "① 手机水平仪 · 航空人工地平仪 (ADI)",
                                "① Level Sensor · Attitude Director Indicator (ADI)"
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = CircleShape
                        ) {
                            Text(
                                text = if (availability.hasLevelSensor) {
                                    s("硬件在线", "Sensor Online")
                                } else {
                                    s("模拟模式", "Sim Mode")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ArtificialHorizonGauge(
                            pitchDeg = pitch,
                            rollDeg = roll,
                            modifier = Modifier
                                .size(148.dp)
                                .clip(CircleShape)
                        )

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AttitudeReadoutRow(
                                label = s("俯仰角 Pitch", "Pitch Angle"),
                                value = String.format(Locale.US, "%+.1f°", pitch),
                                accent = MaterialTheme.colorScheme.primary
                            )
                            AttitudeReadoutRow(
                                label = s("横滚角 Roll", "Roll Angle"),
                                value = String.format(Locale.US, "%+.1f°", roll),
                                accent = MaterialTheme.colorScheme.tertiary
                            )
                            AttitudeReadoutRow(
                                label = s("方位角 Yaw", "Heading Yaw"),
                                value = String.format(Locale.US, "%.0f°", yaw),
                                accent = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // 3. FOUR-SENSOR TELEMETRY GRID (GPS, BAROMETER, ACCELEROMETER)
            // =========================================================================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // GPS Speed & Altitude Card
                ElevatedCard(
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = s("② GPS 卫星测速与高度", "② GNSS Speed & Altitude"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = String.format(Locale.US, "%.0f kts", speedKts),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                s("地速 %.0f km/h", "GS %.0f km/h"),
                                speedKmh
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        Text(
                            text = String.format(
                                Locale.US,
                                s("海拔: %.0f m (%.0f ft)", "Alt: %.0f m (%.0f ft)"),
                                gpsAlt,
                                gpsAlt * 3.28084
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                s("坐标: %.4f, %.4f", "Coord: %.4f, %.4f"),
                                pt?.latitude ?: 0.0,
                                pt?.longitude ?: 0.0
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Barometer Cabin Pressure Card
                ElevatedCard(
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = s("③ 气压计 · 机舱气压", "③ Barometer · Cabin hPa"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = String.format(Locale.US, "%.1f hPa", pressure),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                s("客舱气压高度: %.0f m", "Cabin Alt: %.0f m"),
                                cabinAlt
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        Text(
                            text = if (availability.hasBarometer) {
                                s("硬件气压计实时采集中", "Hardware Barometer Active")
                            } else {
                                s("标准客舱增压曲线解算", "ISA Cabin Pressurization")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = s("用于绘制机舱气压变化图", "Plots Cabin Pressure Curve"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Accelerometer G-Force Card
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = s("④ 三轴加速度计 · 过载监测", "④ 3-Axis Accelerometer · G-Load"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    s("合过载 %.2f G", "Total %.2f G"),
                                    gForce
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AccelAxisCard(
                            modifier = Modifier.weight(1f),
                            label = s("X轴 (横向)", "X-Axis (Lat)"),
                            value = String.format(Locale.US, "%+.2f m/s²", accelX)
                        )
                        AccelAxisCard(
                            modifier = Modifier.weight(1f),
                            label = s("Y轴 (纵向)", "Y-Axis (Lon)"),
                            value = String.format(Locale.US, "%+.2f m/s²", accelY)
                        )
                        AccelAxisCard(
                            modifier = Modifier.weight(1f),
                            label = s("Z轴 (垂直)", "Z-Axis (Vert)"),
                            value = String.format(Locale.US, "%+.2f m/s²", accelZ)
                        )
                    }
                }
            }

            // =========================================================================
            // 4. RECORDING & SIMULATION CONTROLS
            // =========================================================================
            if (!isRecording) {
                Button(
                    onClick = {
                        val perms = buildList {
                            add(Manifest.permission.ACCESS_FINE_LOCATION)
                            add(Manifest.permission.ACCESS_COARSE_LOCATION)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }.toTypedArray()
                        permissionLauncher.launch(perms)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = s(
                            "启动实机传感器采集 (水平仪+加速度计+气压计+GPS)",
                            "Start Hardware Sensor Recording (Level+Accel+Baro+GPS)"
                        ),
                        fontWeight = FontWeight.Bold
                    )
                }

                FilledTonalButton(
                    onClick = { viewModel.startRecording(simulate = true) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.Speed, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = s(
                            "开启实时仪表盘模拟飞行演示 (观察起飞抬轮/巡航/着陆)",
                            "Start Live Simulated Flight Demo (Takeoff / Cruise / Landing)"
                        ),
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                if (!isSimulating) {
                    OutlinedButton(
                        onClick = { viewModel.switchToSimulation() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = s(
                                "切换为实时物理模拟飞行轨迹",
                                "Switch to Live Physics Flight Simulation"
                            )
                        )
                    }
                }

                Button(
                    onClick = { viewModel.stopRecording(onRecordingCompleted) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = s(
                            "结束飞行采集 → 解算 V1/V2/Vapp/跑道 并由 GPU 生成 AI 报告",
                            "Stop & Analyze → Compute V1/V2/Vapp/Runway & GPU AI Report"
                        ),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun AccelAxisCard(
    modifier: Modifier,
    label: String,
    value: String
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun AttitudeReadoutRow(label: String, value: String, accent: Color) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accent
            )
        }
    }
}

@Composable
private fun ArtificialHorizonGauge(
    pitchDeg: Float,
    rollDeg: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.background(Color(0xFF0F172A))) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val pitchOffsetPx = (pitchDeg.coerceIn(-35f, 35f) / 35f) * (h * 0.38f)

        clipRect {
            rotate(degrees = -rollDeg, pivot = Offset(cx, cy)) {
                // Sky
                drawRect(
                    color = Color(0xFF0284C7),
                    topLeft = Offset(-w, -h),
                    size = Size(w * 3f, cy + h + pitchOffsetPx)
                )
                // Ground
                drawRect(
                    color = Color(0xFF78350F),
                    topLeft = Offset(-w, cy + pitchOffsetPx),
                    size = Size(w * 3f, h * 2f)
                )
                // Horizon Line
                drawLine(
                    color = Color.White,
                    start = Offset(0f, cy + pitchOffsetPx),
                    end = Offset(w, cy + pitchOffsetPx),
                    strokeWidth = 3f
                )
                // Pitch ladder rungs (-20, -10, +10, +20 deg)
                for (deg in listOf(-20, -10, 10, 20)) {
                    val rungY = cy + pitchOffsetPx - (deg / 35f) * (h * 0.38f)
                    val halfLen = if (deg % 20 == 0) w * 0.22f else w * 0.13f
                    drawLine(
                        color = Color.White.copy(alpha = 0.85f),
                        start = Offset(cx - halfLen, rungY),
                        end = Offset(cx + halfLen, rungY),
                        strokeWidth = 2f
                    )
                }
            }
        }

        // Fixed aircraft reference wings in center
        drawLine(
            color = Color(0xFFFACC15),
            start = Offset(cx - w * 0.28f, cy),
            end = Offset(cx - w * 0.08f, cy),
            strokeWidth = 5f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color(0xFFFACC15),
            start = Offset(cx + w * 0.08f, cy),
            end = Offset(cx + w * 0.28f, cy),
            strokeWidth = 5f,
            cap = StrokeCap.Round
        )
        drawCircle(
            color = Color(0xFFFACC15),
            radius = 5f,
            center = Offset(cx, cy)
        )
        // Outer bezel ring
        drawCircle(
            color = Color.White.copy(alpha = 0.7f),
            radius = w / 2f - 2f,
            center = Offset(cx, cy),
            style = Stroke(width = 4f)
        )
    }
}
