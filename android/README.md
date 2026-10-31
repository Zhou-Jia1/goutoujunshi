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

根 Skill 与 references 是知识唯一来源；知识模块将在 T15 接入构建时生成 assets 及 SHA-256 清单。没有 Key 的模型测试只使用明确标注的合成响应，不声称真实模型验证已通过。

不得提交 `local.properties`、API Key、密钥库、签名配置或真实聊天。临时会话只在进程内存中，正式草稿与业务资料使用加密数据层。
