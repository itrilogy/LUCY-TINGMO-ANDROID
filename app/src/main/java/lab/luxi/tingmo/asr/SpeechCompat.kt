package lab.luxi.tingmo.asr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * 小米 / 华为等机型常无 Google RecognitionService，
 * [SpeechRecognizer.isRecognitionAvailable] 会误报 false。
 * 这里做更宽松的探测，并尝试绑定厂商组件。
 */
object SpeechCompat {
    private const val TAG = "SpeechCompat"

    /** 厂商 / 常见 RecognitionService 组件（按优先级） */
    private val VENDOR_SERVICES = listOf(
        // Xiaomi / HyperOS
        "com.xiaomi.mibrain.speech/.SpeechRecognizerService",
        "com.xiaomi.mibrain.speech/com.xiaomi.mibrain.speech.SpeechRecognizerService",
        "com.miui.voiceassist/.SpeechRecognizerService",
        "com.xiaomi.aiasr/.SpeechService",
        // Google (若已装)
        "com.google.android.googlequicksearchbox/com.google.android.voicesearch.serviceapi.GoogleRecognitionService",
        "com.google.android.tts/com.google.android.apps.speechservices.RecognitionService",
        // Huawei
        "com.huawei.hivoice/.RecognitionService",
        // Oppo / Vivo 等偶发
        "com.coloros.speechassist/.RecognitionService",
    )

    data class Availability(
        val available: Boolean,
        val reason: String,
        val component: ComponentName? = null,
    )

    fun probe(context: Context): Availability {
        val appCtx = context.applicationContext
        if (SpeechRecognizer.isRecognitionAvailable(appCtx)) {
            return Availability(true, "SpeechRecognizer.isRecognitionAvailable=true")
        }

        // Intent 可被解析，说明至少有听写 Activity（部分机型仅有 Activity 无 Service）
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        val activities = appCtx.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        if (activities.isNotEmpty()) {
            Log.i(TAG, "RECOGNIZE_SPEECH activities=${activities.map { it.activityInfo.packageName }}")
        }

        val component = findVendorComponent(appCtx)
        if (component != null) {
            return Availability(true, "vendor service ${component.flattenToShortString()}", component)
        }

        // 仍尝试 createSpeechRecognizer：部分 ROM 探测 API 撒谎但创建可用
        return try {
            val tmp = SpeechRecognizer.createSpeechRecognizer(appCtx)
            tmp.destroy()
            Availability(
                available = activities.isNotEmpty(),
                reason = if (activities.isNotEmpty()) {
                    "仅有听写 Activity，无标准 RecognitionService（小米常见）"
                } else {
                    "无 RecognitionService 且无听写 Activity"
                },
            )
        } catch (e: Exception) {
            Availability(false, "createSpeechRecognizer failed: ${e.message}")
        }
    }

    fun createRecognizer(context: Context): SpeechRecognizer? {
        val appCtx = context.applicationContext
        val probe = probe(appCtx)
        return try {
            if (probe.component != null && Build.VERSION.SDK_INT >= 31) {
                // API 31+ 可指定组件
                SpeechRecognizer.createSpeechRecognizer(appCtx, probe.component)
            } else if (probe.component != null) {
                @Suppress("DEPRECATION")
                SpeechRecognizer.createSpeechRecognizer(appCtx, probe.component)
            } else {
                SpeechRecognizer.createSpeechRecognizer(appCtx)
            }
        } catch (e: Exception) {
            Log.e(TAG, "createRecognizer failed", e)
            runCatching { SpeechRecognizer.createSpeechRecognizer(appCtx) }.getOrNull()
        }
    }

    fun findVendorComponent(context: Context): ComponentName? {
        val pm = context.packageManager
        for (flat in VENDOR_SERVICES) {
            val cn = ComponentName.unflattenFromString(flat) ?: continue
            try {
                pm.getServiceInfo(cn, 0)
                Log.i(TAG, "found recognition service: $flat")
                return cn
            } catch (_: PackageManager.NameNotFoundException) {
                // try next
            }
        }
        // 扫描已安装包中声明 RecognitionService 的组件
        val intent = Intent("android.speech.RecognitionService")
        val services = pm.queryIntentServices(intent, 0)
        val first = services.firstOrNull()?.serviceInfo
        if (first != null) {
            val cn = ComponentName(first.packageName, first.name)
            Log.i(TAG, "found via intent scan: ${cn.flattenToShortString()}")
            return cn
        }
        return null
    }

    fun unsupportedMessage(probe: Availability): String = buildString {
        append("当前设备未提供可用的系统 RecognitionService")
        append("（小米/HyperOS 常见：录音机有识别，但第三方 App 调不到同一服务）。")
        append("请改用「SenseVoice ONNX」本地模型：模型页下载后选用，半实时/后置即可真转写。")
        if (probe.reason.isNotBlank()) append(" 探测：").append(probe.reason)
    }
}
