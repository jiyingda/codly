# Codly

Codly 是一个终端里的 AI 编程助手（CLI），支持对话式编程和工具调用。

## 快速开始

### 1. 环境要求
- Java 17+
- Maven 3.6+
- DashScope API Key

### 2. 配置 API Key
```bash
export DASHSCOPE_API_KEY=your_api_key
```

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
> 读取 src/main/java/com/jiyingda/codly/CodlyMain.java
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
- API Key 通过环境变量传入，避免写入代码仓库。

## 常见问题

### 启动时报 `环境变量 DASHSCOPE_API_KEY 未设置`
先执行：
```bash
export DASHSCOPE_API_KEY=your_api_key
```

## 许可证

Copyright 2026 chapaof.com. All rights reserved.
