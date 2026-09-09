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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import lab.luxi.tingmo.AppContainer
import lab.luxi.tingmo.data.db.RecordingEntity
import lab.luxi.tingmo.domain.TimedSegment
import lab.luxi.tingmo.ui.viewmodel.RecordingsViewModel
import lab.luxi.tingmo.ui.viewmodel.RecordingsViewModelFactory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(container: AppContainer) {
    val vm: RecordingsViewModel = viewModel(factory = RecordingsViewModelFactory(container))
    val list by vm.recordings.collectAsState()
    val playingId by vm.playingId.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Text("录音管理") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (list.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
            ) {
                Text(
                    "暂无录音。在撰写页完成一次转写后，录音文件会自动归档到这里。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.id }) { item ->
                    RecordingCard(
                        item = item,
                        playing = playingId == item.id,
                        onPlay = {
                            if (playingId == item.id) vm.stopPlay() else vm.play(item)
                        },
                        onRename = { title -> vm.rename(item.id, title) },
                        onRetranscribe = {
                            vm.retranscribe(item) { msg ->
                                scope.launch { snackbar.showSnackbar(msg) }
                            }
                        },
                        onDelete = {
                            vm.delete(item.id)
                            scope.launch { snackbar.showSnackbar("已删除") }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingCard(
    item: RecordingEntity,
    playing: Boolean,
    onPlay: () -> Unit,
    onRename: (String) -> Unit,
    onRetranscribe: () -> Unit,
    onDelete: () -> Unit,
) {
    var editing by remember(item.id) { mutableStateOf(false) }
    var title by remember(item.id) { mutableStateOf(item.title) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (editing) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("标题") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onRename(title)
                            editing = false
                        },
                    ) { Text("保存") }
                    OutlinedButton(onClick = { editing = false; title = item.title }) {
                        Text("取消")
                    }
                }
            } else {
                Text(item.title, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "时长 ${TimedSegment.formatClock(item.durationMs)} · ${item.mode} · ${item.modelId}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            if (item.correctedText.isNotBlank() || item.rawText.isNotBlank()) {
                Text(
                    (item.correctedText.ifBlank { item.rawText }).take(120) +
                        if ((item.correctedText.ifBlank { item.rawText }).length > 120) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(onClick = onPlay) { Text(if (playing) "停止" else "播放") }
                OutlinedButton(onClick = { editing = true }) { Text("重命名") }
                OutlinedButton(onClick = onRetranscribe) { Text("重转写") }
                OutlinedButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}
