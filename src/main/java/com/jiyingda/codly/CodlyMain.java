/**
 * @(#)CodeCli.java, 3 月 31, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly;

import com.alibaba.fastjson.JSON;
import com.jiyingda.codly.data.ChatRequest;
import com.jiyingda.codly.data.FunctionCall;
import com.jiyingda.codly.data.Message;
import com.jiyingda.codly.data.Parameters;
import com.jiyingda.codly.data.Property;
import com.jiyingda.codly.data.StreamChoice;
import com.jiyingda.codly.data.StreamDelta;
import com.jiyingda.codly.data.StreamResponse;
import com.jiyingda.codly.data.Tool;
import com.jiyingda.codly.data.ToolCall;
import com.jiyingda.codly.function.Function;
import com.jiyingda.codly.function.FunctionManager;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于阿里通义千问大模型的命令行对话工具
 * <p>
 * 功能：从控制台读取用户输入，流式输出大模型响应
 * <p>
 * 使用方式：
 * 1. 确保 pom.xml 中已添加 OkHttp 和 FastJSON 依赖
 * 2. 运行 main 方法
 * 3. 输入对话内容，按回车发送
 * 4. 输入 quit 退出程序
 * <p>
 * 使用方法：
 * <pre>{@code
 *   # 设置 API 密钥
 *   export DASHSCOPE_API_KEY=your_api_key
 * }</pre>
 *
 * @author jiyingda
 */
public class CodlyMain {

    private static final String API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static String API_KEY;
    private static final String MODEL = "qwen3.5-plus";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private final FunctionManager functionManager;

    public CodlyMain() {
        this.functionManager = new FunctionManager();
    }

    public static void main(String[] args) {
        API_KEY = args[0];

        List<Message> memory = new ArrayList<>();
        memory.add(Message.fromSystem("你是一个编程 agent，处于 cli 模式下，你的任务是辅助用户完成编程等工作。回答简洁、友好、正式。"));
        CodlyMain cli = new CodlyMain();
        System.out.println("欢迎使用 CodeCli，输入对话内容开始与通义千模型对话（输入 quit 退出）...");
        System.out.print("> ");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if ("/quit".equalsIgnoreCase(line.trim()) || "/exit".equalsIgnoreCase(line.trim())) {
                    break;
                }
                if ("/clear".equalsIgnoreCase(line.trim())) {
                    memory.clear();
                    System.out.println("对话内容已清空");
                    System.out.print("> ");
                    continue;
                }
                if (line.trim().isEmpty()) {
                    continue;
                }
                memory.add(Message.fromUser(line));
                String res = cli.chat(memory);
                memory.add(Message.formAssistant(res));
                System.out.println();
                System.out.print("> ");
            }
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    public String chat(List<Message> messages) {
        OkHttpClient client = new OkHttpClient();

        ChatRequest chatRequest = new ChatRequest();
        chatRequest.setModel(MODEL);
        chatRequest.setStream(true);
        chatRequest.setTop_p(0.8);
        chatRequest.setTemperature(0.7);
        chatRequest.setEnable_search(false);
        chatRequest.setEnable_thinking(false);
        chatRequest.setThinking_budget(4000);
        chatRequest.setResult_format("message");

        // 从 FunctionManager 获取所有已注册的 functions 并转换为 tools
        List<Tool> tools = new ArrayList<>();

        // 添加 read_file 工具
        Parameters readFileParams = new Parameters();
        readFileParams.setType("object");
        Map<String, Property> readFileProps = new HashMap<>();
        Property readFileProp = new Property();
        readFileProp.setType("string");
        readFileProp.setDescription("文件完整路径");
        readFileProps.put("filePath", readFileProp);
        readFileParams.setProperties(readFileProps);
        readFileParams.setRequired(Collections.singletonList("filePath"));
        Function readFileFunc = functionManager.getFunction("read_file");
        if (readFileFunc != null) {
            tools.add(Tool.createFunction(readFileFunc.getName(), readFileFunc.getDescription(), readFileParams));
        }

        // 添加 exec_bash 工具
        Parameters execBashParams = new Parameters();
        execBashParams.setType("object");
        Map<String, Property> execBashProps = new HashMap<>();
        Property execBashProp = new Property();
        execBashProp.setType("string");
        execBashProp.setDescription("要执行的 bash 命令");
        execBashProps.put("command", execBashProp);
        execBashParams.setProperties(execBashProps);
        execBashParams.setRequired(Collections.singletonList("command"));
        Function execBashFunc = functionManager.getFunction("exec_bash");
        if (execBashFunc != null) {
            tools.add(Tool.createFunction(execBashFunc.getName(), execBashFunc.getDescription(), execBashParams));
        }

        // 添加 search_file 工具
        Parameters searchFileParams = new Parameters();
        searchFileParams.setType("object");
        Map<String, Property> searchFileProps = new HashMap<>();
        Property patternProp = new Property();
        patternProp.setType("string");
        patternProp.setDescription("搜索模式，如 *.java 或文件名");
        searchFileProps.put("pattern", patternProp);
        Property directoryProp = new Property();
        directoryProp.setType("string");
        directoryProp.setDescription("搜索目录，默认为当前目录");
        searchFileProps.put("directory", directoryProp);
        searchFileParams.setProperties(searchFileProps);
        searchFileParams.setRequired(Collections.singletonList("pattern"));
        Function searchFileFunc = functionManager.getFunction("search_file");
        if (searchFileFunc != null) {
            tools.add(Tool.createFunction(searchFileFunc.getName(), searchFileFunc.getDescription(), searchFileParams));
        }

        chatRequest.setTools(tools);


        chatRequest.setMessages(messages);

        String jsonBody = JSON.toJSONString(chatRequest);

        Request request = new Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer " + API_KEY)
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
            .build();

        System.out.print("AI: ");

        // 流式输出时累积内容
        StringBuilder fullContent = new StringBuilder();
        // 用于累积 tool_calls 的 arguments
        Map<Integer, StringBuilder> toolCallArgsBuffer = new HashMap<>();
        List<ToolCall> finalToolCalls = new ArrayList<>();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                System.err.println("请求失败：" + response.code() + " - " + response.message());
                return "";
            }

            ResponseBody body = response.body();
            if (body == null) {
                return "";
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(body.byteStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6);
                        if ("[DONE]".equals(data.trim())) {
                            break;
                        }

                        StreamResponse streamResponse = JSON.parseObject(data, StreamResponse.class);
                        if (streamResponse != null && streamResponse.getChoices() != null
                                && streamResponse.getChoices().length > 0) {
                            StreamChoice choice = streamResponse.getChoices()[0];
                            if (choice != null && choice.getDelta() != null) {
                                StreamDelta delta = choice.getDelta();

                                // 处理 tool_calls - 累积 arguments
                                if (delta.getTool_calls() != null && !delta.getTool_calls().isEmpty()) {
                                    for (ToolCall tc : delta.getTool_calls()) {
                                        int index = tc.getIndex() != null ? tc.getIndex() : 0;
                                        if (tc.getFunction() != null) {
                                            // 找到或创建对应的 ToolCall
                                            while (finalToolCalls.size() <= index) {
                                                finalToolCalls.add(new ToolCall());
                                            }
                                            ToolCall existing = finalToolCalls.get(index);

                                            // 累积 id（只在第一个 chunk 有）
                                            if (tc.getId() != null && existing.getId() == null) {
                                                existing.setId(tc.getId());
                                            }

                                            if (existing.getFunction() == null) {
                                                existing.setFunction(new FunctionCall());
                                            }

                                            // 累积 name（只在第一个 chunk 有）
                                            if (tc.getFunction().getName() != null && existing.getFunction().getName() == null) {
                                                existing.getFunction().setName(tc.getFunction().getName());
                                            }

                                            // 累积 arguments
                                            if (tc.getFunction().getArguments() != null) {
                                                toolCallArgsBuffer
                                                    .computeIfAbsent(index, k -> new StringBuilder())
                                                    .append(tc.getFunction().getArguments());
                                            }
                                        }
                                    }
                                }

                                // 处理普通文本内容
                                String content = delta.getContent();
                                if (content != null && !content.isEmpty()) {
                                    System.out.print(content);
                                    fullContent.append(content);
                                }
                            }
                        }
                    }
                }
            }

            // 将累积的 arguments 设置到 finalToolCalls
            for (Map.Entry<Integer, StringBuilder> entry : toolCallArgsBuffer.entrySet()) {
                if (entry.getValue() != null && finalToolCalls.size() > entry.getKey()) {
                    ToolCall tc = finalToolCalls.get(entry.getKey());
                    if (tc.getFunction() != null) {
                        tc.getFunction().setArguments(entry.getValue().toString());
                    }
                }
            }

            // 流式输出完成后，如果有 tool_calls，则执行
            if (!finalToolCalls.isEmpty()) {
                System.out.println();
                for (ToolCall toolCall : finalToolCalls) {
                    if (toolCall.getFunction() != null) {
                        String functionName = toolCall.getFunction().getName();
                        String args = toolCall.getFunction().getArguments();

                        // 通过 FunctionManager 执行函数
                        if (functionManager.hasFunction(functionName)) {
                            System.out.println("[调用 " + functionName + " 工具，参数：" + args + "]");
                            String result = functionManager.execute(functionName, args);
                            System.out.println("[" + functionName + " 结果：" + result + "]");

                            // 将工具调用结果添加到消息历史
                            Message toolMessage = Message.fromTool(toolCall.getId(), result);
                            messages.add(toolMessage);

                            // 递归调用继续对话
                            return chat(messages);
                        } else {
                            System.out.println("[未知的函数：" + functionName + "]");
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("请求失败：" + e.getMessage());
        }

        return fullContent.toString();
    }
}