package com.flog4.app.ui.scanner

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.flog4.app.ui.i18n.Md3LanguageSwitchPill
import com.flog4.app.ui.i18n.s

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardingPassScreen(
    onBack: () -> Unit,
    onStartLiveRecording: (Long) -> Unit,
    onOpenGeneratedDetail: (Long) -> Unit,
    viewModel: ScannerViewModel = hiltViewModel()
) {
    val form by viewModel.form.collectAsState()
    val previewBitmap by viewModel.previewBitmap.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val ocrStatus by viewModel.ocrStatusMessage.collectAsState()

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.processImageUri(it) }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        bitmap?.let { viewModel.processBitmap(it) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = s("登机牌 OCR 与机型引导", "Boarding Pass OCR & Aircraft"),
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // =========================================================================
            // STEP 1: BOARDING PASS IMAGE UPLOAD & OFFLINE OCR
            // =========================================================================
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = "STEP 1",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = s(
                                "上传登机牌图片 (端侧离线 OCR 自动提取)",
                                "Upload Boarding Pass (On-Device Offline OCR)"
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = s(
                            "支持从手机相册上传登机牌照片、直接拍照或一键生成示例登机牌。端侧离线 OCR 模型将自动提取航班号、航司、出发地、目的地、日期与座位号。",
                            "Upload a boarding pass from your gallery, take a photo, or generate a sample pass. On-device offline OCR automatically extracts flight number, airline, origin, destination, date, and seat."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.88f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { galleryLauncher.launch("image/*") },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 46.dp),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Icon(
                                Icons.Default.PhotoLibrary,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = s("相册上传登机牌", "From Gallery"),
                                maxLines = 1
                            )
                        }

                        FilledTonalButton(
                            onClick = { cameraLauncher.launch(null) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 46.dp),
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(
                                Icons.Default.PhotoCamera,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = s("拍摄登机牌", "Take Photo"),
                                maxLines = 1
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { viewModel.generateAndScanSampleBoardingPass() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 46.dp),
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Icon(
                            Icons.Default.AutoFixHigh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = s(
                                "生成示例登机牌图片并执行 OCR 识别 (切换航司预设)",
                                "Generate Sample Boarding Pass & Run Offline OCR"
                            )
                        )
                    }

                    if (isScanning) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = s("正在运行端侧离线 OCR 识别…", "Running on-device offline OCR…"),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    previewBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = s("登机牌预览", "Boarding pass preview"),
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                        )
                    }

                    ocrStatus?.let { msg ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // OCR EXTRACTED FIELDS (ALL EDITABLE)
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
                    Text(
                        text = s(
                            "OCR 提取结果 (所有字段均可随时修改)",
                            "OCR Extracted Fields (All Editable)"
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = form.flightNumber,
                            onValueChange = { viewModel.updateFlightNumber(it) },
                            label = { Text(s("航班号 Flight No.", "Flight Number")) },
                            placeholder = { Text("CA1501") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = form.airline,
                            onValueChange = { viewModel.updateAirline(it, form.airlineName) },
                            label = { Text(s("航司代码 IATA", "Airline IATA")) },
                            placeholder = { Text("CA / MU / CZ") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = form.airlineName,
                        onValueChange = { viewModel.updateAirline(form.airline, it) },
                        label = { Text(s("航空公司名称 Airline Name", "Airline Name")) },
                        placeholder = { Text(s("如 中国国际航空 (Air China)", "e.g. Air China")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = form.origin,
                            onValueChange = { viewModel.updateOrigin(it) },
                            label = { Text(s("出发机场 Origin", "Origin IATA/ICAO")) },
                            placeholder = { Text("PEK") },
                            supportingText = { if (form.originName.isNotBlank()) Text(form.originName) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = form.destination,
                            onValueChange = { viewModel.updateDestination(it) },
                            label = { Text(s("目的机场 Dest", "Dest IATA/ICAO")) },
                            placeholder = { Text("SHA") },
                            supportingText = { if (form.destinationName.isNotBlank()) Text(form.destinationName) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text(
                        text = s("常用航线快捷填入：", "Quick Route Presets:"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val quickRoutes = listOf(
                        Triple("PEK", "SHA", s("北京首都 → 上海虹桥", "PEK Beijing → SHA Shanghai")),
                        Triple("PVG", "CAN", s("上海浦东 → 广州白云", "PVG Shanghai → CAN Guangzhou")),
                        Triple("SZX", "TFU", s("深圳宝安 → 成都天府", "SZX Shenzhen → TFU Chengdu")),
                        Triple("PKX", "HKG", s("北京大兴 → 香港", "PKX Beijing → HKG Hong Kong"))
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        quickRoutes.forEach { (dep, arr, label) ->
                            AssistChip(
                                onClick = {
                                    viewModel.updateOrigin(dep)
                                    viewModel.updateDestination(arr)
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = form.date,
                            onValueChange = { viewModel.updateDate(it) },
                            label = { Text(s("日期 Date", "Date")) },
                            placeholder = { Text("YYYY-MM-DD") },
                            singleLine = true,
                            modifier = Modifier.weight(1.35f)
                        )
                        OutlinedTextField(
                            value = form.seatNumber,
                            onValueChange = {
                                viewModel.updateSeatAndGate(it, form.boardingGate, form.cabinClass, form.passengerName)
                            },
                            label = { Text(s("座位 Seat", "Seat")) },
                            placeholder = { Text("12A") },
                            singleLine = true,
                            modifier = Modifier.weight(0.85f)
                        )
                        OutlinedTextField(
                            value = form.boardingGate,
                            onValueChange = {
                                viewModel.updateSeatAndGate(form.seatNumber, it, form.cabinClass, form.passengerName)
                            },
                            label = { Text(s("登机口 Gate", "Gate")) },
                            placeholder = { Text("C32") },
                            singleLine = true,
                            modifier = Modifier.weight(0.85f)
                        )
                    }
                }
            }

            // =========================================================================
            // STEP 2: GUIDED MANUAL INPUT FOR AIRCRAFT TYPE & REGISTRATION (MD3 TONAL)
            // =========================================================================
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                ),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = s("STEP 2 · 手工补全引导", "STEP 2 · GUIDED INPUT"),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = s(
                                "请填写执飞机型与飞机注册号",
                                "Enter Aircraft Type & Registration"
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }

                    Text(
                        text = s(
                            "纸质/电子登机牌通常不包含飞机注册号（Tail Number）与具体子机型。请在此手工输入或点击下方常见机型/注册号快捷标签：",
                            "Boarding passes usually omit the aircraft registration (Tail Number) and exact sub-model. Please enter them manually or tap a quick preset chip below:"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.88f)
                    )

                    OutlinedTextField(
                        value = form.aircraftType,
                        onValueChange = { viewModel.updateAircraftType(it) },
                        label = {
                            Text(s("执飞机型 Aircraft Type (必填引导)", "Aircraft Type (Guided Input)"))
                        },
                        placeholder = {
                            Text(s("例如：Airbus A350-900 / Boeing 737-800 / COMAC C919", "e.g. Airbus A350-900 / Boeing 787-9 / COMAC C919"))
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = s("主流机型快捷选择：", "Popular Aircraft Presets:"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    val popularAircraft = listOf(
                        "Airbus A320neo",
                        "Airbus A321neo",
                        "Airbus A350-900",
                        "Airbus A330-300",
                        "Boeing 737-800",
                        "Boeing 787-9",
                        "Boeing 777-300ER",
                        "COMAC C919",
                        "COMAC ARJ21-700 (C909)"
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        popularAircraft.forEach { acft ->
                            FilterChip(
                                selected = form.aircraftType == acft,
                                onClick = { viewModel.updateAircraftType(acft) },
                                label = { Text(acft, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = form.registration,
                        onValueChange = { viewModel.updateRegistration(it) },
                        label = {
                            Text(s("飞机注册号 Aircraft Registration (必填引导)", "Aircraft Registration / Tail No."))
                        },
                        placeholder = {
                            Text(s("例如：B-308M / B-919A / N12345", "e.g. B-308M / B-919A / N881AA"))
                        },
                        supportingText = {
                            Text(
                                s(
                                    "中国大陆民航客机通常以 B- 开头（如 B-308M、B-919A、B-2088）",
                                    "Examples: B-308M (China), N881AA (US), G-XWBA (UK), JA01XJ (Japan)"
                                )
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    val sampleRegs = listOf("B-308M", "B-919A", "B-321A", "B-2088", "B-1086", "B-LRA", "N881AA")
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        sampleRegs.forEach { reg ->
                            FilterChip(
                                selected = form.registration == reg,
                                onClick = { viewModel.updateRegistration(reg) },
                                label = { Text(reg, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // STEP 3: START SENSOR RECORDING OR SIMULATE FLIGHT
            // =========================================================================
            Button(
                onClick = { viewModel.saveAndStartLiveRecording(onStartLiveRecording) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.Sensors, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = s(
                        "进入实时传感器仪表盘 (水平仪/加速度计/气压计/GPS)",
                        "Open Live Sensor Cockpit (Level / Accel / Baro / GPS)"
                    ),
                    fontWeight = FontWeight.Bold
                )
            }

            FilledTonalButton(
                onClick = { viewModel.saveAndGenerateSimulatedFlightLog(onOpenGeneratedDetail) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = s(
                        "基于当前航班信息模拟完整传感器飞行并生成报告",
                        "Simulate Full Flight Telemetry & Generate GPU AI Report"
                    ),
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
