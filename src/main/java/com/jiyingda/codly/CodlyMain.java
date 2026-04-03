/**
 * @(#)CodeCli.java, 3 月 31, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly;

import com.alibaba.fastjson.JSON;
import com.jiyingda.codly.data.ChatRequest;
import com.jiyingda.codly.data.Message;
import com.jiyingda.codly.data.StreamChoice;
import com.jiyingda.codly.data.StreamDelta;
import com.jiyingda.codly.data.StreamResponse;
import com.jiyingda.codly.data.ToolCall;
import com.jiyingda.codly.function.FunctionManager;
import com.jiyingda.codly.prompt.SystemPrompt;
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
    private static final String API_KEY = System.getenv("DASHSCOPE_API_KEY");
    private static final String MODEL = "qwen3.5-plus";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private final FunctionManager functionManager;

    public CodlyMain() {
        this.functionManager = new FunctionManager();
    }

    public static void main(String[] args) {

        List<Message> memory = new ArrayList<>();
        memory.add(Message.fromSystem(SystemPrompt.SOUL_PROMPT));
        CodlyMain cli = new CodlyMain();
        System.out.println("欢迎使用 Codly，输入对话内容开始与模型对话（输入 /quit 退出）...");
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
        chatRequest.setTools(functionManager.getTools());

        chatRequest.setMessages(messages);

        String jsonBody = JSON.toJSONString(chatRequest);

        Request request = new Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer " + API_KEY)
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
            .build();

        System.out.print(">>: ");

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
                                                existing.setFunction(new com.jiyingda.codly.data.FunctionCall());
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