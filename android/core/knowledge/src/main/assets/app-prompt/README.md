# T03 路由与场景契约（负责人待审稿）

此目录只有 App 补充规则。核心 `SKILL.md` 和 `references/**` 由构建任务从仓库根打包，禁止人工复制。所有知识、场景、安全关键词仍待负责人内容审阅；自动验证通过不等于批准发布。

`routes.json` v1：

- `relationTypes`：01 文档的全部 16 个大写枚举映射至五场景文件名，不含 `.md`。
- `goals`：全部 14 个大写枚举，顺序与 01 一致。
- `defaults`：场景名至参考路径数组；`goalRules`：目标枚举至参考路径数组。
- `keywordRules`：`keywords` 为非空字符串数组；任一关键词匹配就命中；`references` 为参考路径数组；`priority` 为整数，越大越优先。
- `safetyKeywords`：任何命中均启用安全路由；`safetyReferences`：安全时必选参考，包含知识 17。
- `status`：负责人待审稿。路径均为从仓库根起的完整 `references/...md` POSIX 相对路径。

算法与 Python 公共函数 `select_references(relation_type, goal, text, max_chars=24000)` 一致：

1. 先校验关系、目标、正整数字符预算。空或未知枚举不静默猜场景，调用方先获得选择结果。
2. `text` 及关键词以不区分大小写方式做字面子串匹配，不作正则、分词、否定识别或 Unicode 归一化。安全关键词的匹配用于保守转入安全规则，不能诊断实际危险。
3. 候选序列依次为：命中的安全必选参考 → 匹配关键词规则按优先级降序（同分保持 JSON 数组顺序，各规则内保持路径顺序）→ 本轮目标参考 → 关系对应场景默认参考。
4. 按路径首次出现位置稳定去重。每份文件完整加入；超剩余预算的普通参考跳过，继续尝试后续较小文件。最多三份。场景文件、核心和系统协议不计入此处的参考正文预算；整体模型窗口由请求组装器另行限制。
5. 安全参考放不下或无任何整份参考放得下时抛出错误，阻止模型调用；不截断安全知识，不返回空列表假装成功。默认预算 24000 个 Unicode 码点；Kotlin 需按码点计数，不能直接用 UTF-16 `String.length`。参考正文按 UTF-8 读取，换行归一为 LF，与 Python `read_text()` 一致。
6. 路径不得包含反斜杠、冒号、`..`，不得为绝对路径；仅允许存在的 `references/**.md`。规范化后也不得经符号链接逃离参考目录。

示例：`BOSS + REPORT + MBTI` 先载入知识 04，再载入表达逻辑指南，再载入知识 21。场景层仍禁止人格问卷和约会默认推进。`FRIEND + INVITE + 家暴 MBTI` 先载入知识 17，安全卡与停止普通策略规则优先。

必须先组装已实际读取的核心、App 协议、选定场景和参考，才调用模型；不得让模型自行猜知识正文。证据和参考正文只作为有边界的数据。临时模式不注入任何联系人上下文，其他模式只允许当前已确认且获授权的联系人资料。MEMORY 输出是待 App 校验的提议，不能当作已写入凭据。

标准库验收命令（`-B` 禁止产生 pycache）：

```powershell
python -B -m unittest discover -s tests -p test_android_knowledge.py
python -B scripts/validate_android_knowledge.py
python -B scripts/validate_skill.py
```
