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
/help      显示帮助
/clear     清空对话历史
/compact   压缩对话历史，保留总结
/model     选择模型
/quit      退出
/exit      退出
```

终端快捷键：
- `Ctrl+C` 退出程序
- `Ctrl+U` 取消当前输入并继续

## 内置工具能力

- `read_file`：读取文件内容
- `write_file`：写入文件内容（支持覆盖和追加）
- `search_file`：按模式搜索文件
- `exec_bash`：执行 Bash 命令

## 安全边界与注意事项

- 文件读写/搜索限制在程序启动目录内。
- `exec_bash` 会执行系统命令，请谨慎使用。
- API Key 存储在 `~/.codly/settings.json` 中，请勿提交到代码仓库。
- 建议将 `~/.codly/settings.json` 添加到 `.gitignore`。

## 许可证

Copyright 2026 chapaof.com. All rights reserved.
