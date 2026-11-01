# T22 · PaddleOCR 开发记录

状态：隔离引擎、应用接线、本机完整回归与负责人专用无 GMS 设备的六图真实 UI 闭环已通过；独立 SPEC 已正式工程 PASS，QUALITY 最终 PASS；性能、16KB 等外部门禁仍待验收，不宣称发布验收完成。负责人已确认采用 PaddleOCR 端侧方案，继续坚持不发送统计指标。完整需求见 03 T22；此前 ML Kit 失败记录保留于 `T22-隔离OCR验证.md`。

## 当前实现

- 在既有 `core/data` 的 `ocr` 包增加非导出的 `isolatedProcess` 服务，主进程不初始化引擎。唯一引擎创建点先检查本进程没有 INTERNET 权限，再加载原生库和打包资产。
- 复制 [官方 SDK](https://github.com/PaddlePaddle/PaddleOCR/tree/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/deploy/ppocr-android/ppocr-sdk) 的 26 个 Kotlin 源文件，保留 Apache 2.0 声明；只删除 OpenCVUtils 的原生异常日志，算法保持原样。代码位于 `core/data/src/main/kotlin/com/paddle/ocr/`。原始 git blob 哈希、分发源码哈希、模型 revision、字节数和 SHA-256 均见 `assets/ocr/paddle/provenance.json`。
- 固定 ORT 1.21.1 与官方 OpenCV 4.14.0；后者替代官方示例较旧的 QuickBird 4.5.3，原因是实际 AAR 的 64 位 ELF 对齐。版本差异已用同一设备实际推理验证，没有更新整个工具链。
- PP-OCRv5 mobile 检测、识别和对应字典配置均打包，无运行时下载器。两份模型 SHA-256 均与对应固定 revision 的官方 Hugging Face LFS OID 相符。
- 位图通过 Binder 传入；文字、四点坐标和置信度通过有界 JSON 回调返回。取消后等待阻塞 native 调用退出再释放位图；排队未启动的任务也在 Job completion 释放；Session 释放与推理使用同一 mutex。没有写截图文件、文字日志、SDK 网络初始化或 URI 持久授权。
- 最初引擎阶段仅提供接口；以下旧设备结果只证明当时的引擎边界。应用接线与后续独立验证见文末，不把六图原图调用等同于完整 UI 验收。

## 实际验证

仅使用专用 `emulator-5580`，API35 / Android15 / x86_64 AOSP；GMS 包查询为空。未操作用户 emulator-5556，未替换已有应用，仅安装 `com.shengjidaguai.goutoujunshi.data.test`。

1. `:core:data:assembleDebugAndroidTest` 首轮使用 384m、in-process、单 worker：生产及设备测试源码编译通过，D8 合并时出现 `OutOfMemoryError: Java heap space`。原日志 `R:/task-tmp/t22-paddle-spike-build-01.log` 保留。
2. 同一冻结源仅把本次构建堆改为 768m，65 秒 BUILD SUCCESSFUL / exit0；未修改默认 Gradle 配置，C 盘保持 12GB 以上。日志 `t22-paddle-spike-build-02.log`。
3. 显式执行 `PaddleOcrIsolationTest`，`OK (1 test)`，总 8.698 秒。测试首先证明宿主具备 INTERNET 权限，再断言服务 UID 不同、服务 INTERNET 被拒、Socket.bind 被拒；随后六图各断言至少四行、合成关键词存在、四点坐标和有效置信度。输出仅夹具标识、耗时与行数。

| 合成图 | 端到端调用毫秒 | 检测行数（含待过滤 UI 文本） |
| --- | ---: | ---: |
| light_1（首次含模型加载） | 2716 | 10 |
| light_2 | 1000 | 10 |
| dark_1 | 647 | 10 |
| dark_2 | 738 | 10 |
| long | 2296 | 28 |
| group | 635 | 13 |

这些是模拟器数据，不能作为“中端机三图小于三秒”的验收；也没有测完整文本准确率或完整导入 UI。设备日志 `R:/task-tmp/t22-paddle-isolation-device-01.log`。测试 APK 270181781 bytes，SHA-256 `7421bfd6b664315e8da179e1c764b27c2720e421e720dd2e2692c8a7a39c115d`；这是含四种 ABI 的调试设备测试包，不是用户使用的 App 安装包或发布体积结论。

4. `zipalign -c -P 16 4 data-debug-androidTest.apk` 返回 0；具体 ORT/OpenCV 的 arm64-v8a、x86_64 ELF 均为 16384 PT_LOAD 对齐。仍没有运行 16KB 页大小设备，不推广为该机型验收。
5. `python -B scripts/validate_paddle_assets.py` 校验三份模型/配置和 26 份分发源码通过；Android 模块边界与 `git diff --check` 通过。CI 增加同一资产校验命令，但 fork Actions 已禁用，尚无本轮云端成功结果。

## 资源安全专项复修与复验

阶段静态审查发现两处资源边界问题，已修复并实际复验：

- 初版 SDK 的可取消 dispatcher 切换可能在冷加载期间丢掉已创建引擎的返回值。新增生产 `OcrEngineLease`，同一 mutex 内以 NonCancellable 创建并赋值，随后检查取消；close 同样使用该 mutex，不能提前释放 native 正在使用的 Session。
- 初版 `jobs.size` 与登记不是原子操作。改用非阻塞 Semaphore 准入与幂等 permit，重复 ID、未启动取消、任务完成路径都释放相同 permit；最多持有两份已接收输入。

新增三项 JVM 回归直接使用生产 lease/admission：冷加载取消仍可释放一次、阻塞 native 返回前不能释放、32 个并发申请最多保留两份输入且重复 close 不增加容量。为了验证回归有效性，临时恢复旧不安全冷加载实现，实际得到 1 项失败（应释放 1 次，实际为 0）；finally 已恢复安全源并校验 SHA-256 完全相同。红测日志/XML `t22-runtime-cold-load-red-04.*` 保留。随后安全实现 `t22-runtime-safety-green-05.log` BUILD SUCCESSFUL 56 秒，三项全部 0 failure / error / skipped，设备测试包完成打包。原 SDK 算法源码和模型未修改。

修正后的 APK SHA-256 为 `77328abc021250b99bca5eedb5a9fab13b29c42abc38908fb77be3c9718eac1f`，已重新安装并显式重跑同一六图隔离设备测试：`OK (1 test)`，总 7.099 秒，日志 `t22-paddle-isolation-device-02.log`。不能把前面的初版六图结果当成未复验的修正后结果；此处是独立的新结果。阶段静态复核未再发现原范围的新阻断问题，仍不构成 T22 完整 SPEC / QUALITY 或发布验收。

本工作区的 Python 50 项、Skill、模块边界与资产校验通过；没有第二个并发 Gradle，也没有操作用户 emulator-5556。

复现时沿用项目 JDK17/SDK37.2 与临时目录设置；不执行 `connected*` 自动扫描用户设备：

在 `android/` 目录构建引擎回归和测试包（Windows 使用 `gradlew.bat`）；可选的低内存 init 文件已随本分支提供，不依赖另一工作区：

```sh
./gradlew :core:data:testDebugUnitTest --tests '*OcrRuntimeSafetyTest' :core:data:assembleDebugAndroidTest --no-daemon --max-workers=1 --no-parallel --init-script low-memory.init.gradle -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx768m -Dfile.encoding=UTF-8'
```

```powershell
R:/sdk/platform-tools/adb.exe -s emulator-5580 shell am instrument -w -r -e class com.shengjidaguai.goutoujunshi.data.ocr.PaddleOcrIsolationTest com.shengjidaguai.goutoujunshi.data.test/androidx.test.runner.AndroidJUnitRunner
```

## 应用接线（2026-11-01，本机回归及六图真实 UI 已通过）

- 分享支持 `ACTION_SEND` / `ACTION_SEND_MULTIPLE` 图片流及 ClipData；首页与附件图片入口使用系统选择器。至多 20 张，仅接受 content URI，不持久授权。原选择顺序和稳定图片 ID 保留，长按拖动可上下重排，另有无障碍上移/下移按钮；原选择编号不会随重排改掉。每行也有稳定 ID，延迟的旧 index / preview 回调不能改到重排后的另一行。
- `PaddleImageReader` 在 IO 线程顺序处理图片/区域。每图编码上限 25 MiB、宽 16000、高 100000、像素 1.2 亿；不把整张长图解码成大 Bitmap。按宽度采样后以约 1600 高、200 重叠的区域识别，重叠中心归属保证拼接时一行只归一个 tile。每个客户端批次顺序保留一张推理输入，不同时解码 20 张大图；服务端继续使用先前原子准入的最多两份输入边界。解码、结果与 URI 均仅 RAM，没有图像缓存或删除用户原图。
- 一个批次共用一次隔离服务绑定，唯一 SDK 初始化点仍在隔离服务。读取可中断，30 秒读超时、20 秒绑定超时、90 秒单块推理超时；Binder death/结果/取消采用一次响应边界，取消不会提前释放服务 native 持有的 Bitmap。错误只映射静态原因与粘贴退路，不打印原文或异常数据。
- 领域模块维护 tile、左右建议、UI 建议过滤和相邻 suffix/prefix 去重策略。文字几何结合气泡背景平坦区域采样给出左右建议，仍必须显式确认一次“哪边是我”。本次导入沿用选择，居中未知行仍要逐条确认；短名、上方邻接、相对缩进与宽度共同形成昵称建议，不以 OCR 检测框高度代替字号；多昵称对应 OTHER 与昵称。逐行可以编辑内容、昵称、说话人。
- 状态栏、标题、输入区、时间及正在输入等界面行，与相邻图重叠内容，均为可恢复的忽略建议。同图的真实重复保留；手工恢复或忽略后再排序仍尊重用户选择。低于 0.8 的识别行带标记，核对后才可确认。
- 继续走 T21 `ImportCoordinator → commitImport`：权利提示、目标来源确认、明确左右、所有有效行说话人及低置信度核对、总行/字节预算全部满足后才能提交。OCR confidence 沿已有 ChatLine 字段传入统一加密存储；临时导入仍仅 RAM。其后沿既有统一脱敏、实际发送预览与 Demo / 自带 Key 分析，不建立旁路请求。
- 应用锁或会话 authority 改变、关闭或新分享均清空旧预览/在途结果；正式确认成功也清空图片中间行。表单复用 DS 的 state TextField，未用 rememberSaveable/SavedStateHandle。别名编辑器按 previewId 清空，行修改按稳定身份合并，避免泄漏到新预览。
- 六张确定性合成 PNG 同步放在 `app/src/androidTest/assets/ocr`，生成脚本为 `scripts/generate_ocr_fixtures.py`；无真实聊天输入。debug-only 的非导出 provider 只允许六个固定资产名，以 RAM pipe 供真实生产 URI 解码路径测试，release 源集不含 provider 或夹具。

### 本机测试与失败修复证据

Gradle 全程仅一个窗口、no-daemon、max-workers=1、no-parallel、Kotlin in-process；测试 worker 256 MiB，编译专项 384 MiB，D8/lint 打包 768 MiB，临时目录在 R。未操作任何设备。

- `t22-app-compile1.log`：init script 相对路径重复 android，命令失败；改为 R 下绝对路径。`t22-app-compile2.log`：首期开发中源与旧 domain jar 不一致导致 nickname 未解析，此轮不计通过；后续每轮构建前冻结全部输入，不在运行中改源。
- `t22-app-test1.log` / `t22-app-test2.log`：28 项导入回归中取消/owner 用例 red。实际定位到 coroutine receiver 内裸 `cancel()` 绑定了 CoroutineScope，未清协调器预览；改成 `this@ImportCoordinator.cancel()`，同时修 T21 parse 的同型接收者位置。`t22-app-test3.log` 的导入 28/28 green；该轮 native 编译另因 androidTest Compose BOM 缺失失败，随后补与现工程相同 BOM。
- `t22-newshots-nativecompile.log`：仅新 T22 测试类 record 9 张图，native 源编译通过。`t22-package-lint1.log`：App/test APK 已打包，lint 唯一 error 是本机忽略文件 local.properties 的 Windows 盘符未转义。仅修本机路径，不新增 baseline、不禁用检查；`t22-package-lint2.log` 打包/lint 成功。
- `t22-full-verify-package-lint.log`：domain 40 / data 58 / knowledge 14 全过，app 250 中 249 过；唯一旧测试仍期待 OCR_PENDING 占位文案。将旧测试改为真实图片选择器 dispatch 断言。
- `t22-full-verify-package-lint2.log`：**BUILD SUCCESSFUL，3m22s，270 tasks；608 项 JVM 全部 0 failure/error/skipped**，其中 domain 40、data 58、knowledge 14、app 250、designsystem 246。App 与 DS 共 **303 PNG verify 通过（旧 294 + 新 T22 9）**；旧 PNG 的 git diff 为 0。只对新 `PaddleImportScreenshotTest` 执行过 record，没有 record 旧图。
- `python -B -m unittest discover tests`：52 项通过；Skill、模块/颜色边界、Paddle 3 资产和 26 SDK 源完整性校验通过；`git diff --check` 通过。

最终完整命令（在本工作区根；JDK/SDK/TEMP 按上述 R 配置）：

```powershell
./android/gradlew.bat -p android :core:domain:test :core:data:testDebugUnitTest :core:knowledge:testDebugUnitTest :app:verifyRoborazziDebug :core:designsystem:verifyRoborazziDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --init-script R:/t22-paddle-native/android/low-memory.init.gradle --no-daemon --max-workers=1 --no-parallel '-Dorg.gradle.jvmargs=-Xmx768m' '-Pkotlin.compiler.execution.strategy=in-process' '-Pjunshi.mavenMirror=https://maven-central.storage-download.googleapis.com/maven2/' --console=plain
```

原生 `T22PaddleUiTest` 使用真实引擎逐张走六图的分享 → 本机识别 → 明确左右/逐行核对 → 临时导入 → 统一发送预览 → Demo 分析；额外断言长图真实重复保留 24 条、群聊多昵称仍绑定 OTHER。该测试初轮真实失败，经以下专项修复后在同一专用设备完整重跑通过；设备与 APK 证据见下节，编译/打包本身不计设备通过。 负责人仅对其专用 emulator-5580 显式执行单 class/method，不使用 connected* 自动扫描设备：

```powershell
R:/sdk/platform-tools/adb.exe -s emulator-5580 shell am instrument -w -r -e class 'com.shengjidaguai.goutoujunshi.T22PaddleUiTest#sixRealFixturesThroughSideConfirmationImportPreviewAndDemoAnalysis' com.shengjidaguai.goutoujunshi.debug.test/androidx.test.runner.AndroidJUnitRunner
```

## 真实群聊 red → 几何诊断 → green 与最终冻结

负责人只操作专用 emulator-5580：API35 / Android15 / x86_64 AOSP，无 GMS；1080×2400 / dpi420 / font1，Demo / NoKey，只有合成数据。本应用代理未执行 adb。

1. `t22-real-six-ui-native-01.log`：初轮真实 **FAIL**，JUnit 1 failure，38.575 秒。前五张（含长图 24 条真实重复及后续分析）通过；第六张群聊昵称未附着正文，`distinct speakerName >= 2` 强断言失败。没有用 am 的 exit0 冒充通过，没有减弱断言。
2. `t22-synthetic-group-geometry-native-02.log`：独立 `T22PaddleDiagnosticTest.syntheticGroupGeometryOnly`，只允许硬编码合成 group 资产，经生产 reader 读取；OK(1 test)，1.544 秒。13 行均返回，三个昵称均未附着。实测昵称框高度/正文框高度分别 **39/42、35/38、35/35**，均不满足旧 `< .88` 比例。因此旧逻辑把检测框高度误当成字体大小，不是数据落库或映射丢失。诊断原文/坐标输出仅存在于这个固定合成资产的 androidTest，生产没有添加日志。
3. 将昵称识别移到纯 domain `attachNicknames`：短名字符、上方邻接、正文相对缩进与宽度共同约束，不依赖字号比例，也不硬编码夹具名字；昵称低置信度向附着正文传播。新增真实三组 bbox 回归及正常短消息/同气泡多行/不同气泡的负例。实际无空格 `今天09:30` bbox 也新增为正例；诊断里的 `ui=false` 只是 reader 像素区标志，最终时间过滤原本在 domain，仍确实为 true。
4. 另修未确认 OCR 行的原图左右展示与“建议左侧/右侧 · 待确认”标签，身份仍未确认且不能提交。新增左右显示 bounds UI 回归；首次因 Lazy 列表未显示右行而获得裁剪 bounds 的 red 保留于 `t22-group-fix-package.log`，逐行滚动显示后维持原 `right > left` 强断言 green，见 `t22-group-fix-package2.log`。
5. 负责人使用新 App 与原强断言 test 包完整重跑六图：`t22-real-six-ui-native-03.log`，**OK (1 test)，42.435 秒**。六张真实引擎识别 → 明确左右/低置信核对 → 目标确认/导入 → 真实知识与实际发送预览 → Demo 分析均通过；long 24 条真实重复、group 至少两个昵称与 OTHER 强断言全部通过。这是 UI 套件总时间，绝非中端机三图 OCR 性能结果。
6. 当前同一生产源的完整 `t22-group-full-verify-lint.log`：**BUILD SUCCESSFUL，2m56s，209 tasks；614 项 JVM 全部 0 failure/error/skipped**：domain 42、data 58、knowledge 14、app 254、designsystem 246。**306 张 PNG verify：旧基线 294 + 先前新增并只 verify 的 T22 9 + 本次只 record 独立新 `PaddleGeometryScreenshotTest` 的 3**。旧图 git diff 为 0；没有 record 任何原有截图。新三张为 `t22_geometry_{light,dark,font260}.png`，原九张为 order/side/review × light/dark/font260。

native03 设备通过的历史冻结包（Photo Picker 修正前）：

| APK | 字节 | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 280715431 | `dd3d8f8c4f889b23c28aad7135a5ad97de248831be4ae5129381ca5900d80f3c` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 1698096 | `48329a1f5c613768fdf9b93afba783d4a0ba182158abb36e07543fe2f9889614` |

这两个历史阶段 APK 通过后保持了生产与 native 测试源冻结，随后 SPEC/QUALITY 修正与当前最终结果见后节。完整回归期间只产生 JVM 新截图，没有更换设备上的 App 或 native 测试包。所有构建日志留在 `R:/task-tmp`，没有自行提交、推送或操作用户 emulator-5556。

## SPEC 修正：默认 Photo Picker（当前候选，2026-11-01）

独立 SPEC 指出 02 第 8/12 节要求默认 Photo Picker；此前 `GetMultipleContents` 只是 GET_CONTENT 文档入口，属于实现不符合规范。已改为实际页面调用的 `ScreenshotPhotoPicker.contract()` / `request()`：`PickMultipleVisualMedia(20)` 与 `PickVisualMediaRequest(ImageOnly, maxItems=20, isOrderedSelection=true)`。

- 对照 [Android 官方 Photo Picker 指南](https://developer.android.com/training/data-storage/shared/photo-picker) 与已锁定的 [Activity 1.11.0 官方源码包](https://dl.google.com/dl/android/maven2/androidx/activity/activity/1.11.0/activity-1.11.0-sources.jar) 读取实际实现；源码包 SHA-256 为 `c5c0ec34e54ce4ca4d7f29010096d670f4fa3ec6528b57c48801b7e090ac2b6a`，没有更新依赖版本或复制其源码到应用。
- 使用 AndroidX 原生兼容链：系统 ACTION_PICK_IMAGES → 系统 OEM picker → ACTION_OPEN_DOCUMENT；只请求 image/*，不加 GMS 依赖、相册全读权限或持久 URI grant。请求有序选择；应用保留 AndroidX 返回 URI 的顺序供确认/重排。
- 官方回退文档选择器可能不执行 maxItems，所以协调器在识别前再次拒绝超过 20 张或非 content URI，不静默截断、不读取被拒内容；data 解码器继续独立限制。选中的 URI 与未确认识别结果继续仅 RAM。
- 新增 `ScreenshotPhotoPickerTest` 7 项实际 contract 测试：API35 的 action/image MIME/20 上限/有序 extra，API28 无 picker/GMS 的多选 OPEN_DOCUMENT 回退，API28 系统 OEM picker 优先级，以及 URI 返回次序/取消、20 张允许但未确认、21 张和非 content URI 在读取前拒绝。并非仅测试 UI callback 计数。
- `t22-photopicker-tests.log` 专项 12/12（7 新项 + 5 既有导入回归）；`t22-photopicker-full-verify-package-lint.log` **BUILD SUCCESSFUL，2m34s，270 tasks**：**621 项 JVM 全部 0 failure/error/skipped**，domain42/data58/knowledge14/app261/designsystem246；**306 PNG verify 全过，截图零修改**；App/test APK 重打及 lint 通过。没有 record 截图。

Photo Picker 阶段候选包：App `a6d5a660efb3971af1ca7d2572f43d28f52a6eee004e9086f830d3f33ec62362`，280715431 bytes；test 仍为 `48329a1f5c613768fdf9b93afba783d4a0ba182158abb36e07543fe2f9889614`，1698096 bytes。路径与上表相同。此前 native03 的真实六图 PASS 对应 dd3d8f8c 旧 App，不把旧设备证据冒充此新候选。负责人随后独立安装 a6d5a660 App 与 48329a1f test，并完整重跑原六图强断言：`t22-real-six-ui-native-04.log`，**OK (1 test)，45.993 秒**，读取的是实际 JUnit 结果，非只看 am exit code。原 long24、群聊 ≥2 昵称/OTHER、统一预览与 Demo 断言完整保留且通过；M1/import/settings smoke 与独立 QUALITY 仍由负责人继续，生产和 native 测试源继续冻结。

只读审查另提示未返回的 picker 与新 owner/分享等状态变化之间的回调归属风险：singleTop 允许多个 MainActivity，分别的 ViewModel 共用进程内 ImportCoordinator；若 A picker 未返回时由 NEW_TASK/MULTIPLE_TASK 分享到 B，新 preview 建立后 A 返回旧图片，当前 callback 无 launch stamp 可能再次 begin 覆盖新 RAM 预览。这是代码与生命周期推导，未进行该双 task 设备复现；既有目标/左右确认仍阻止未经确认的持久化或分析，不能据此声称已静默错绑发送。负责人 QUALITY 正独立评估，不在本轮擅自改源。复杂群聊、多行同气泡的后续行昵称仍可能需要逐行人工核对；现有可编辑建议不代表这些复杂样式已自动归属验收。

## QUALITY 两项 P2 修复与当前冻结候选

独立 QUALITY 要求修复两处实际边界；本节替代前节“picker 风险待判断”的当前状态，历史分析保留。

1. **跨图去重包含已识别昵称**。统一使用纯 domain `OcrLayout.sameMessage`：text/side/name 均一致才认为重复。不同昵称，或仅一侧有昵称，都保留；普通双未知昵称仍按 text/side 去重。同名真实重叠继续是可恢复建议，同图的真实重复不去重。domain 与真实 ImportCoordinator 跨图回归覆盖不同昵称及一侧未知。
2. **每 Activity ViewModel 的一次性 RAM 选择 slot**。图片/文件两个固定 AndroidX launcher 移至导航根，覆盖 import overlay 时不注销。二者共用每 VM 单个在途 slot，区分 IMAGE/FILE；launch 前捕获共享 coordinator epoch/conversation/owner/preview 与仅 RAM 的选择 revision。失效只令返回被拒，原 slot 保留至终态（含取消），期间拒绝重入；callback 先消费原 slot，再检查归属，过期或空结果不 begin/cancel 新预览。不同 VM 的较新选择也使旧 revision 无效。不用每票独立 registry key，不存 URI、SavedStateHandle 或 ticket Bundle。配置重建复用同 VM，进程重建无 slot 的旧结果被丢弃。
3. 测试通过真实 `CounselViewModel → ImportSelectionSession → ImportCoordinator` 边界，仅底层持久化使用既有 Fixture；不是独立 fake if。包括 A/B 两 VM 共 runtime 的新分享覆盖、owner/clear/cancel、重入、取消、重复/错误 kind 回调、ViewModelStore 配置保留、无 ticket 进程重建、文件入口同护栏与合法文件实际读取。原 Application 构造仍保留；额外 internal 构造只使这条边界能在 JVM 使用真实对象验证。
4. OCR 错误重试文字改用已有 DS“重试”，保存错误仍保留“重试保存”。没有新增 UI 功能或 record 旧图。

红绿证据：

- `t22-quality-red.log`：旧比较器的 domain 1 项、app 3 项实际 red（跨昵称误删、无 ticket 结果建预览、旧 VM 结果覆盖新分享），没有以编译失败冒充红测。
- `t22-quality-green.log`：20 项中 1 项仍失败。该断言错误地要求 clear 回到无 conversation 的欢迎状态后可立即新 launch；旧结果不改变 epoch/preview 的强断言原本已过，测试在重新 initialize 后才验证新选择可用。
- `t22-quality-green2-package.log`：新合法文件用例误给 Robolectric `getApplication()` 加泛型，编译失败，已修测试调用。
- `t22-quality-green3-package.log`：专项 **21/21 green**（slot/VM 8、Photo Picker 7、协调器 6），domain 布局专项全绿，App/test 打包成功，1m3s。
- `t22-quality-final-verify-lint.log`：全套仅 T43 list_light/list_dark 日期像素不符。只读 compare 证实仅 `2026-10-30 → 2026-10-31`；宿主已变为 China Standard Time，旧基线在 LA 生成。按负责人明确授权，仅给 T43 截图测试 @Before 固定 America/Los_Angeles，@After 恢复原 TimeZone，**未改生产日期显示、未改宿主时区、未 record 旧图**。原 compare 留于 `t22-t43-timezone-{light,dark}-compare.png`；`t22-t43-timezone-verify.log` 9 项强截图 verify 独立通过，56 秒。
- `t22-quality-final-verify-lint2.log`：**BUILD SUCCESSFUL，2m35s，209 tasks**；**631 项 JVM 全部 0 failure/error/skipped**（domain43 / data58 / knowledge14 / app270 / designsystem246）；**306 PNG verify 全过，旧 PNG git diff 为 0**，lint 通过。当前这轮没有 record 截图。负责人另独立读取五模块 XML 验证计数，lint XML 为 0 Error / 36 Warning / 2 Hint（SARIF 将 Warning 与 Hint 共 38 项均标为 warning）；Python 52/52、Skill、模块边界及资产完整性独立通过。

当前运行包（生产/native 测试源继续冻结）：

| APK | 字节 | SHA-256 |
| --- | ---: | --- |
| App | 280715431 | `01c4e55ca76fb5c3a6df1e8402cfc1c6243c06308171702f5f4d7f4d843492b4` |
| AndroidTest | 1698096 | `48329a1f5c613768fdf9b93afba783d4a0ba182158abb36e07543fe2f9889614` |

负责人独立新包设备复验：`t22-quality-final-six-native-11.log`，**OK (1 test)，50.731 秒**，完整六图及 long24/group≥2 昵称+OTHER 强断言仍未改变。负责人同一 01c4 包后续独立设备结果已齐：native12 M1 六项、native13 文字导入五项、native14 设置四项、`t22-quality-final-picker-native-15.log` 系统选择器/真实取消/不存在 content URI 的 OCR_READ 与禁确认/关闭后恢复粘贴共四项均 PASS、exit0。独立 QUALITY 最终 PASS，无新增 P2。原生六图单 test 内仍含六张完整流程，不把套件数当成六个独立 JUnit；不以旧 a6 或 dd3 证据替代本包。

负责人新增可复现 `scripts/smoke_android_ocr.py`，在先前 a6 包及最终 01c4 包均实跑 4/4；该脚本与 CI 单行接线由负责人拥有，未修改 Gradle/connected 任务，Actions 禁用状态下不声称云端 green。只在 API33+、具备系统 picker 的空专用 debug 模拟器运行；serial 必须显式指定，不能扫描任意设备：

```powershell
python -B scripts/smoke_android_ocr.py --serial emulator-5580 --apk android/app/build/outputs/apk/debug/app-debug.apk --adb R:/sdk/platform-tools/adb.exe --aapt2 R:/sdk/build-tools/37.0.0/aapt2.exe
```

## 剩余设备与发布门禁

工程接线提交为 `cac93e8`，随后正常合并父 T43 的最终文档提交 `f9b78a6`，没有重写历史或改变运行源。51 文件提交前冻结清单 SHA-256 为 `e5da80bf6dadcac83dc3f62bed2626519f0fdfca5307b4d3a374e20f7810d2fb`；负责人逐项核对全部字节哈希和 git 改动集合相同后提交。最终 lint 为 **0 Error、36 Warning、2 Hint**；SARIF 将两类非错误合计表示为 38 条 warning level。未合并或发布草稿 PR。

- 六图完整原生 UI 流程及实际分块/群聊断言已实际通过；binder death、损坏/撤权/超限图片、配置旋转与锁定/来源删除的设备专项仍须补实际证据。JVM 有 fake OCR 的取消、owner stale、重试、侧确认、群聊、重排恢复与临时不落库回归，不冒充上述设备覆盖。
- 中端 ARM64 真机三图 <3 秒、16KB 页设备、真实 SAF/自带 Key 等仍未验证。原引擎模拟器时间不用于这些验收。
- 当前 App 为含四 ABI 的大型 debug 包，不能当作最终发布体积。正式按 ABI 分发、OCR 运行时与模型体积须另评估。
- ORT LICENSE/ThirdPartyNotices、PaddleOCR/OpenCV LICENSE 已放入 assets/licenses；具体原生库完整第三方 NOTICE 与最终许可证审阅仍待补齐，不把顶层 LICENSE 当作所有二进制第三方许可的完整证明，不自行发布或合并。
