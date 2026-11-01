# T22 · PaddleOCR 开发记录

状态：隔离引擎最小验证通过；T22 尚未完成。负责人已确认采用 PaddleOCR 端侧方案，继续坚持不发送统计指标。完整需求见 03 T22；此前 ML Kit 失败记录保留于 `T22-隔离OCR验证.md`。

## 当前实现

- 在既有 `core/data` 的 `ocr` 包增加非导出的 `isolatedProcess` 服务，主进程不初始化引擎。唯一引擎创建点先检查本进程没有 INTERNET 权限，再加载原生库和打包资产。
- 复制 [官方 SDK](https://github.com/PaddlePaddle/PaddleOCR/tree/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/deploy/ppocr-android/ppocr-sdk) 的 26 个 Kotlin 源文件，保留 Apache 2.0 声明；只删除 OpenCVUtils 的原生异常日志，算法保持原样。代码位于 `core/data/src/main/kotlin/com/paddle/ocr/`。原始 git blob 哈希、分发源码哈希、模型 revision、字节数和 SHA-256 均见 `assets/ocr/paddle/provenance.json`。
- 固定 ORT 1.21.1 与官方 OpenCV 4.14.0；后者替代官方示例较旧的 QuickBird 4.5.3，原因是实际 AAR 的 64 位 ELF 对齐。版本差异已用同一设备实际推理验证，没有更新整个工具链。
- PP-OCRv5 mobile 检测、识别和对应字典配置均打包，无运行时下载器。两份模型 SHA-256 均与对应固定 revision 的官方 Hugging Face LFS OID 相符。
- 位图通过 Binder 传入；文字、四点坐标和置信度通过有界 JSON 回调返回。取消后等待阻塞 native 调用退出再释放位图；排队未启动的任务也在 Job completion 释放；Session 释放与推理使用同一 mutex。没有写截图文件、文字日志、SDK 网络初始化或 URI 持久授权。
- 此阶段服务只提供引擎接口，图片导入页面仍未接通。六图原图调用的引擎测试不等于完成长截图分块、相邻图去重、UI 过滤、左右建议与用户确认。

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

## 后续验收

- 接入分享和文件选择器的图片导入、顺序调整、分块拼接和相邻图去重；低置信度核对与逐行编辑。
- 识别左右仅为建议，同一导入必须明确确认“哪边是我”；未确认不得提交或触发模型分析。沿用 T21 当前会话/目标/版本隔离与统一发送预览。
- 增加取消、binder death、损坏图片、超限、旋转、旧回调、临时与锁定清理、来源删除回归；不可用时保留粘贴退路。
- 完整 JVM/截图/构建/lint 回归、六图“识别→确认→分析”原生流程、ARM64 中端真机三图性能与 16KB 设备验证。现有包体积较大，发布时须单独评估按 ABI 分发与 OCR 运行时体积，不用模拟器包估计发布体积。
- ORT LICENSE/ThirdPartyNotices、PaddleOCR/OpenCV LICENSE 已放入 assets/licenses；具体原生库第三方声明清单和最终许可证审阅仍需完成，不把顶层 LICENSE 当作所有二进制第三方许可的完整证明。
