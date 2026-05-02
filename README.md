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

## 安全边界与注意事项

- 文件读写/搜索限制在程序启动目录内。
- `exec_bash` 会执行系统命令，请谨慎使用。
- API Key 存储在 `~/.codly/settings.json` 中，请勿提交到代码仓库。
- 建议将 `~/.codly/settings.json` 添加到 `.gitignore`。

## 许可证

Copyright 2026 chapaof.com. All rights reserved.
