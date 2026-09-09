package lab.luxi.tingmo.data

import android.content.Context
import lab.luxi.tingmo.asr.ModelDownloader
import lab.luxi.tingmo.asr.OnnxAsrEngine
import lab.luxi.tingmo.asr.OnnxModelPack
import lab.luxi.tingmo.asr.VadEngine
import lab.luxi.tingmo.data.db.ModelInstallDao
import lab.luxi.tingmo.data.db.ModelInstallEntity
import lab.luxi.tingmo.domain.AddonCatalog
import lab.luxi.tingmo.domain.AsrModel
import lab.luxi.tingmo.domain.EngineKind
import lab.luxi.tingmo.domain.ModelCatalog
import lab.luxi.tingmo.domain.ModelStatus
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class ModelUiState(
    val model: AsrModel,
    val status: ModelStatus,
    val selected: Boolean,
    /** 磁盘存在但不完整时的说明 */
    val errorHint: String? = null,
)

data class AddonUiState(
    val id: String,
    val displayName: String,
    val description: String,
    val sizeLabel: String,
    val ready: Boolean,
    val errorHint: String? = null,
)

class ModelRepository(
    private val context: Context,
    private val installDao: ModelInstallDao,
    private val settings: SettingsRepository,
    private val onnxEngine: OnnxAsrEngine,
    private val vadEngine: VadEngine,
) {
    private val downloader = ModelDownloader(onnxEngine.modelsRoot())
    private val vadTick = MutableStateFlow(0)

    fun observeModels(): Flow<List<ModelUiState>> =
        combine(installDao.observeAll(), settings.selectedModelId) { installs, selectedId ->
            ModelCatalog.models.map { model ->
                val pack = OnnxModelPack.forModel(model)
                val issue = pack?.integrityIssue(onnxEngine.modelsRoot())
                val dirExists = pack?.modelDir(onnxEngine.modelsRoot())?.exists() == true
                val status = when {
                    model.engine == EngineKind.SYSTEM_SPEECH -> ModelStatus.READY
                    // 仅以磁盘完整性为准
                    OnnxModelPack.requiresOnnx(model) && onnxEngine.isModelReady(model) ->
                        ModelStatus.READY
                    OnnxModelPack.requiresOnnx(model) && dirExists && issue != null ->
                        ModelStatus.ERROR
                    else -> ModelStatus.NOT_DOWNLOADED
                }
                ModelUiState(
                    model = model,
                    status = status,
                    selected = model.id == selectedId,
                    errorHint = if (status == ModelStatus.ERROR) issue else null,
                )
            }
        }

    fun observeVadAddon(): Flow<AddonUiState> = vadTick.map {
        val a = AddonCatalog.sileroVad
        val ready = vadEngine.isReady()
        val issue = if (!ready && vadEngine.vadFile().exists()) vadEngine.integrityIssue() else null
        AddonUiState(
            id = a.id,
            displayName = a.displayName,
            description = a.description,
            sizeLabel = a.sizeLabel,
            ready = ready,
            errorHint = issue,
        )
    }

    suspend fun select(modelId: String) {
        val model = ModelCatalog.require(modelId)
        if (OnnxModelPack.requiresOnnx(model)) {
            require(onnxEngine.isModelReady(model)) { "请先下载本地 ONNX 模型" }
        }
        settings.setSelectedModel(modelId)
        if (OnnxModelPack.requiresOnnx(model)) {
            runCatching { onnxEngine.ensureLoaded(model) }
        }
    }

    /** 仅下载 ASR 模型包（不含 VAD）。损坏时会强制重下。 */
    suspend fun download(modelId: String, onProgress: (Float) -> Unit = {}) {
        val model = ModelCatalog.require(modelId)
        require(model.engine != EngineKind.SYSTEM_SPEECH) { "系统引擎无需下载" }
        val pack = OnnxModelPack.forModel(model)
            ?: throw IllegalStateException("该模型暂无 ONNX 下载源")
        onnxEngine.unload()
        // 已就绪时用户点「重新下载」也强制重来
        downloader.download(pack, onProgress, force = true)
        require(pack.isReady(onnxEngine.modelsRoot())) {
            pack.integrityIssue(onnxEngine.modelsRoot()) ?: "下载后校验失败"
        }
        val paths = pack.resolvePaths(onnxEngine.modelsRoot())
        installDao.upsert(
            ModelInstallEntity(
                modelId = modelId,
                installed = true,
                localPath = paths?.first?.parent
                    ?: File(onnxEngine.modelsRoot(), pack.extractDirName).absolutePath,
            ),
        )
        if (pack.modelId != modelId) {
            installDao.upsert(
                ModelInstallEntity(
                    modelId = pack.modelId,
                    installed = true,
                    localPath = paths?.first?.parent,
                ),
            )
        }
        // 预热加载，把损坏尽早暴露为 Kotlin 异常而不是录音中闪退
        onnxEngine.ensureLoaded(model)
    }

    /** 独立下载 Silero VAD（sherpa 版 ~630KB）。调用即强制重下。 */
    suspend fun downloadVad(onProgress: (Float) -> Unit = {}) {
        vadEngine.vadFile().delete()
        vadEngine.ensureDownloaded(onProgress)
        require(vadEngine.isReady()) {
            vadEngine.integrityIssue() ?: "VAD 下载后校验失败"
        }
        installDao.upsert(
            ModelInstallEntity(
                modelId = AddonCatalog.SILERO_VAD_ID,
                installed = true,
                localPath = vadEngine.vadFile().absolutePath,
            ),
        )
        vadTick.value = vadTick.value + 1
    }

    suspend fun uninstallVad() {
        vadEngine.vadFile().delete()
        installDao.upsert(
            ModelInstallEntity(modelId = AddonCatalog.SILERO_VAD_ID, installed = false, localPath = null),
        )
        vadTick.value = vadTick.value + 1
    }

    suspend fun uninstall(modelId: String) {
        val model = ModelCatalog.require(modelId)
        val pack = OnnxModelPack.forModel(model)
        if (pack != null) {
            // 共享 SenseVoice 包：一并清理所有别名安装标记，避免半删
            val aliases = ModelCatalog.models
                .filter { OnnxModelPack.forModel(it)?.extractDirName == pack.extractDirName }
                .map { it.id }
                .distinct()
            File(onnxEngine.modelsRoot(), pack.extractDirName).deleteRecursively()
            aliases.forEach { id ->
                installDao.upsert(
                    ModelInstallEntity(modelId = id, installed = false, localPath = null),
                )
            }
        } else {
            installDao.upsert(
                ModelInstallEntity(modelId = modelId, installed = false, localPath = null),
            )
        }
        onnxEngine.unload()
    }

    fun selectedModelFlow(): Flow<AsrModel> =
        settings.selectedModelId.map { ModelCatalog.require(it) }

    fun isVadReady(): Boolean = vadEngine.isReady()
}
