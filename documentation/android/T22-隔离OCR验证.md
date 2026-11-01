# T22 · 隔离 OCR 技术验证（原 ML Kit 失败历史）

## 负责人决定与 PaddleOCR 恢复准备

负责人已明确选择“改用 PaddleOCR 端侧方案，继续坚持不发送统计指标”。引擎选择门禁已解除；下文 ML Kit 失败与待选择措辞为历史实验记录，不再等待重复确认。PaddleOCR 已完成隔离引擎与六张合成图的真实 UI 闭环验证，具体版本、失败修复、APK 哈希、完整回归与仍待验证的外部门禁见 [T22-PaddleOCR 开发记录](T22-PaddleOCR开发记录.md)。本文下方“未接通/未通过”措辞保留为原 ML Kit 实验历史，不代表当前 PaddleOCR 应用状态，亦不宣称整体发布验收完成。

以下为引擎接入前的资产检查历史快照；表内“候选”和下文“尚未接入/验证”描述当时阶段。当前这些固定资产已接入并完成隔离引擎、应用流程验证，以前述 PaddleOCR 开发记录为准：

| 资产 | 固定版本 / 来源 | 字节数 | SHA-256 |
| --- | --- | ---: | --- |
| 官方 SDK 源码 | PaddlePaddle/PaddleOCR `dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf`，仅 `deploy/ppocr-android/ppocr-sdk` | — | commit 固定 |
| ONNX Runtime Android AAR | Maven Central `com.microsoft.onnxruntime:onnxruntime-android:1.21.1` | 27944395 | `30e594a4b9246fe3ca25768570e90f71e6d33ceb7b7dd72f92dcd7c267611d3f` |
| 官方 OpenCV AAR，候选 | Maven Central `org.opencv:opencv:4.14.0` | 123380341 | `6d11b40f6a54113dafe8540b1237b637193cb21e83deb11bc53d6757d35d494d` |
| v5 mobile 检测 ONNX | [官方模型](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx/tree/e6f4fa85f00e168c862bc462aebca69eef9b3d3d) | 4826518 | `a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d` |
| v5 mobile 识别 ONNX | [官方模型](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx/tree/ed152b8b495f84de93cda5709d768548a9127622) | 16534782 | `da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092` |
| 对应识别字典配置 | 同一识别模型 revision 的 `inference.yml` | 148345 | `5dfeb2777f6d0db8177d8128a8acfcf6e6276dc4ac73ea3bf0dc06d6a5e85d8e` |

具体 AAR 已逐个读取 ELF PT_LOAD 对齐：官方示例的 QuickBird OpenCV 4.5.3 所有 ABI 原生库均为 4096；不采用其作为当前目标版本的正式依赖。候选官方 OpenCV 4.14.0 的 arm64-v8a / x86_64 的 `libopencv_java4.so` 和 `libc++_shared.so` 均为 16384，ORT 1.21.1 的同两种 ABI 两份 so 均为 16384。此结果只证明 ELF 对齐与 ABI 存在；尚未验证 APK ZIP 对齐、系统加载、16KB 设备、完整推理和中端机性能。32 位库的对齐单独记录，不把 64 位结果推广为所有 ABI。

模型下载使用固定 revision 的官方 Hugging Face 镜像；检测模型 SHA-256 已与该 revision 的上游 LFS OID 相符。BOS 检测包读取停滞后已只停止本任务下载进程；已完成文件保留，没有把不完整 `.part` 当作模型。ORT LICENSE / ThirdPartyNotices 与 OpenCV 对应 tag 的 LICENSE 已下载待整理，AAR 本身未找到 NOTICE 路径，仍需收集具体原生库第三方声明。

实施先后：先在独立 OCR 模块中固定官方 SDK / 模型 / 校验清单；只允许服务在无网络权限的 isolatedProcess 加载，禁止主进程初始化引擎；先通过六图端侧推理与网络权限断言，再接导入排序、分块/去重、低置信度核对、说话人确认和统一预览。未通过时保留粘贴退路，展示静态错误，不输出截图或识别原文日志。与 T43 的构建严格串行，不启动第二个 Gradle，不操作用户 emulator-5556。

本草稿 PR 当前只提交技术评估文档。下述隔离服务、设备测试、六张合成图及生成脚本保留在本机 WIP，不属于当前文档提交；尚未把任何 OCR SDK 接入主开发链。负责人选择引擎后，再提交实际实现与完整验收，不把失败实验标为已完成功能。

2026-10-31，当前仅完成隐私约束下的阻断性技术验证。**不能宣称 T22 已实现、六图端到端通过、纯离线可用或达到性能门槛。** 应用保留粘贴导入，并说明识别组件在无网络权限的隔离进程中不可用；未接通普通进程 ML Kit，也未切换 PaddleOCR。

## 采用的公开接口与边界

- [中文识别 Android 指南](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)：bundled `com.google.mlkit:text-recognition-chinese:16.0.1`，无动态下载模型依赖。
- [MlKit 手动初始化接口](https://developers.google.com/android/reference/com/google/mlkit/common/MlKit)：通过 manifest `tools:node="remove"` 移除 `MlKitInitProvider`；只在隔离服务调用公开 `MlKit.initialize(context)`。
- [Android isolatedProcess](https://developer.android.com/guide/topics/manifest/service-element#isolated)：隔离服务没有应用权限。服务 `exported=false`、`isolatedProcess=true`，客户端使用 `BIND_AUTO_CREATE`，无前台服务或新增用户权限。
- [ML Kit 数据披露](https://developers.google.com/ml-kit/android-data-disclosure)说明 SDK 收集设备、应用、性能与使用分析数据。这与本项目“不接入第三方统计 SDK”的隐私红线存在需要负责人评估的冲突；本验证没有以“模型在本地运行”推导“SDK 不联网”。

主进程不调用 ML Kit；未创建数据库或修改 Application 初始化。位图通过 AIDL IPC 输入，结果经字符串回调返回，不用 Bundle。样例仅含虚构文本。没有 SDK 反射、私有接口、模型或源码补丁。

## 实际设备与结果

- 仅使用本任务 AOSP 模拟器 `emulator-5580`，API 35 / Android 15 / x86_64；型号 `Android SDK built for x86_64`。
- fingerprint：`Android/sdk_phone64_x86_64/emu64x:15/AE3A.240806.019/12368160:userdebug/test-keys`。
- `pm list packages com.google.android.gms` 返回空，无 GMS。未操作用户的 emulator-5556。
- `:app:assembleDebug :app:assembleDebugAndroidTest` 成功，首次构建 3 分 25 秒。
- 设备测试 `IsolatedOcrSpikeTest.isolatedChineseRecognition`：隔离 UID、INTERNET 权限拒绝、Socket.bind 拒绝的三个断言通过。系统记录服务为 `u0ai9000`。
- 第一张浅色合成图触发识别后，SDK 后台线程崩溃；客户端收到 `android.os.DeadObjectException`。没有得到第一张图的识别结果，因此六图识别循环无法继续。
- SDK 系统日志表明 `Selected local version of com.google.mlkit.dynamite.text.chinese`，随后发生以下异常：

```text
FATAL EXCEPTION: GoogleApiHandler
java.lang.SecurityException: Isolated process not allowed to call getMyMemoryState
  android.app.ActivityManager.getMyMemoryState(ActivityManager.java:4753)
  com.google.android.gms.common.api.internal.BackgroundDetector.readCurrentStateIfPossible
    (play-services-basement@@18.3.0:3)
  com.google.android.gms.common.api.internal.GoogleApiManager.handleMessage
    (play-services-base@@18.4.0:11)
```

错误发生在 SDK 自有后台线程，不能由识别调用周围的同步 try/catch 恢复。未经验证的系统接口拦截或降低进程隔离均未采用。该结果证明当前 SDK/API35 组合下的此方案失败，不泛化为所有设备或其他引擎均不支持。

## 本机实验复现步骤（需对应 WIP 源码）

```powershell
$env:JAVA_HOME='R:\jdk17\jdk-17.0.20.1+1'
$env:ANDROID_HOME='R:\sdk'
& Q:\android\gradlew.bat -p Q:\android :app:assembleDebug :app:assembleDebugAndroidTest '-Pjunshi.mavenMirror=https://maven-central.storage-download.googleapis.com/maven2/' --console=plain
& R:\sdk\platform-tools\adb.exe -s emulator-5580 install -r Q:\android\app\build\outputs\apk\debug\app-debug.apk
& R:\sdk\platform-tools\adb.exe -s emulator-5580 install -r Q:\android\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& R:\sdk\platform-tools\adb.exe -s emulator-5580 shell am instrument -w -r -e class com.shengjidaguai.goutoujunshi.IsolatedOcrSpikeTest com.shengjidaguai.goutoujunshi.debug.test/androidx.test.runner.AndroidJUnitRunner
& R:\sdk\platform-tools\adb.exe -s emulator-5580 logcat -d -b crash -v brief
```

该设备测试刻意保留真实失败，用作方案验证，不能计为通过的回归。原始本机输出：`D:\t22-spike-build.log`、`D:\t22-spike-device.log`。只有合成夹具用于此测试；生产内容不可输出到日志。

## 合成夹具与剩余验收

六张 PNG 位于 `android/app/src/androidTest/assets/ocr/`：`light_1`、`light_2`、`dark_1`、`dark_2`、`long`、`group`。全部由 `scripts/generate_ocr_fixtures.py` 确定性生成。字体来自 AOSP 设备 `/system/fonts/NotoSansCJK-Regular.ttc`（SIL OFL），仅生成时使用，没有把字体二进制复制到仓库。

工程后续门禁：先解决兼容隔离环境的引擎选择及 SDK 数据披露冲突，再实现 PhotoPicker、左右侧一次确认、多图重排、长图区域解码、相邻 suffix/prefix 去重、噪音过滤、置信度与未知提示、逐条编辑、取消与会话切换丢弃迟到结果，以及六图识别→确认→Demo 知识路径测试。当前没有把未实现项标为通过。

三图耗时与中端真机小于 3 秒的验收**未运行、未通过**；本次模拟器仅用于兼容性验证，不能替代中端物理设备门槛。

## PaddleOCR 端侧替代方案评估（仅建议，未切换）

截至本次核查，不能把所有 PaddleOCR Android 方案等同为旧的 Paddle Lite Demo。官方仓库同时存在以下两条路线：

| 路线 | 官方资料核实内容 | 本项目判断 |
| --- | --- | --- |
| 当前 `deploy/ppocr-android/ppocr-sdk` | [Android 指南](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/inference_deployment/cross_platform/android_deployment.en.md)描述 PP-OCRv6 Android SDK，ONNX Runtime 推理，可源码模块或自行构建 AAR；也支持 PP-OCRv5_mobile ONNX | 优先评估这一公开源码路线，可复用本项目 Kotlin/JDK17/minSdk26，无需导入 Demo UI |
| 旧 `deploy/android_demo` | [官方旧 Demo](https://github.com/PaddlePaddle/PaddleOCR/blob/main/deploy/android_demo/README.md)采用 Paddle Lite 2.10，文档更新记录为 2022-02-27，需 NDK/CMake | 维护与工具链成本较高；官方旧路线 ARM 方案不能直接当作当前 x86_64 模拟器可验证方案 |

当前 SDK 的[版本目录](https://github.com/PaddlePaddle/PaddleOCR/blob/main/deploy/ppocr-android/gradle/libs.versions.toml)使用 `com.microsoft.onnxruntime:onnxruntime-android:1.21.1`、`com.quickbirdstudios:opencv:4.5.3` 和协程。SDK 的 [manifest](https://github.com/PaddlePaddle/PaddleOCR/blob/main/deploy/ppocr-android/ppocr-sdk/src/main/AndroidManifest.xml)为空，没有声明 INTERNET。**空 manifest 不能替代依赖审计或设备网络隔离测试。**

模型建议先固定 `PP-OCRv5_mobile_det_onnx` + `PP-OCRv5_mobile_rec_onnx` 作为可比基线：官方当前 Android 指南支持该组合，官方模型页分别标注 Apache-2.0（[检测](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx)、[识别](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx)），识别模型包括简体、繁体中文。若评估 v6 tiny/small，须另外记录对应模型卡、许可证、版本、大小与哈希，不能沿用 v5 的声明。

源码许可已核对：[PaddleOCR Apache-2.0](https://github.com/PaddlePaddle/PaddleOCR/blob/main/LICENSE)、[ONNX Runtime MIT](https://github.com/microsoft/onnxruntime/blob/main/LICENSE)、[OpenCV 4.5.3 Apache-2.0](https://github.com/opencv/opencv/blob/4.5.3/LICENSE)。集成时仍须收集具体分发包及各原生库的第三方 NOTICE，不把仓库顶层许可证当作所有二进制依赖的完整清单。这是工程核查记录，不表示负责人已完成许可证审阅。

推荐的下一步具体变更：先移除 ML Kit 生产依赖与服务，把失败实验留为独立诊断；建立单独 OCR engine 模块，固定官方 SDK commit 和两份官方模型 SHA-256，打包只读模型资产。保留无网络权限的隔离服务，通过 AIDL 位图输入/结构化字符串结果输出；正式截图、裁剪图、待确认文字仍只在 RAM。官方 [ORTSessionManager](https://github.com/PaddlePaddle/PaddleOCR/blob/main/deploy/ppocr-android/ppocr-sdk/src/main/java/com/paddle/ocr/engine/ORTSessionManager.kt)从资产读模型并通过字节创建推理 Session，具备尝试无可写存储隔离环境的基础；这只是源码推断，尚未运行验证。

批准切换后，先做新的最小 spike，避免完整页面完成后才发现原生库不兼容：

1. 下载确定版本的依赖到 D 盘，检查实际 AAR 中 `arm64-v8a` 与 `x86_64` 的原生库、依赖关系和 16 KiB ELF/打包对齐；目前**没有下载检查，因此不宣称这些 ABI 已可用**。[ONNX 官方 Android 构建文档](https://onnxruntime.ai/docs/build/android.html)提供 ABI 构建选项与模拟器测试流程，但这不证明特定 Maven AAR 的内容。
2. 对具体 OpenCV 分发版本进行可加载性验证。Paddle 当前指南固定 4.5.3；其依赖维护方的[官方仓库](https://github.com/QuickBirdEng/opencv-android)明确提示部分 Android 版本应改用 4.5.3.0。不能直接复制指南坐标后声称 API35/37 可用，也不能擅自更新整个运行时组合。
3. 在同一无 GMS API35 模拟器执行 UID/INTERNET/Socket 拒绝断言，实际识别全部六张夹具，检查推理生命周期、取消后不提前释放位图、Session 释放、零截图落盘及零日志；通过后再进入 T22 的 UI 与状态机实现。
4. 再使用中端 ARM64 真机测三张截图，并区分首次模型加载与预热耗时；官方 Demo 的样例速度不可代替本项目截图与设备数据。

当前建议交负责人批准的是“用 PaddleOCR 官方源码 + 打包模型 + ONNX Runtime/OpenCV，继续尝试同等网络隔离”的技术路线。尚未引入 Paddle 依赖、下载模型、改动 OCR 引擎或宣称隐私/性能验收通过。

## WIP 回归验证

在保留上述真实设备失败的同时，运行 `:app:testDebugUnitTest :app:lintDebug :app:verifyRoborazziDebug` 成功（1 分 29 秒）。App JVM 测试 71 项，0 失败/错误/跳过；现有截图基线 verify 通过，没有重录；lint 为 0 errors、25 warnings。这里只证明该本机 WIP 的现有应用与粘贴退路未发生已测回归，不表示隔离 OCR 或 T22 通过。数据/设计系统/知识/领域模块测试未在本次额外重跑。实验源码与依赖改动没有提交或推送，继续保留为待选择引擎后的 WIP；本 PR 仅发布以上评估记录，不能从文档快照直接复现未提交的实验。
