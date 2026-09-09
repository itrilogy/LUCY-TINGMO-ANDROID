package lab.luxi.tingmo.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import lab.luxi.tingmo.AppContainer
import lab.luxi.tingmo.domain.PickupProfile
import lab.luxi.tingmo.domain.ResultTab
import lab.luxi.tingmo.domain.TimedSegment
import lab.luxi.tingmo.domain.TranscribeMode
import lab.luxi.tingmo.domain.segmentWindowMs
import lab.luxi.tingmo.ui.theme.LuxiCyan
import lab.luxi.tingmo.ui.theme.LuxiGreen
import lab.luxi.tingmo.ui.viewmodel.TranscribeViewModel
import lab.luxi.tingmo.ui.viewmodel.TranscribeViewModelFactory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscribeScreen(container: AppContainer) {
    val vm: TranscribeViewModel = viewModel(factory = TranscribeViewModelFactory(container))
    val state by vm.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            vm.start()
        } else {
            scope.launch {
                val permanentlyDenied = activity != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.RECORD_AUDIO,
                    )
                val result = snackbar.showSnackbar(
                    message = if (permanentlyDenied) {
                        "麦克风权限被拒绝，请到系统设置开启"
                    } else {
                        "需要麦克风权限才能录音"
                    },
                    actionLabel = if (permanentlyDenied) "去设置" else null,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    )
                    context.startActivity(intent)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("听默撰写", fontWeight = FontWeight.SemiBold)
                        Text(
                            buildString {
                                append(state.model?.displayName ?: "未选择模型")
                                if (state.usingOnnx) append(" · ONNX")
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            // —— 集中控制区（图标式，节省纵向空间）——
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                ),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "当前模型",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            )
                            Text(
                                state.model?.displayName ?: "—",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            when {
                                state.isBusy && state.mode == TranscribeMode.BATCH ->
                                    "录音 ${TimedSegment.formatClock(state.elapsedMs)}"
                                state.isBusy ->
                                    "进行中 ${TimedSegment.formatClock(state.elapsedMs)}"
                                state.elapsedMs > 0 ->
                                    "时长 ${TimedSegment.formatClock(state.elapsedMs)}"
                                else -> "窗 ${((state.model?.segmentWindowMs() ?: 20_000L) / 1000)}s"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.isBusy) LuxiCyan else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        ModeIcon(
                            selected = state.mode == TranscribeMode.REALTIME,
                            icon = Icons.Outlined.Bolt,
                            label = "实时",
                            enabled = !state.isBusy,
                            onClick = { vm.setMode(TranscribeMode.REALTIME) },
                        )
                        ModeIcon(
                            selected = state.mode == TranscribeMode.SEMI_REALTIME,
                            icon = Icons.Outlined.Subtitles,
                            label = "半实时",
                            enabled = !state.isBusy,
                            onClick = { vm.setMode(TranscribeMode.SEMI_REALTIME) },
                        )
                        ModeIcon(
                            selected = state.mode == TranscribeMode.BATCH,
                            icon = Icons.Outlined.UploadFile,
                            label = "后置",
                            enabled = !state.isBusy,
                            onClick = { vm.setMode(TranscribeMode.BATCH) },
                        )

                        // 主按钮：开始 / 停止
                        FilledIconButton(
                            onClick = {
                                if (state.isBusy) {
                                    vm.stop()
                                } else {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO,
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (!granted) {
                                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    } else {
                                        vm.start()
                                    }
                                }
                            },
                            modifier = Modifier.size(64.dp),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (state.isBusy) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    LuxiGreen
                                },
                            ),
                        ) {
                            Icon(
                                imageVector = if (state.isBusy) Icons.Outlined.Stop else Icons.Outlined.Mic,
                                contentDescription = if (state.isBusy) "停止" else "开始录音",
                                modifier = Modifier.size(30.dp),
                            )
                        }

                        ModeIcon(
                            selected = false,
                            icon = Icons.Outlined.AutoFixHigh,
                            label = if (state.isPolishing) "加工中" else "AI加工",
                            enabled = !state.isBusy && !state.isPolishing &&
                                (state.rawText.isNotBlank() || state.recordingPath != null),
                            onClick = { vm.aiPolish() },
                        )
                        ModeIcon(
                            selected = false,
                            icon = Icons.Outlined.DeleteOutline,
                            label = "清空",
                            enabled = !state.isBusy,
                            onClick = { vm.clearText() },
                        )
                    }

                    if (state.isBusy) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.Outlined.GraphicEq,
                                contentDescription = null,
                                tint = LuxiCyan,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                when (state.mode) {
                                    TranscribeMode.REALTIME -> "实时听写中"
                                    TranscribeMode.SEMI_REALTIME -> {
                                        val cut = if (state.usingVad) "VAD停顿切段" else "时间窗切段"
                                        "半实时·$cut · 第 ${state.segmentIndex + 1} 段"
                                    }
                                    TranscribeMode.BATCH -> "后置录音中（停止后转写文件）"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = LuxiCyan,
                                modifier = Modifier.weight(1f),
                            )
                            LevelMeter(level = state.peakLevel)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    PickupSensitivityBar(
                        sensitivity = state.pickupSensitivity,
                        label = state.pickupLabel,
                        hint = state.pickupHint,
                        enabled = !state.isBusy,
                        onChange = { vm.setPickupSensitivity(it) },
                    )

                    if (state.mode == TranscribeMode.SEMI_REALTIME || state.usingOnnx) {
                        Spacer(Modifier.height(6.dp))
                        SemiSliceBar(
                            sec = state.semiMaxSpeechSec,
                            enabled = !state.isBusy,
                            onChange = { vm.setSemiMaxSpeechSec(it) },
                        )
                    }
                }
            }

            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            // —— 大文本区：原文 / 纠偏 / 对照 标签 ——
            val tabIndex = when (state.resultTab) {
                ResultTab.RAW -> 0
                ResultTab.CORRECTED -> 1
                ResultTab.BOTH -> 2
            }
            TabRow(selectedTabIndex = tabIndex) {
                Tab(
                    selected = state.resultTab == ResultTab.RAW,
                    onClick = { vm.setResultTab(ResultTab.RAW) },
                    text = { Text("原文") },
                )
                Tab(
                    selected = state.resultTab == ResultTab.CORRECTED,
                    onClick = { vm.setResultTab(ResultTab.CORRECTED) },
                    text = { Text("纠偏") },
                )
                Tab(
                    selected = state.resultTab == ResultTab.BOTH,
                    onClick = { vm.setResultTab(ResultTab.BOTH) },
                    text = { Text("对照") },
                )
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 0.dp),
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        IconButton(
                            onClick = {
                                val text = when (state.resultTab) {
                                    ResultTab.RAW -> state.liveText.ifBlank { state.rawText }
                                    ResultTab.CORRECTED -> state.correctedText.ifBlank {
                                        state.liveText.ifBlank { state.rawText }
                                    }
                                    ResultTab.BOTH -> buildString {
                                        append("【原文】\n")
                                        append(state.rawText.ifBlank { state.liveText })
                                        append("\n\n【纠偏】\n")
                                        append(state.correctedText)
                                    }
                                }
                                clipboard.setText(AnnotatedString(text))
                                scope.launch { snackbar.showSnackbar("已复制") }
                            },
                            enabled = state.rawText.isNotBlank() || state.liveText.isNotBlank() ||
                                state.correctedText.isNotBlank(),
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "复制")
                        }
                    }

                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        when (state.resultTab) {
                            ResultTab.RAW -> {
                                ScrollBody(
                                    text = state.liveText.ifBlank { state.rawText }
                                        .ifBlank { placeholderFor(state.mode, state.isBusy) },
                                    muted = state.liveText.isBlank() && state.rawText.isBlank(),
                                )
                            }
                            ResultTab.CORRECTED -> {
                                ScrollBody(
                                    text = state.correctedText.ifBlank {
                                        if (state.isBusy) {
                                            "纠偏将在结束后自动应用（默认接受纠偏词）"
                                        } else {
                                            "（尚无纠偏文本，可点 AI加工）"
                                        }
                                    },
                                    muted = state.correctedText.isBlank(),
                                )
                            }
                            ResultTab.BOTH -> {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                    Text("原文", style = MaterialTheme.typography.labelLarge, color = LuxiGreen)
                                    Text(
                                        state.rawText.ifBlank { state.liveText }.ifBlank { "—" },
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Text("纠偏", style = MaterialTheme.typography.labelLarge, color = LuxiCyan)
                                    Text(
                                        state.correctedText.ifBlank { "—" },
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    if (state.segments.isNotEmpty()) {
                                        Spacer(Modifier.height(16.dp))
                                        Text("分片", style = MaterialTheme.typography.labelLarge)
                                        state.segments.forEach { seg ->
                                            Text(
                                                "[${seg.timeLabel()}] ${seg.text}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.padding(top = 4.dp),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(24.dp))
                                }
                            }
                        }
                    }
                }
            }

            if (state.engineNote.isNotBlank()) {
                Text(
                    state.engineNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 6.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun LevelMeter(level: Float, modifier: Modifier = Modifier) {
    val clamped = level.coerceIn(0f, 1f)
    Row(
        modifier = modifier.width(72.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            "电平",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
        Box(
            Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(clamped.coerceAtLeast(0.03f))
                    .background(
                        when {
                            clamped > 0.75f -> MaterialTheme.colorScheme.error
                            clamped > 0.35f -> LuxiGreen
                            else -> LuxiCyan
                        },
                    ),
            )
        }
    }
}

@Composable
private fun SemiSliceBar(
    sec: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "半实时切片 · VAD + 最长 ${sec}s",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
        }
        Slider(
            value = sec.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 2f..15f,
            steps = 12,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = LuxiCyan,
                activeTrackColor = LuxiCyan,
            ),
        )
        Text(
            "有停顿即出字幕；无停顿则到最长时长强制出片。建议课堂 4–6 秒。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun PickupSensitivityBar(
    sensitivity: Int,
    label: String,
    hint: String,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "拾音场景 · $label",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "$sensitivity",
                style = MaterialTheme.typography.labelMedium,
                color = LuxiGreen,
            )
        }
        Slider(
            value = sensitivity.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..100f,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = LuxiGreen,
                activeTrackColor = LuxiGreen,
            ),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PickupProfile.anchors.forEach { (_, name) ->
                val active = label == name
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) {
                        LuxiGreen
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    },
                )
            }
        }
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun ModeIcon(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = if (selected) LuxiGreen else MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) LuxiGreen.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surface,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = label)
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) LuxiGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
    }
}

@Composable
private fun ScrollBody(text: String, muted: Boolean) {
    Column(
        Modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (muted) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Spacer(Modifier.height(32.dp))
    }
}

private fun placeholderFor(mode: TranscribeMode, busy: Boolean): String = when {
    busy && mode == TranscribeMode.BATCH -> "后置模式：录音中…停止后将对录音文件分片转写"
    busy && mode == TranscribeMode.SEMI_REALTIME -> "半实时字幕将显示在这里…"
    busy -> "实时文本将显示在这里…"
    else -> "点击麦克风开始。原文与纠偏共用此区域，用上方标签切换。"
}
