package lab.luxi.tingmo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import lab.luxi.tingmo.AppContainer
import lab.luxi.tingmo.data.AddonUiState
import lab.luxi.tingmo.data.ModelUiState
import lab.luxi.tingmo.domain.AddonCatalog
import lab.luxi.tingmo.domain.EngineKind
import lab.luxi.tingmo.domain.ModelStatus
import lab.luxi.tingmo.ui.theme.LuxiGreen
import lab.luxi.tingmo.ui.viewmodel.ModelsViewModel
import lab.luxi.tingmo.ui.viewmodel.ModelsViewModelFactory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(container: AppContainer) {
    val vm: ModelsViewModel = viewModel(factory = ModelsViewModelFactory(container))
    val models by vm.models.collectAsState()
    val vad by vm.vadAddon.collectAsState()
    val downloadingId by vm.downloadingId.collectAsState()
    val progress by vm.progress.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Text("模型管理") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "ASR 模型与 VAD 组件分开下载。Silero VAD 可配合任意本地 ONNX。" +
                        "SenseVoice 完整约 230MB；若曾闪退并提示 Protobuf，请点「重新下载」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }

            item {
                Text("组件", fontWeight = FontWeight.SemiBold)
            }
            item {
                VadCard(
                    item = vad,
                    downloading = downloadingId == AddonCatalog.SILERO_VAD_ID,
                    progress = if (downloadingId == AddonCatalog.SILERO_VAD_ID) progress else 0f,
                    onDownload = {
                        scope.launch {
                            runCatching { vm.downloadVad() }
                                .onSuccess { snackbar.showSnackbar("Silero VAD 已就绪") }
                                .onFailure { snackbar.showSnackbar(it.message ?: "VAD 下载失败") }
                        }
                    },
                    onRemove = {
                        scope.launch {
                            vm.uninstallVad()
                            snackbar.showSnackbar("已移除 VAD")
                        }
                    },
                )
            }

            item {
                Text("语音模型", fontWeight = FontWeight.SemiBold)
            }
            items(models, key = { it.model.id }) { item ->
                ModelCard(
                    item = item,
                    downloading = downloadingId == item.model.id,
                    progress = if (downloadingId == item.model.id) progress else 0f,
                    onSelect = {
                        scope.launch {
                            runCatching { vm.select(item.model.id) }
                                .onFailure { e -> snackbar.showSnackbar(e.message ?: "无法选用") }
                                .onSuccess { snackbar.showSnackbar("已切换到 ${item.model.displayName}") }
                        }
                    },
                    onDownload = {
                        scope.launch {
                            runCatching { vm.download(item.model.id) }
                                .onFailure { e -> snackbar.showSnackbar(e.message ?: "下载失败") }
                                .onSuccess { snackbar.showSnackbar("${item.model.displayName} 已就绪") }
                        }
                    },
                    onRemove = {
                        scope.launch {
                            vm.uninstall(item.model.id)
                            snackbar.showSnackbar("已移除")
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun VadCard(
    item: AddonUiState,
    downloading: Boolean,
    progress: Float,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.displayName, fontWeight = FontWeight.Bold)
                Text(
                    if (item.ready) "已就绪" else "未安装",
                    color = if (item.ready) LuxiGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Text(item.description, style = MaterialTheme.typography.bodySmall)
            Text(item.sizeLabel, style = MaterialTheme.typography.labelMedium)
            item.errorHint?.let { hint ->
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (downloading) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!item.ready) {
                    Button(onClick = onDownload, enabled = !downloading) {
                        Text(if (item.errorHint != null) "重新下载 VAD" else "下载 VAD")
                    }
                    if (item.errorHint != null) {
                        OutlinedButton(onClick = onRemove, enabled = !downloading) { Text("移除") }
                    }
                } else {
                    OutlinedButton(onClick = onRemove, enabled = !downloading) { Text("移除") }
                    OutlinedButton(onClick = onDownload, enabled = !downloading) { Text("重新下载") }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    item: ModelUiState,
    downloading: Boolean,
    progress: Float,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    val model = item.model
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(model.displayName, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        item.selected -> "使用中"
                        item.status == ModelStatus.READY -> "已就绪"
                        item.status == ModelStatus.ERROR -> "已损坏"
                        else -> "未安装"
                    },
                    color = when {
                        item.selected -> MaterialTheme.colorScheme.primary
                        item.status == ModelStatus.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    },
                )
            }
            Text(model.description, style = MaterialTheme.typography.bodySmall)
            Text(
                "${model.sizeLabel} · ${model.languages}",
                style = MaterialTheme.typography.labelMedium,
            )
            item.errorHint?.let { hint ->
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (downloading) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSelect,
                    enabled = item.status == ModelStatus.READY && !item.selected && !downloading,
                ) { Text(if (item.selected) "当前" else "选用") }
                if (model.engine != EngineKind.SYSTEM_SPEECH) {
                    when (item.status) {
                        ModelStatus.READY -> {
                            OutlinedButton(
                                onClick = onRemove,
                                enabled = !downloading && !item.selected,
                            ) { Text("移除") }
                            OutlinedButton(onClick = onDownload, enabled = !downloading) {
                                Text("重新下载")
                            }
                        }
                        ModelStatus.ERROR -> {
                            Button(onClick = onDownload, enabled = !downloading) {
                                Text("重新下载")
                            }
                            OutlinedButton(onClick = onRemove, enabled = !downloading) {
                                Text("移除")
                            }
                        }
                        else -> {
                            OutlinedButton(onClick = onDownload, enabled = !downloading) {
                                Text("下载")
                            }
                        }
                    }
                }
            }
        }
    }
}
