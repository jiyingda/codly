# Codly - AI编程助手CLI工具

基于阿里通义千问大模型的命令行编程助手，支持文件读取、Bash执行、文件搜索等功能。

## 🚀 快速开始

### 环境要求
- Java 17+
- Maven 3.6+
- 阿里云 DashScope API Key

### 构建
```bash
mvn clean package
```

### 运行
```bash
# 设置 API 密钥
export DASHSCOPE_API_KEY=your_api_key

# 运行应用
java -jar target/codly-1.0-SNAPSHOT.jar
```

### 使用
```
> 你好，帮我读取 /path/to/file.txt
AI: [正在读取文件...]

> 搜索所有的 Java 文件
AI: [正在搜索...]

> 执行 ls -la
AI: [执行结果...]

> quit  # 退出程序
```

## 📁 项目结构

```
codly/
├── src/main/java/com/jiyingda/codly/
│   ├── CodlyMain.java                 # 主应用
│   ├── data/                          # 数据模型层
│   │   ├── Message.java              # 消息
│   │   ├── ChatRequest.java          # 请求
│   │   ├── ToolCall.java             # 工具调用
│   │   └── ...
│   └── functionCallApi/                      # 函数执行层
│       ├── Function.java             # 核心接口
│       ├── FunctionManager.java      # 管理器
│       ├── ReadFileFunction.java     # 读文件
│       ├── SearchFileFunction.java   # 搜索文件
│       └── ExecBashFunction.java     # 执行命令
├── pom.xml                            # Maven配置
└── README.md                          # 本文件
```

## ✨ 核心功能

| 函数 | 功能 | 示例 |
|------|------|------|
| `read_file` | 读取文件内容 | 读取 /path/to/file.txt |
| `search_file` | 搜索文件 | 搜索 *.java 文件 |
| `exec_bash` | 执行 Bash 命令 | 执行 ls -la |

## 🏗️ 架构设计

### 分层结构
- **data**: POJO数据模型，与阿里云API对接
- **functionCallApi**: 函数执行层，支持可扩展的Function设计模式

### 设计模式
```
Function 接口 (标准契约)
    ↓
FunctionManager (集中管理)
    ↓
具体实现类 (ReadFileFunction, SearchFileFunction, ExecBashFunction)
```

## 🔧 快速扩展新功能

### 第一步：创建Function实现类

```java
package com.jiyingda.codly.functionCallApi;

public class MyFunction implements FunctionCall {
    @Override
    public String getName() {
        return "my_function";
    }

    @Override
    public String getDescription() {
        return "我的自定义函数";
    }

    @Override
    public String execute(String argsJson) {
        // 实现业务逻辑
        return "执行结果";
    }
}
```

### 第二步：在FunctionManager中注册
```java
public FunctionManager() {
    registerFunction(new ReadFileFunction());
    registerFunction(new SearchFileFunction());
    registerFunction(new ExecBashFunction());
    registerFunction(new MyFunction());  // 添加这一行
}
```

✅ 完成！无需修改其他代码。

## 📚 依赖

- **FastJSON**: JSON序列化/反序列化
- **OkHttp3**: HTTP客户端，用于调用阿里云API

## 💡 主要特性

- ✅ 流式输出AI响应
- ✅ 消息历史记忆
- ✅ 支持工具调用（Function Calling）
- ✅ 模块化架构，易于扩展
- ✅ 清晰的代码分层

## 📝 命令

```
/quit   或 /exit     # 退出程序
/clear               # 清空对话历史
```

## 🔐 注意事项

- API Key 需要通过环境变量传入，避免硬编码
- 文件操作有路径限制，建议设置安全沙盒
- 命令执行超时时间为 30 秒

## 📄 许可证

Copyright 2026 chapaof.com. All rights reserved.

## 👨‍💻 作者

jiyingda

---

更多详情见 [REFACTOR_SUMMARY.md](./REFACTOR_SUMMARY.md) 和 [functionCallApi/README_EXTENSION.md](./src/main/java/com/jiyingda/codly/functionCallApi/README_EXTENSION.md)
