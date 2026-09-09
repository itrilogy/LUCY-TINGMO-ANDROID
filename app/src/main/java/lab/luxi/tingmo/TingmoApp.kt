package lab.luxi.tingmo

import android.app.Application
import lab.luxi.tingmo.asr.OnnxAsrEngine
import lab.luxi.tingmo.asr.TranscriptionService
import lab.luxi.tingmo.asr.VadEngine
import lab.luxi.tingmo.data.AiSettingsRepository
import lab.luxi.tingmo.data.CorrectionRepository
import lab.luxi.tingmo.data.ModelRepository
import lab.luxi.tingmo.data.PromptRepository
import lab.luxi.tingmo.data.RecordingRepository
import lab.luxi.tingmo.data.SettingsRepository
import lab.luxi.tingmo.data.db.CorrectionRuleEntity
import lab.luxi.tingmo.data.db.TingmoDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TingmoApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val db = TingmoDatabase.get(this)
        val settings = SettingsRepository(this)
        val aiSettings = AiSettingsRepository(this)
        val onnx = OnnxAsrEngine(this)
        val vad = VadEngine(this)
        val prompts = PromptRepository(db.promptDao())
        val recordings = RecordingRepository(db.recordingDao())
        container = AppContainer(
            appContext = applicationContext,
            settings = settings,
            aiSettings = aiSettings,
            promptRepository = prompts,
            recordingRepository = recordings,
            modelRepository = ModelRepository(this, db.modelInstallDao(), settings, onnx, vad),
            correctionRepository = CorrectionRepository(db.correctionDao()),
            pendingCorrectionDao = db.pendingCorrectionDao(),
            transcriptionService = TranscriptionService(
                this,
                db.correctionDao(),
                db.historyDao(),
                db.pendingCorrectionDao(),
                recordings,
                onnx,
                vad,
            ),
            historyDao = db.historyDao(),
            onnxEngine = onnx,
            vadEngine = vad,
        )

        appScope.launch {
            prompts.ensureDefault()
            val existing = db.correctionDao().observeAll().first()
            if (existing.isEmpty()) {
                db.correctionDao().insert(
                    CorrectionRuleEntity(
                        source = "占位",
                        target = "听默",
                        note = "示例纠偏规则",
                    ),
                )
            }
        }
    }
}

class AppContainer(
    val appContext: android.content.Context,
    val settings: SettingsRepository,
    val aiSettings: AiSettingsRepository,
    val promptRepository: PromptRepository,
    val recordingRepository: RecordingRepository,
    val modelRepository: ModelRepository,
    val correctionRepository: CorrectionRepository,
    val pendingCorrectionDao: lab.luxi.tingmo.data.db.PendingCorrectionDao,
    val transcriptionService: TranscriptionService,
    val historyDao: lab.luxi.tingmo.data.db.HistoryDao,
    val onnxEngine: OnnxAsrEngine,
    val vadEngine: VadEngine,
)
