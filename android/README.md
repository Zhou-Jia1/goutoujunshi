# 狗头军师 Android

开发中。功能完成情况以 [工作包进度](../documentation/android/06-开发进度.md) 为准。

## 环境与构建

- JDK 17、Android SDK platform 37.2、Build Tools 37.0.0；targetSdk 37、minSdk 26。
- Gradle Wrapper 固定 9.6.0；AGP 9.5.0；依赖全部固定于 `gradle/libs.versions.toml`。
- 设置 `JAVA_HOME` 指向完整 JDK；设置 `ANDROID_HOME` 或在未跟踪的 `local.properties` 中写 `sdk.dir=...`。JRE 或 Java 11 不可替代。
- Windows 中文路径还会影响 JVM 测试发现，建议使用下述脚本建立临时英文盘符；路径检查覆盖本身不足以解决该问题。

仓库根目录的 PowerShell 脚本只为本次构建分配空闲盘符，临时设置环境与本地 SDK 路径，退出后恢复；不要同时运行多个脚本实例：

```powershell
.\scripts\build_android.ps1 -JdkHome 'D:\tools\jdk-17' -SdkHome 'D:\tools\android-sdk'
# 可选：网络无法稳定访问 Maven Central 时，显式指定经你选择的 HTTPS 镜像。
.\scripts\build_android.ps1 -JdkHome 'D:\tools\jdk-17' -SdkHome 'D:\tools\android-sdk' -Tasks @(':core:domain:test') -MavenMirror 'https://maven-central.storage-download.googleapis.com/maven2/'
```

安装环境：SDK Manager 安装 `platforms;android-37.2`、`build-tools;37.0.0`、`platform-tools` 并接受许可证。[Android 17 官方配置说明](https://developer.android.com/about/versions/17/setup-sdk)。不需要修改全局 Java 或 SDK 设置。

```powershell
.\gradlew.bat assembleDebug test lint
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.shengjidaguai.goutoujunshi.debug/com.shengjidaguai.goutoujunshi.MainActivity
```

```sh
./gradlew assembleDebug test lint
```

根 Skill 与 references 是知识唯一来源；知识模块在构建时生成 assets 及 SHA-256 清单。没有 Key 的模型测试只使用明确标注的合成响应，不声称真实模型验证已通过。

不得提交 `local.properties`、API Key、密钥库、签名配置或真实聊天。临时会话只在进程内存中，正式草稿与业务资料使用加密数据层。

## 真正运行 APK 的回归

使用没有联系人资料的专用测试模拟器。脚本在安装前校验包名和 debuggable 标志，仅创建、操作并删除一位合成联系人，不清除应用数据；UI 树只在进程内存中读取。它会为该测试模拟器开启软键盘。

在仓库根目录运行（将工具路径和模拟器编号改成你的测试环境）：

```powershell
python scripts/smoke_android_m1.py --adb 'D:\tools\android-sdk\platform-tools\adb.exe' --aapt2 'D:\tools\android-sdk\build-tools\37.0.0\aapt2.exe' --serial emulator-5554 --apk android/app/build/outputs/apk/debug/app-debug.apk
```

检查快速输入、演示生成与复制、真实进程终止后正式草稿恢复、临时内容清除。CI 的独立设备任务同时运行该脚本和原生数据测试。仅检查 APK 身份时可加 `--check-apk-only`，不会访问设备。

导入流程使用相同的专用空白模拟器和工具参数：

```powershell
python scripts/smoke_android_import.py --adb 'D:\tools\android-sdk\platform-tools\adb.exe' --aapt2 'D:\tools\android-sdk\build-tools\37.0.0\aapt2.exe' --serial emulator-5554 --apk android/app/build/outputs/apk/debug/app-debug.apk
```

覆盖冷启动分享、相同内容热分享、目标与说话人确认、重复提示、纯证据演示分析、临时进程清除，以及正式加密记录在进程重启后再次分析；结束时只删除本脚本创建的合成联系人。该脚本不代替真实模型、OCR、SAF 系统选择器或真机验收。

模型配置回归只用于专用空白模拟器。它要求没有联系人、处于演示模式、尚未保存 Key，输入固定虚构 Key 后检查掩码、进程重启、移除与清除；不执行外部连接测试。**此脚本故意调用应用的全清除 UI 清理自身测试配置，不要对含有需保留资料的安装运行。**

```powershell
python scripts/smoke_android_model_settings.py --adb 'D:\tools\android-sdk\platform-tools\adb.exe' --aapt2 'D:\tools\android-sdk\build-tools\37.0.0\aapt2.exe' --serial emulator-5554 --apk android/app/build/outputs/apk/debug/app-debug.apk
```
