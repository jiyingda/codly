# Codly

Codly 是一个终端里的 AI 编程助手（CLI），支持对话式编程和工具调用。

输入 `/` 会逐字符显示匹配的命令与 `/skill-<name>` 候选。直接按 ↑↓ 进入候选菜单并选择，Enter 确认补全，再按 Enter 执行；也支持 Tab 补全。普通对话中 ↑↓ 仍用于浏览历史记录。

🌐 官网：[http://codly.jiyingda.com](http://codly.jiyingda.com)

## Banner

```
   ██████╗ ██████╗ ██████╗ ██╗  ██╗   ██╗
  ██╔════╝██╔═══██╗██╔══██╗██║  ╚██╗ ██╔╝
  ██║     ██║   ██║██║  ██║██║   ╚████╔╝ 
  ██║     ██║   ██║██║  ██║██║    ╚██╔╝  
  ╚██████╗╚██████╔╝██████╔╝███████╗██║   
   ╚═════╝ ╚═════╝ ╚═════╝ ╚══════╝╚═╝   
         /\_____/\
         ( o . o )   Your AI Coding Companion
          > ^ ^ <
         /|     |\
  ────────────────────────────────v1.0.0──
```

## 快速开始

### 1. 环境要求
- Java 17+
- Maven 3.6+

### 2. 配置 API Key

创建配置文件 `~/.codly/settings.json`：

```bash
mkdir -p ~/.codly
cat > ~/.codly/settings.json << 'EOF'
{
  "provider": "qwen",
  "apiKey": "your-api-key-here",
  "apiUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
  "enableThinking": true,
  "defaultModel": "qwen3.5-plus",
  "availableModels": ["qwen3.5-plus", "qwen3-coder-plus"],
  "deepseek": {
    "apiKey": "sk-your-deepseek-key",
    "model": "deepseek-flash",
    "reasoningEffort": "high"
  }
}
EOF
```

配置说明：

| 字段 | 必填 | 说明 |
|------|------|------|
| `provider` | 否 | 当前使用的 provider：`qwen`（默认）或 `deepseek` |
| `apiKey` | provider=qwen 时必填 | 通义千问 API Key |
| `apiUrl` | 否 | 千问 API 端点地址 |
| `defaultModel` | provider=qwen 时必填 | 千问默认模型 |
| `availableModels` | 否 | 千问可选模型列表，供 `/model` 切换 |
| `enableThinking` | 否 | 是否启用深度思考，对两个 provider 都生效（默认 false） |
| `deepseek.apiKey` | provider=deepseek 时必填 | DeepSeek API Key |
| `deepseek.apiUrl` | 否 | 默认 `https://api.deepseek.com/chat/completions` |
| `deepseek.model` | 否 | 默认 `deepseek-flash` |
| `deepseek.availableModels` | 否 | 默认 `["deepseek-flash", "deepseek-v4-pro"]` |
| `deepseek.reasoningEffort` | 否 | 思考强度：`none` / `low` / `high` / `max`，启用思考时生效（默认 `high`） |

### 切换到 DeepSeek

把 `provider` 改为 `deepseek`，并填好 `deepseek.apiKey`，重启即可。`/model` 只在当前 provider 的模型列表内切换。
启用思考时（`enableThinking: true`）思考过程会以灰色输出，且不会写入对话记忆。

### 3. 构建
```bash
mvn clean package
```

### 4. 运行
```bash
java -jar target/codly-1.0-SNAPSHOT.jar
```

## Swing 桌面版（第一版）

沿用 Java 17，界面使用 FlatLaf 3.8（macOS 默认 Mac Light，其他系统默认 Light），
FlatLaf 控件主题配置位于 `src/main/resources/com/jiyingda/codly/desktop/FlatLaf.properties`。
macOS 桌面版建议使用 Java 17.0.21
或更新的修订版：本机 Java 17.0.18 在中英文混排时会漏画字符，同一份 JAR 在
17.0.21 上已验证显示正常。启动脚本按 `CODLY_JAVA_HOME`、`~/.codly/runtime`、
`JAVA_HOME`、PATH 的顺序选择 Java；独立运行时无需修改系统 Java。
本机 17.0.18 还出现过 JVM 原生线程被 macOS 以 `GUARD / SIGKILL` 终止的情况。
构建后运行：

```bash
java -jar target/codly-1.0-SNAPSHOT.jar --desktop
# 可选：指定项目目录
java -jar target/codly-1.0-SNAPSHOT.jar --desktop /path/to/project
```

也可以在 IDE 中直接运行 `com.jiyingda.codly.desktop.DesktopMain`。
不加 `--desktop` 时仍启动 CLI。

- 启动时默认使用命令运行所在目录，不弹出目录选择；可通过参数指定目录。文件工具、Bash 与项目信息均使用当前对话的目录。
- 支持多行输入、实时执行状态、模型切换，以及写文件和 Bash 操作确认。
- 点击侧栏“设置”，管理模型、Skills 和 MCP 配置信息；缺少配置时也能直接进入设置。
- 输入区右侧可切换模型和思考深度：不思考、浅度思考、深度思考、极深思考。选择作用于当前窗口，切换模型或新对话时保留；执行期间锁定，保存设置后重新读取配置默认值。
- `Enter` 发送，`Shift+Enter` 换行；同时保留 `⌘/Ctrl+Enter` 发送。中文输入法组合输入期间不会触发发送。
- “停止”取消当前模型请求或 Bash 进程；已完成的文件修改不会回滚。
- 关闭窗口时保存会话并执行原有长期记忆提取；配置继续使用 `~/.codly/settings.json`。

桌面采用项目侧栏、消息卡片和固定输入区，并提供“对话”和“轨迹”两个标签页。对话页仅展示用户消息与最终回复，执行期间显示“正在思考”“正在调用 Bash”等临时状态，中间说明、思考正文和命令结果统一放入轨迹。助手回答支持基础 Markdown
（标题、列表、粗体、行内代码、围栏代码块）和正文复制。向上阅读时保留位置，可点击“回到最新消息”。
对话区仅纵向滚动；长链接、路径和代码自动软换行，复制仍保留原文本。短消息气泡按内容收窄，正文在宽屏下限制行宽并居中，缩小窗口时自动适配。
正文使用 16 号字、代码 14 号字，标题分级展示；消息与输入区共用最大 860 像素的内容列。侧栏区分工作区、当前对话和历史记录，辅助信息采用较小字号。

思考深度写入实际请求参数，按所选模型支持情况生效：

| 深度 | Qwen 思考预算上限 | DeepSeek 思考强度 |
|------|------------------|------------------|
| 不思考 | `enable_thinking=false`，不发送预算 | `thinking.type=disabled`，不发送强度 |
| 浅度思考 | 1024 tokens | `low` |
| 深度思考 | 4000 tokens | `high` |
| 极深思考 | 16384 tokens | `max` |

Qwen 开启思考时使用 `thinking_budget`，DeepSeek 使用 `reasoning_effort`。自动命名和长期记忆提取等辅助请求保持关闭思考；轨迹可查看当前深度及实际请求体。协议参考：[Qwen](https://www.alibabacloud.com/help/en/model-studio/deep-thinking)、[DeepSeek](https://api-docs.deepseek.com/guides/thinking_mode/)。

轨迹按实际发生时间排列，展示时间、状态和耗时；各节点可展开查看完整内容：

- 蓝色：模型请求，包含可阅读的系统提示词、历史消息、工具定义和完整 JSON 请求体。
- 紫色：实时思考过程。
- 橙色：工具调用，包含命令/参数及返回结果；批准节点展示自动授权、人工批准或拒绝。
- 绿色：模型响应和已批准状态；红色：失败或拒绝，停止操作单独标记。

自动命名和后台记忆提取的模型请求也会记录。轨迹不包含鉴权请求头，随对话保存在本地历史中；新对话显示独立轨迹，旧对话迟到的后台事件不会混入新对话。

- 点击“新对话”或按 `⌘/Ctrl+N` 保存当前对话并开始新任务。
- 发送首条消息后，后台使用当前模型为对话自动命名；只调用一次，生成后更新侧栏、顶部和窗口标题。等待或失败时使用首条消息的简短名称，不影响正文回复；新对话会重新生成。
- 点击“新对话”右侧的 `▾` 选择其他目录并新建对话；取消选择会保留当前对话。切换后同步更新工作区、提示词和工具执行目录，清空上一段对话的临时授权。
- `⌘/Ctrl+,` 打开设置；生成期间按 `Esc` 停止。
- 侧栏可收起；底部“设置”为统一配置入口，模型、Skills、MCP 在设置窗口内分类管理。

输入框下方可选择执行批准策略，作用于当前窗口（包括新对话）。桌面版默认“帮我批准”，CLI 默认“请求批准”；生成期间不可切换：

| 策略 | 行为 |
|------|------|
| 请求批准 | 只读工具和可识别的只读命令自动执行；文件修改、构建测试及敏感操作请求确认 |
| 帮我批准（桌面默认） | 自动批准项目修改、项目脚本、文件复制移动、构建测试、依赖安装、Git 暂存/拉取和临时文件操作；删除、全局安装、发布推送、系统修改及未识别命令仍需确认 |
| 完全授权 | 自动批准所有原本需确认的操作，包括系统命令；现有文件访问限制和停止功能仍生效 |

执行权限分为三级：只读查询、项目操作、敏感或未识别操作。确认框展示当前操作的级别。
可识别的只读命令包括 `pwd`、`ls`、`cat`、`head`、`tail`、`wc`、`grep`、`rg`、无执行或写入选项的 `find`、`sed -n '1,80p'` 和 Git 查询；
项目操作包括 Maven（含 `-pl/-am/-D/-f` 等常用参数）、Gradle、`npm/pnpm/yarn` 的构建测试及项目依赖安装、Python 测试、项目内 Python/Bash/Node 文件脚本、`mkdir/touch/tee`、普通 `cp/mv` 及 Git `add/fetch/pull/switch`（强制操作除外）。
支持可识别命令之间的 `&&`、分号、换行和管道；允许项目内或临时目录的输入输出重定向、追加日志、`2>&1` 和 `/dev/null`。命令替换、内联脚本或其他未知 shell 语法仍请求确认。
临时文件指显式绝对路径下的 `/tmp` 或系统临时目录；普通文件工具仍遵循工作区限制。构建、测试、依赖安装和项目脚本会执行代码，“帮我批准”表示允许这些代码执行。
分级使用本地规则，不额外调用模型。CLI 默认使用“请求批准”策略。

桌面批准弹窗可勾选 **“本会话类似操作不再申请”**，再点击“允许执行”。弹窗展示实际记忆范围：

- 文件修改：同一目录的写入和编辑，不含子目录。
- 已识别的常规构建：同类工具的项目操作，依赖安装与前端构建分开记忆。
- Python / Bash / Node 文件脚本：同一解释器与脚本路径，允许参数变化。
- 敏感命令、复杂 shell 及其他操作：完整命令或完整操作参数匹配。

默认不勾选；拒绝、关闭或停止不会产生授权。授权仅存于内存，点击“新对话”或关闭窗口后失效，不写入全局设置。

目前为单窗口、单个活动会话，原生安装包尚未提供；
斜杠命令仍由 CLI 执行。更换项目可通过新对话右侧的目录选择入口完成。

### 历史对话

- 侧栏按最近访问时间列出历史对话；新建、切换目录和重启均保留已有记录，未发送消息的空白会话不单独保存。
- 点击历史记录可恢复名称、工作目录、消息上下文、最终回复和执行轨迹，并继续原来的对话；当前模型服务支持时恢复原模型与思考深度，否则沿用当前设置并提示。
- 启动仍默认当前目录；切换历史时同步恢复侧栏工作区、提示词和工具执行目录。原历史目录不可访问，或旧版记录未保存目录时，选择一次该对话的工作区后记住；取消选择保留当前对话。临时执行授权不会恢复。
- 每轮结束、切换及退出时保存，模型生成的名称和后台轨迹变化会自动补存。文件位于 `~/.codly/conversations/`，使用原子替换，支持的平台上权限为 `600`。
- 自动兼容 `~/.codly/memory/session/` 中旧版 JSON Lines 消息。原记录保留；继续旧记录时只追加新消息，不重复写入已有消息。
- 缺少模型配置时也能浏览历史，补齐设置后可继续聊天。

### 桌面设置

- **模型**：编辑 Qwen / DeepSeek 的 API Key、API URL、默认模型、可选模型与思考参数，
  以及联网搜索的 `iqsApiKey`。API Key 默认隐藏显示。保存后切换当前会话的模型客户端，保留聊天记录。
- **Skills**：始终扫描 `~/.codly/skills/`，可添加额外绝对路径（支持 `~/`）、刷新扫描、
  预览内容并勾选默认激活项。同名 Skill 优先使用默认目录中的定义。
  默认激活设置在当前桌面会话立即应用，CLI 和桌面下次启动也会读取。
- **MCP**：支持 HTTP / Streamable HTTP 的初始化、工具发现和调用，自定义 `headers`（含 Bearer 鉴权），兼容 JSON 与 SSE 响应、分页和会话头。MCP 页直接粘贴服务器名称映射（不需要外层 `mcpServers`），点击“测试连接”查看连接结果及工具数量，再保存应用。
  对话首次请求时按需连接，工具使用独立命名空间，保留完整参数 Schema。只读标注的工具自动执行，其他工具沿用当前批准策略；“完全授权”自动批准调用。调用参数与结果进入轨迹，鉴权请求头不进入模型上下文和轨迹。
  连接失败只影响对应服务器；修改设置后可重新连接。支持取消与超时，失败不会自动重放工具操作。当前只支持文本/结构化结果，图片和音频二进制会省略；stdio 和旧版 HTTP+SSE 传输配置可保留，但不会启动或连接。

新增配置示例（与原有模型字段一起放在 `~/.codly/settings.json` 中）：

```json
{
  "skills": {
    "directories": ["~/my-skills"],
    "enabled": ["my-skill"]
  },
  "mcpServers": {
    "local-example": {
      "command": "my-mcp-server",
      "args": [],
      "env": {},
      "disabled": true
    },
    "ares-mcp": {
      "url": "https://ares.zhenguanyu.com/tutor-wb-ai-gateway/api/mcp",
      "type": "http",
      "headers": {"Authorization": "Bearer YOUR_TOKEN"}
    }
  }
}
```

设置保存保留未识别字段，采用临时文件替换；若文件在打开设置后被外部修改，会拒绝覆盖并提示重新打开。


入口与核心的分层：

```text
CodlyMain（JLine + 斜杠命令） ──┐
                               ├── ChatSession → SessionManager / LlmProvider / 工具
DesktopMain（Swing） ──────────┘
```

`core/ConversationContext` 不持有终端；通过确认回调和每轮 `CancellationToken`
承接入口差异。`CommandContext` 仅为 CLI 增加 JLine 适配。

## 使用方式

启动后直接输入自然语言即可：
```text
> 帮我搜索当前目录下所有 .java 文件
> 读取 Example.java
> 把 README 追加一行“测试内容”
```

模型回复会流式输出。

## CLI 命令

```text
/help                   显示帮助信息
/clear                  清空对话历史
/compact                压缩对话历史，保留总结
/model                  列出并切换当前使用的模型
/memory                 查看长期记忆
/memory clear           清空所有长期记忆
/memory delete <key>    删除指定长期记忆条目
/gen-skill              根据最近的对话自动生成一个 skill
/skill                  交互式选择并激活 skill（↑↓ 选择，Enter 确认）
/skill-<name>           直接激活指定 skill
/kb                     打印 INDEX.md（无知识包时显示提示）
/kb show <name>         打印某知识包 KNOWLEDGE.md 全文
/kb section <name> <s>  打印某节（s = positioning|concepts|relations|flows|diff|pending|sources）
/kb search <query>      关键字检索全部知识包
/kb scaffold <name> "<system>"  生成空骨架知识包
/kb status <name> <s>   修改 frontmatter status（draft|reviewed|stale）
/kb delete <name>       删除整个知识包目录（带确认）
/kb reload              重新扫描 ~/.codly/knowledge/ 并重建索引
/sysinfo                显示当前系统与项目环境信息
/sysinfo refresh        强制重新采集系统信息
/quit                   退出程序
/exit                   退出程序
```

终端快捷键：
- `Ctrl+C` 退出程序
- `Ctrl+U` 清空当前输入行

## 内置工具能力

| 工具 | 说明 |
|------|------|
| `read_file` | 读取文件内容（限工作目录，最大 64KB） |
| `write_file` | 写入文件内容（支持覆盖和追加，最大 256KB） |
| `edit_file` | 对文件进行局部替换，避免覆盖整个文件 |
| `search_file` | 按 glob 模式在工作目录内搜索文件 |
| `Grep` | 在文件中搜索文本/正则内容 |
| `list_directory` | 列出目录内容 |
| `Bash` | 执行 Bash 命令（按权限分级和所选策略决定是否确认，超时 30 秒） |
| `system_info` | 读取系统和运行环境信息 |
| `web_search` | 通过通义搜索 API 搜索互联网内容 |
| `kb_list` | 列出 ~/.codly/knowledge/ 下全部已加载的知识包 |
| `kb_search` | 跨所有知识包做关键字检索（节级粒度，TF 打分） |
| `kb_read` | 读取指定知识包 KNOWLEDGE.md 全文（>32KB 拒绝） |
| `kb_section` | 读取指定知识包的某节（positioning/concepts/relations/flows/diff/pending/sources） |
| `kb_sources` | 读取指定知识包的 sources.json 原文（追溯 Confluence pageId） |

## 知识库（Knowledge Packs）

Codly 在 `~/.codly/knowledge/` 下加载与 [skill-kit/extract-system-knowledge](https://github.com/) 完全对齐的"系统知识包"：

```
~/.codly/knowledge/
├── INDEX.md                      # 由 Codly 自动维护的总索引
└── <name>-knowledge/
    ├── KNOWLEDGE.md              # frontmatter 7 字段 + 7 节固定正文
    └── sources.json              # schema_version=1 的原始资料索引
```

KNOWLEDGE.md frontmatter 字段白名单：`name / system / description / generated_at / generator / code_anchors[] / status`。
正文 7 节：系统定位 / 概念表 / 系统关系 / 核心流程 / 文档 vs 代码差异 / 未覆盖 / 出处索引。

启动时 Codly 会扫描全部知识包并构建关键字倒排索引，把"目录"段（pack 名 + system + description + status）注入 system prompt；模型按需调用 `kb_search` / `kb_section` / `kb_read` / `kb_sources` 工具检索具体内容。

知识包的产出路径推荐两种：

- 在 Cursor 中使用 `extract-system-knowledge` skill 生成后落到 `~/.codly/knowledge/`
- 直接 `/kb scaffold <name> "<system 描述>"` 生成空骨架后手工补充正文

## 安全边界与注意事项

- 文件读写/搜索限制在程序启动目录内。
- `exec_bash` 会执行系统命令，请谨慎使用。
- API Key 存储在 `~/.codly/settings.json` 中，请勿提交到代码仓库。
- 建议将 `~/.codly/settings.json` 添加到 `.gitignore`。

## 许可证

Copyright 2026 chapaof.com. All rights reserved.
