# Codly

Codly 是一个终端里的 AI 编程助手（CLI），支持对话式编程和工具调用。

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
  "apiKey": "your-api-key-here",
  "apiUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
  "enableThinking": true,
  "defaultModel": "qwen3.5-plus"
}
EOF
```

配置说明：
- `apiKey` **必需** - 通义千问 API Key
- `apiUrl` 可选 - API 端点地址（默认通义千问）
- `enableThinking` 可选 - 是否启用深度思考（默认 true）
- `defaultModel` **必需** - 默认使用的模型

### 3. 构建
```bash
mvn clean package
```

### 4. 运行
```bash
java -jar target/codly-1.0-SNAPSHOT.jar
```

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
| `Bash` | 执行 Bash 命令（执行前需用户确认，超时 30 秒） |
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
