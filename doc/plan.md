# Codly 项目现状审计与规划

> 更新时间：2026-05-02
> 说明：本次已按当前代码库重新核对，替换了 2026-04-19 版本中已过时的判断。

---

## 一、项目概览（基于当前仓库）

**Codly** 是一个基于 **Java 17** 的终端 AI 编程助手 CLI，当前代码库包含：

- `src/main/java` 下 **57 个 Java 源文件**
- `command/` 下 **9 个已注册斜杠命令**（另含 `/skill-<name>` 动态命令）
- `function/` 下 **9 个内置工具函数**
- `llm/` 下已抽象 `LlmProvider`，但当前仅落地 `QwenLlmClient`

**当前主结构：**

```
CodlyMain (启动 + 主循环)
├── CommandDispatcher (Slash 命令路由)
├── SessionManager (会话生命周期 / 标题 / 记忆触发)
├── ResponseRenderer (流式输出 / Markdown 渲染 / Token 统计)
├── QwenLlmClient (LLM 调用 + tool_calls 编排)
├── FunctionManager (9 个内置工具)
├── MemoryManager (会话落盘 + 长期记忆提取)
├── SkillRegistry (skill 装载与激活)
└── SystemInfoManager (系统/项目信息采集)
```

**当前已实现的主要能力：**

- CLI 对话式编程
- 工具调用：`read_file`、`write_file`、`edit_file`、`search_file`、`grep`、`list_directory`、`Bash`、`system_info`、`web_search`
- 长期记忆查看与清理：`/memory`
- skill 生成与激活：`/gen-skill`、`/skill`、`/skill-<name>`
- 流式输出增强：Markdown 渲染、spinner、Token 统计

---

## 二、相对旧版报告，已过时的结论

以下内容在旧版 `plan.md` 中成立，但**现在已经不准确**：

1. **“fastjson 1.2.83 有历史漏洞，应升级”**
   - 当前 `pom.xml` 已升级为 **`fastjson 2.0.61`**。
   - 这条建议应从“待处理”改为“已完成，但仍建议持续关注依赖升级”。

2. **“CodlyMain 职责过重，需拆分出 SessionManager / ResponseRenderer”**
   - 当前 `CodlyMain.java` 已明显瘦身。
   - `SessionManager.java` 与 `ResponseRenderer.java` 已独立存在并投入使用。
   - 这项优化应改为“已部分完成，主循环仍可继续抽象”。

3. **“无输出美化 / 无 token 计数 / 无耗时统计”**
   - 当前 `ResponseRenderer.java` 已接入：
     - `MarkdownRenderer`
     - `ProgressIndicator`
     - `TokenStats`
   - 因此旧结论已失效。

4. **“仅有基础命令，缺少记忆/skill 能力”**
   - 当前命令系统已包含 `MemoryCommand`、`GenSkillCommand`、`SkillCommand`。
   - 能力比旧报告描述更完整。

5. **“项目约 47 个 Java 文件”**
   - 当前实际盘点结果为 **57 个 Java 文件**，旧统计已过期。

---

## 三、当前仍然成立的核心问题

### 1. 安全性

| 问题 | 位置 | 严重度 | 说明 |
|------|------|--------|------|
| API Key 明文存储 | `~/.codly/settings.json`、`Config.java` | 中 | 当前仍为本地明文文件，无系统 Keychain 集成 |
| Bash 执行无工作区沙箱 | `ExecBashFunctionCall.java` | 高 | 直接 `bash -c` 执行，未限制 cwd、未限制命令类别、未限制访问范围 |
| 工具调用只有深度上限，无成本治理 | `QwenLlmClient.java` | 中 | 虽有 `MAX_TOOL_CALL_DEPTH = 10`，但缺少 token/次数/敏感工具分级策略 |
| 文件写入非原子、无回滚 | `WriteFileFunctionCall.java`、`EditFileFunctionCall.java` | 中 | 覆盖写直接落盘，失败恢复与撤销能力缺失 |
| Web 下载服务无认证与 HTTPS | `web/server.js` | 中 | 裸 `http` 服务，且 CORS 为 `*` |

### 2. 代码质量

- **仍然没有测试体系**
  - 当前仓库不存在 `src/test`，也未发现 `*Test*.java`。
  - 核心风险点：`QwenLlmClient` 流式解析、文件工具、`MemoryManager`、`GenSkillCommand`。

- **魔法数字仍较多**
  - 例子：
    - `ReadFileFunctionCall.java`：`64KB`
    - `WriteFileFunctionCall.java`：`256KB`
    - `MemoryManager.java`：`FLUSH_THRESHOLD = 3`
    - `QwenLlmClient.java`：`MAX_TOOL_CALL_DEPTH = 10`
    - `ExecBashFunctionCall.java`：`30 秒超时`
    - `HttpClientUtil.java`：连接/写超时 `10 秒`

- **超长类/方法问题仍存在**
  - `GenSkillCommand.java` 约 **270 行**，请求构造、流式解析、文件落盘耦合在同一类。
  - `QwenLlmClient.java` 的 `doChat()` 仍承担流式解析、tool_calls 聚合、工具执行回环等多重职责。

- **文档与实现存在漂移**
  - `README.md` 的命令列表未覆盖 `/memory`、`/gen-skill`、`/skill`。
  - `README.md` 的工具列表只列了少数工具，未反映 `edit_file`、`grep`、`list_directory`、`web_search` 等。
  - `CommandDispatcher.helpText()` 提到了 **`/sysinfo`**，但当前并无对应命令实现，属于用户可见不一致。

### 3. 架构设计

- **LLM Provider 抽象存在，但工厂仍写死 Qwen**
  - `LlmProvider` 已定义通用接口。
  - 但 `LlmClient.create()` / `create(String)` 当前都直接返回 `QwenLlmClient`。
  - 这意味着“接口抽象已做、真正多实现尚未落地”。

- **Config 支持手动 reload，但不支持热更新**
  - `Config.load()` 已存在。
  - 但没有 `FileWatcher`、事件通知或运行中自动刷新机制。

- **MemoryManager 并发边界仍需收紧**
  - 同时使用了：
    - `synchronized`
    - `CompletableFuture.runAsync(...)`
    - 额外的 `Thread` 异步提取
  - `getInstance()` 也不是线程安全单例写法。
  - 当前实现可用，但并发模型不够清晰。

- **命令体系仍是 Picocli + 自定义接口混搭**
  - `CommandDispatcher` 用 `picocli` 解析。
  - 实际执行又依赖自定义 `CliCommand.execute(ctx)`。
  - 可维护性尚可，但设计上不够统一。

- **Function 扩展机制仍未插件化**
  - 当前 `FunctionManager` 通过构造函数硬编码注册 9 个工具。
  - 用户无法从外部目录动态扩展工具。

### 4. 用户体验

- **仍无 `/undo` 命令**
  - 文件工具涉及覆盖写，但没有回滚能力。

- **仍无历史会话浏览/恢复命令**
  - 虽然 `MemoryManager` 会持久化会话到 `~/.codly/memory/session/`，但 CLI 没有 `/history` 或 `/resume`。

- **长输出仍无分页浏览**
  - 目前有流式渲染和统计，但没有 pager/折叠/查看器模式。

- **配置错误提示已有基础能力，但体验还可继续增强**
  - `Config.printLoadErr()` 和 `printLlmConfigErr()` 已比旧版描述更友好。
  - 但仍缺少交互式初始化、配置校验向导、字段级修复提示。

### 5. 工程化

- **版本号仍为 `1.0-SNAPSHOT`**
  - `README.md` Banner 已写 `v1.0.0`，但 `pom.xml` 仍是 `1.0-SNAPSHOT`，版本表达不一致。

- **无 CI / 无自动化质量门禁**
  - 当前未见 GitHub Actions / CI 配置。

- **日志级别配置仍偏激进**
  - `logback.xml` 根级别为 `INFO`，但 `com.jiyingda.codly` 单独设为 `DEBUG`。
  - 对本地调试友好，但生产使用可能放大日志噪声。

- **Web 附属交付物仍是裸 Node.js 静态服务器**
  - 适合演示下载，但不适合直接作为生产发布面。

---

## 四、当前值得新增关注的问题

这些问题在旧报告中没有明确写出，但从现状看值得补充：

1. **帮助文本与实际命令不一致**
   - `/help` 展示了 `/sysinfo`，但 `CommandDispatcher` 的 subcommands 中没有 `SysInfoCommand`。

2. **命名不一致影响模型工具调用稳定性**
   - 大多数工具名是下划线风格，如 `read_file`、`write_file`。
   - 但 Bash 工具名是 **`Bash`**，风格不统一，可能增加 prompt/工具对齐成本。

3. **`GenSkillCommand` 重复了部分 LLM 调用逻辑**
   - 它没有复用 `LlmProvider` 的高层能力，而是自己构造请求并处理同步/流式响应。
   - 后续如果引入多模型，会形成额外维护成本。

4. **README 已落后于代码**
   - 用户按文档理解功能边界时，会低估当前能力，也可能误解实际命令集。

---

## 五、优化建议（按优先级重排）

### 高优先级

1. **建立测试体系（首要任务）**
   - 建议引入 `JUnit 5` + `Mockito`
   - 第一批覆盖目标：
     - `QwenLlmClient` 的流式 chunk 解析与 tool_calls 聚合
     - `ReadFileFunctionCall` / `WriteFileFunctionCall` / `EditFileFunctionCall`
     - `MemoryManager` 的刷盘与长期记忆提取流程

2. **加固 `Bash` 工具安全边界**
   - 至少补上：
     - 工作目录限制
     - 黑白名单或高危命令拦截
     - 超时/输出大小常量化
     - 可选只读模式

3. **补齐文件写入安全能力**
   - 原子写入（临时文件 + rename）
   - 写前备份
   - `/undo` 回滚命令

4. **修正文档与帮助漂移**
   - 更新 `README.md`
   - 修正 `/help` 中的 `/sysinfo` 条目，或补齐对应命令实现

5. **把魔法数字统一下沉到常量层**
   - 建议新增 `constants/RuntimeLimits.java` 或按模块拆分

### 中优先级

6. **真正落地多 LLM Provider**
   - 保留 `LlmProvider` 接口
   - 让 `LlmClient` 基于配置选择实现，而不是固定返回 `QwenLlmClient`

7. **重构 `GenSkillCommand`**
   - 拆成：
     - prompt 生成
     - LLM 调用
     - skill 内容解析
     - 本地保存
   - 降低命令层耦合度

8. **梳理 `MemoryManager` 并发模型**
   - 明确：哪些路径同步、哪些路径异步、退出时如何保证一致性

9. **改进配置体验**
   - 增加 `codly --setup` 或首次启动引导
   - 支持配置热更新或显式 reload 命令

10. **统一工具命名与权限分级**
    - 例如统一为 snake_case
    - 对 `Bash`、写文件类工具采用更强确认策略

### 低优先级

11. **插件化工具机制**
    - 支持从 `~/.codly/functions/` 或 SPI 动态加载扩展工具

12. **会话历史与恢复能力**
    - 增加 `/history`、`/resume`

13. **输出分页与查看器模式**
    - 对长回答增加 pager 或专门浏览模式

14. **统一版本与发布策略**
    - `pom.xml`、Banner、下载页面版本号统一
    - 引入语义化版本发布流程

---

## 六、阶段性路线图

### 短期（1-2 个月）

- 建立测试骨架并覆盖高风险核心模块
- 修复 `README` / `/help` 与实现不一致问题
- 完成文件写入原子化与基础回滚
- 收紧 `Bash` 工具安全边界

### 中期（2-4 个月）

- 落地多模型 Provider 选择机制
- 重构 `GenSkillCommand` 与 `QwenLlmClient` 的职责边界
- 补齐会话历史浏览与恢复能力
- 优化配置加载与 reload 体验

### 长期（4-8 个月）

- 插件化 Function 生态
- 项目级记忆与上下文隔离
- 更完整的 Web 分发与安装体验
- 向 MCP / 外部工具生态进一步兼容

---

## 七、总体评价（更新版）

| 维度 | 评分 | 说明 |
|------|------|------|
| 架构设计 | ★★★★☆ | 已有清晰分层，且完成了一轮入口拆分 |
| 功能完整性 | ★★★★☆ | CLI、工具、记忆、skill 已覆盖较多核心场景 |
| 代码质量 | ★★★☆☆ | 可读性尚可，但缺测试、魔法数字多、局部类偏重 |
| 安全性 | ★★★☆☆ | 文件沙箱已有基础，Bash 与 Web 暴露面仍需重点治理 |
| 工程化 | ★★☆☆☆ | 仍缺测试、CI、版本发布规范 |

**当前最核心的短板依旧是：缺少测试体系 + 高风险工具安全边界不足。**

相较旧版报告，项目已经从“基础 CLI 原型”进一步演进为“具备记忆、skill、流式渲染增强能力的可用型终端 AI 助手”；但若要继续向稳定产品推进，下一阶段最值得投入的仍然是：**测试、边界、安全、文档一致性**。

---

## 八、本次核对所依据的关键文件

- `pom.xml`
- `README.md`
- `src/main/java/com/jiyingda/codly/CodlyMain.java`
- `src/main/java/com/jiyingda/codly/SessionManager.java`
- `src/main/java/com/jiyingda/codly/ResponseRenderer.java`
- `src/main/java/com/jiyingda/codly/command/CommandDispatcher.java`
- `src/main/java/com/jiyingda/codly/command/MemoryCommand.java`
- `src/main/java/com/jiyingda/codly/command/GenSkillCommand.java`
- `src/main/java/com/jiyingda/codly/function/FunctionManager.java`
- `src/main/java/com/jiyingda/codly/function/ExecBashFunctionCall.java`
- `src/main/java/com/jiyingda/codly/function/ReadFileFunctionCall.java`
- `src/main/java/com/jiyingda/codly/function/WriteFileFunctionCall.java`
- `src/main/java/com/jiyingda/codly/function/EditFileFunctionCall.java`
- `src/main/java/com/jiyingda/codly/llm/LlmClient.java`
- `src/main/java/com/jiyingda/codly/llm/LlmProvider.java`
- `src/main/java/com/jiyingda/codly/llm/QwenLlmClient.java`
- `src/main/java/com/jiyingda/codly/memory/MemoryManager.java`
- `src/main/java/com/jiyingda/codly/skill/SkillRegistry.java`
- `src/main/resources/logback.xml`
- `web/server.js`
