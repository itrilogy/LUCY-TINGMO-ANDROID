# 听默 · Tingmo（Android）

**听而有迹 · 默而成文**  
鹿溪联合创新实验室 · Android 实验客户端

独立工程路径：`/Users/kwangwah/Project/tingmo-android`  
与桌面听默（Handy lab fork）配合：本端聚焦 **模型管理、实时/后置转写、AI 纠偏**。

## 功能

| 模块 | 说明 |
| --- | --- |
| **撰写** | **实时** / **半实时字幕** / **后置（停录后对录音分片转写）**；紧凑图标控件；原文·纠偏·对照标签；AI 加工 |
| **本地 ONNX** | 集成 **sherpa-onnx 1.13.6**；SenseVoice INT8 / Whisper tiny 真推理；模型页真实下载解压 |
| **模型** | 多模型目录、下载安装标记、切换当前引擎 |
| **纠偏** | 易错词规则 CRUD、启用开关、预览、转写落库前自动替换 |
| **关于** | 听默 / 鹿溪品牌与版本信息 |

内置模型条目：`system_zh`、`sensevoice_small`、`parakeet_v3`、`qwen3_asr`、`whisper_tiny`。

> 本地 ONNX/Whisper **真实推理**通过 `LocalModelTranscriber` 适配层接入；当前为可运行的占位实现，保证 UI 与流程可完整验证。

## 环境

- Android Studio（推荐直接 Open 本目录同步 SDK）
- **JDK 17**（命令行构建请用 OpenJDK 17；Android Studio 自带 JBR 若为 25+ 可能导致 Gradle 异常）
- Android SDK Platform 35+，Build-Tools 34/36，`minSdk 26`
- 设备或模拟器需麦克风权限；系统实时引擎部分机型需网络

本机已验证：`assembleDebug` 成功，产物见  
`app/build/outputs/apk/debug/app-debug.apk`

## 打开与运行

```bash
# Android Studio：File → Open → tingmo-android

# 命令行（务必 JDK 17）：
export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

## 结构

```
app/src/main/java/lab/luxi/tingmo/
├── asr/           # 实时/后置转写引擎与编排
├── data/          # Room · DataStore · Repository
├── domain/        # 模型目录 · 纠偏 · 转写模型
└── ui/            # Compose 界面 · ViewModel
```

## 本地 ONNX 真推理（已接入）

依赖：`app/libs/sherpa-onnx-1.13.6.aar`（含 onnxruntime 与 JNI）。

1. 打开 App → **模型** → 下载 **SenseVoice Small (ONNX)**（约 230MB，需网络）  
2. **选用**该模型（标题会显示 `· ONNX`）  
3. **半实时**：说话过程中按窗长出字幕（真推理）  
4. **后置**：只录音，停止后对 PCM 分片 ONNX 转写  
5. **AI 加工**：可对已存录音用 ONNX 重跑 + 纠偏词  

若缺少 AAR：

```bash
cd app/libs
gh release download v1.13.6 -R k2-fsa/sherpa-onnx -p 'sherpa-onnx-1.13.6.aar'
```

## 许可与品牌

实验室内部实验构建。品牌归鹿溪联合创新实验室；上游桌面能力致谢 Handy（MIT）。  
ONNX 推理基于 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) / SenseVoice。
