package lab.luxi.tingmo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import lab.luxi.tingmo.AppContainer
import lab.luxi.tingmo.domain.CorrectionApplier
import lab.luxi.tingmo.domain.CorrectionRule
import lab.luxi.tingmo.ui.viewmodel.CorrectionsViewModel
import lab.luxi.tingmo.ui.viewmodel.CorrectionsViewModelFactory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CorrectionsScreen(container: AppContainer) {
    val vm: CorrectionsViewModel = viewModel(factory = CorrectionsViewModelFactory(container))
    val rules by vm.rules.collectAsState()
    val pending by vm.pending.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var source by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var previewIn by remember { mutableStateOf("这是占位结果里的易错词示例") }
    val previewOut = remember(previewIn, rules) { CorrectionApplier.apply(previewIn, rules) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("AI 纠偏管理") }) },
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
                    "正式词库会在转写/AI 加工时自动应用。AI 加工后可产生「待审核」增量词，人工通过后再入库。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }

            if (pending.isNotEmpty()) {
                item {
                    Text("待审核（AI 提议）", fontWeight = FontWeight.SemiBold)
                }
                items(pending, key = { "p-${it.id}" }) { p ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("「${p.source}」→「${p.target}」", fontWeight = FontWeight.Medium)
                            if (p.reason.isNotBlank()) {
                                Text(
                                    p.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        vm.approvePending(p)
                                        scope.launch { snackbar.showSnackbar("已入库") }
                                    },
                                ) { Text("通过入库") }
                                OutlinedButton(
                                    onClick = {
                                        vm.rejectPending(p.id)
                                        scope.launch { snackbar.showSnackbar("已拒绝") }
                                    },
                                ) { Text("拒绝") }
                            }
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("新增规则", fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = source,
                            onValueChange = { source = it },
                            label = { Text("原词（识别结果）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = target,
                            onValueChange = { target = it },
                            label = { Text("目标词（纠偏后）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it },
                            label = { Text("备注（可选）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    runCatching { vm.add(source, target, note) }
                                        .onSuccess {
                                            source = ""
                                            target = ""
                                            note = ""
                                            snackbar.showSnackbar("已添加")
                                        }
                                        .onFailure {
                                            snackbar.showSnackbar(it.message ?: "添加失败")
                                        }
                                }
                            },
                        ) { Text("添加") }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("纠偏预览", fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = previewIn,
                            onValueChange = { previewIn = it },
                            label = { Text("输入样例文本") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("结果：", style = MaterialTheme.typography.labelLarge)
                        Text(previewOut, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            items(rules, key = { it.id }) { rule ->
                RuleRow(
                    rule = rule,
                    onToggle = { enabled ->
                        scope.launch { vm.setEnabled(rule, enabled) }
                    },
                    onDelete = {
                        scope.launch {
                            vm.delete(rule.id)
                            snackbar.showSnackbar("已删除")
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun RuleRow(
    rule: CorrectionRule,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "「${rule.source}」→「${rule.target}」",
                    fontWeight = FontWeight.Medium,
                )
                if (rule.note.isNotBlank()) {
                    Text(
                        rule.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除")
            }
        }
    }
}
