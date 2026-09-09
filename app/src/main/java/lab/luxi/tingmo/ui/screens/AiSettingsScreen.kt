package lab.luxi.tingmo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import lab.luxi.tingmo.data.LlmSettings
import lab.luxi.tingmo.data.RemoteAsrSettings
import lab.luxi.tingmo.ui.viewmodel.AiSettingsViewModel
import lab.luxi.tingmo.ui.viewmodel.AiSettingsViewModelFactory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(container: AppContainer) {
    val vm: AiSettingsViewModel = viewModel(factory = AiSettingsViewModelFactory(container))
    val llm by vm.llm.collectAsState()
    val rasr by vm.remoteAsr.collectAsState()
    val prompts by vm.prompts.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var llmEnabled by remember(llm) { mutableStateOf(llm.enabled) }
    var llmBase by remember(llm) { mutableStateOf(llm.baseUrl) }
    var llmKey by remember(llm) { mutableStateOf(llm.apiKey) }
    var llmModel by remember(llm) { mutableStateOf(llm.model) }

    var asrEnabled by remember(rasr) { mutableStateOf(rasr.enabled) }
    var asrBase by remember(rasr) { mutableStateOf(rasr.baseUrl) }
    var asrKey by remember(rasr) { mutableStateOf(rasr.apiKey) }
    var asrModel by remember(rasr) { mutableStateOf(rasr.model) }

    var promptName by remember { mutableStateOf("") }
    var promptBody by remember { mutableStateOf("") }

    Scaffold(
        topBar = { TopAppBar(title = { Text("智能配置") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "智能链路（点撰写页「AI加工」时执行）：\n" +
                    "① 若启用远端 ASR → 用本场录音 WAV 调云端重转写\n" +
                    "② 套用纠偏词库\n" +
                    "③ 若启用大模型 → 按默认提示词润色，并可提议待审纠偏词\n" +
                    "录音页「重转写」：优先远端 ASR（若已启用），否则本地 ONNX。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("大模型（LLM）", fontWeight = FontWeight.SemiBold)
                        Switch(checked = llmEnabled, onCheckedChange = { llmEnabled = it })
                    }
                    OutlinedTextField(
                        value = llmBase,
                        onValueChange = { llmBase = it },
                        label = { Text("Base URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = llmKey,
                        onValueChange = { llmKey = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = llmModel,
                        onValueChange = { llmModel = it },
                        label = { Text("模型名") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            vm.saveLlm(
                                LlmSettings(llmEnabled, llmBase, llmKey, llmModel),
                            )
                            scope.launch { snackbar.showSnackbar("大模型配置已保存") }
                        },
                    ) { Text("保存大模型") }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("远端语音 API", fontWeight = FontWeight.SemiBold)
                        Switch(checked = asrEnabled, onCheckedChange = { asrEnabled = it })
                    }
                    Text(
                        "协议：OpenAI 兼容 multipart\n" +
                            "POST {BaseURL}/audio/transcriptions\n" +
                            "字段：file=录音.wav，model，language=zh\n" +
                            "示例 Base：https://api.openai.com/v1　模型：whisper-1\n" +
                            "也可用兼容网关（Groq/本地 whisper.cpp 代理等），路径需含 /v1。\n" +
                            "注意：远端 ASR 不参与半实时本地字幕；用于「AI加工」重转写或录音页重转写。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    )
                    OutlinedTextField(
                        value = asrBase,
                        onValueChange = { asrBase = it },
                        label = { Text("Base URL（到 /v1 为止）") },
                        placeholder = { Text("https://api.openai.com/v1") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = asrKey,
                        onValueChange = { asrKey = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = asrModel,
                        onValueChange = { asrModel = it },
                        label = { Text("ASR 模型名") },
                        placeholder = { Text("whisper-1") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            vm.saveRemoteAsr(
                                RemoteAsrSettings(asrEnabled, asrBase, asrKey, asrModel),
                            )
                            scope.launch {
                                snackbar.showSnackbar(
                                    if (asrEnabled) {
                                        "远端 ASR 已开启：AI加工 / 录音重转写时生效"
                                    } else {
                                        "远端 ASR 已关闭并保存"
                                    },
                                )
                            }
                        },
                    ) { Text("保存远端 ASR") }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("提示词管理", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = promptName,
                        onValueChange = { promptName = it },
                        label = { Text("名称") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = promptBody,
                        onValueChange = { promptBody = it },
                        label = { Text("提示词内容") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                    )
                    Button(
                        onClick = {
                            if (promptBody.isBlank()) {
                                scope.launch { snackbar.showSnackbar("提示词不能为空") }
                            } else {
                                vm.addPrompt(promptName, promptBody, makeDefault = prompts.isEmpty())
                                promptName = ""
                                promptBody = ""
                                scope.launch { snackbar.showSnackbar("已添加提示词") }
                            }
                        },
                    ) { Text("添加提示词") }

                    prompts.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    p.name + if (p.isDefault) "（默认）" else "",
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    p.content.take(80) + if (p.content.length > 80) "…" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                            }
                            if (!p.isDefault) {
                                Button(onClick = { vm.setDefaultPrompt(p.id) }) { Text("设默认") }
                            }
                            IconButton(onClick = { vm.deletePrompt(p.id) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "删除")
                            }
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("关于听默", fontWeight = FontWeight.SemiBold)
                    Text(
                        "课堂语音成文 · 鹿溪联合创新实验室\n底栏「关于」已并入此处简述。版本见应用详情。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
