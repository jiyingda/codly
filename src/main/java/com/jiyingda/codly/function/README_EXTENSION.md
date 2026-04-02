/**
 * @(#)README.md, 4 月 2, 2026.
 * <p>
 * Function 管理系统说明文档
 * 
 * ## 架构设计
 * 
 * ### 1. 核心接口：Function
 * - 定义所有 tool functionCallApi 需要实现的标准
 * - 包含三个方法：
 *   - getName(): 获取函数名称
 *   - getDescription(): 获取函数描述
 *   - execute(String argsJson): 执行函数逻辑
 * 
 * ### 2. 实现类（Function Implementations）
 * - ReadFileFunction: 读取文件
 * - SearchFileFunction: 搜索文件
 * - ExecBashFunction: 执行 Bash 命令
 * 
 * ### 3. 管理器：FunctionManager
 * - 负责注册和管理所有 Function 实现
 * - 提供便捷的函数查询和执行方法
 * - 支持动态注册新的 Function
 * 
 * ### 4. 使用方式（CodlyMain）
 * - 在构造函数中初始化 FunctionManager
 * - 通过 FunctionManager 获取已注册的 Functions
 * - 在执行 tool calls 时，通过 FunctionManager.execute() 执行函数
 * 
 * ## 如何扩展新的 Function
 * 
 * ### 第一步：创建新的 Function 实现类
 * 
 * ```java
 * package com.jiyingda.codly.functionCallApi;
 * 
 * public class MyCustomFunction implements Function {
 * 
 *     @Override
 *     public String getName() {
 *         return "my_function";  // 唯一的函数名称
 *     }
 * 
 *     @Override
 *     public String getDescription() {
 *         return "我的自定义函数描述";
 *     }
 * 
 *     @Override
 *     public String execute(String argsJson) {
 *         // 1. 解析 argsJson
 *         Map<String, Object> args = JSON.parseObject(argsJson, Map.class);
 *         
 *         // 2. 提取参数
 *         String param1 = (String) args.get("param1");
 *         
 *         // 3. 实现业务逻辑
 *         // ...
 *         
 *         // 4. 返回结果
 *         return "执行结果";
 *     }
 * }
 * ```
 * 
 * ### 第二步：在 FunctionManager 中注册
 * 
 * 在 FunctionManager 的构造函数中添加：
 * 
 * ```java
 * public FunctionManager() {
 *     registerFunction(new ReadFileFunction());
 *     registerFunction(new SearchFileFunction());
 *     registerFunction(new ExecBashFunction());
 *     registerFunction(new MyCustomFunction());  // 添加这一行
 * }
 * ```
 * 
 * ### 第三步：在 CodlyMain 中配置参数（可选）
 * 
 * 如果需要在 AI 调用函数时提供参数定义，在 chat() 方法中添加相应的 Parameters 配置。
 * 
 * ## 优势
 * 
 * 1. **解耦合**: Function 实现与管理分离，易于维护
 * 2. **可扩展性**: 添加新 Function 只需创建新的实现类并注册
 * 3. **灵活性**: 支持动态注册和卸载 Function
 * 4. **可测试性**: 每个 Function 可独立单元测试
 * 5. **清晰的接口**: 统一的 Function 接口使得代码更易理解
 * 
 * ## 文件结构
 * 
 * ```
 * com.jiyingda.codly.functionCallApi/
 * ├── Function.java                 # 核心接口
 * ├── ReadFileFunction.java         # 具体实现 1
 * ├── SearchFileFunction.java       # 具体实现 2
 * ├── ExecBashFunction.java         # 具体实现 3
 * └── FunctionManager.java          # 管理器
 * ```
 */

