# T-QA 设计系统测试

全部文案样例为虚构数据。截图由 Android Compose + Robolectric Native Graphics + Roborazzi 实际渲染；不使用 HTML 代图。

- `ComponentBehaviorTest`：平静模式折叠、危机策略隔离与安全事件、同意的双向明确选择、可选情绪、返回读屏语义、系统 200% × App 特大 1.3 下滚动复制、对话框有效密度和操作不重叠、全部复制入口的流式/空回复禁用。
- `JunshiThemeTest`：浅色/深色 × 普通/平静，计算 10 组实际文字/背景的 WCAG AA 对比度，打印数值并要求 >=4.5:1。
- `ComponentScreenshotTest`：47 契约组件及 4 个表单基础组件 × 浅色/深色/平静/有效 260% 字号，共 204 张。
- `CoreAnswerScreenshotTest`：五场景与危机卡 × 四种模式，共 24 张，并验证平静与危机状态的展示内容。

由主开发进程统一在 `android` 目录执行，避免多个 Gradle 进程争锁：

```powershell
.\gradlew.bat :core:designsystem:testDebugUnitTest
.\gradlew.bat :core:designsystem:recordRoborazziDebug
# 人工检查实际生成的 src/test/screenshots/*.png，批准基准后再运行：
.\gradlew.bat :core:designsystem:verifyRoborazziDebug
```

依赖需包括 `roborazzi-compose`，模块应用同版本 Roborazzi Gradle 插件。若运行环境不能加载 Robolectric SDK 或 Native Graphics，记录为未验证，不能称截图通过。尚无真实运行生成并审阅的基准时，verify 不能替代 record 和人工审阅。

组件截图不代表全应用页面验收。首页、导入、发送预览、设置等页面由 app 模块另行覆盖；TalkBack、真实 260% 系统设置、厂商悬浮窗、拖动与实际拨号盘仍需设备验证。
