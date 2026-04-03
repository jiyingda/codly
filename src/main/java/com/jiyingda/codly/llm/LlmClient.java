package com.jiyingda.codly.llm;

import com.alibaba.fastjson.JSON;
import com.jiyingda.codly.data.ChatRequest;
import com.jiyingda.codly.data.FunctionCall;
import com.jiyingda.codly.data.Message;
import com.jiyingda.codly.data.StreamChoice;
import com.jiyingda.codly.data.StreamDelta;
import com.jiyingda.codly.data.StreamResponse;
import com.jiyingda.codly.data.ToolCall;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 通义千问大模型调用客户端，负责流式 HTTP 请求和 tool_calls 处理。
 */
public class LlmClient {

    private static final String API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_TOOL_CALL_DEPTH = 10;

    /** 通义千问可用模型列表 */
    public static final List<String> AVAILABLE_MODELS = List.of(
        "qwen3.5-plus",
        "qwen3-max-2026-01-23",
        "qwen3-coder-next",
        "qwen3-coder-plus",
        "glm-5",
        "glm-4.7",
        "kimi-k2.5",
        "MiniMax-M2.5"
    );

    private final String apiKey;
    private final OkHttpClient httpClient;
    private final FunctionManager functionManager;
    private String model;

    public LlmClient() {
        this("qwen3.5-plus");
    }

    public LlmClient(String model) {
        this.apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (this.apiKey == null || this.apiKey.isBlank()) {
            throw new IllegalStateException("环境变量 DASHSCOPE_API_KEY 未设置");
        }
        this.httpClient = new OkHttpClient();
        this.functionManager = new FunctionManager();
        this.model = model;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    /**
     * 发起对话，流式内容通过 onToken 回调实时输出。
     *
     * @param messages 对话历史
     * @param onToken  每个流式 token 的回调，传入 null 则静默
     * @return 完整的模型回复文本
     */
    public String chat(List<Message> messages, Consumer<String> onToken) {
        return doChat(messages, onToken, 0);
    }

    private String doChat(List<Message> messages, Consumer<String> onToken, int depth) {
        if (depth >= MAX_TOOL_CALL_DEPTH) {
            System.err.println("[警告] tool_call 递归深度已达上限 " + MAX_TOOL_CALL_DEPTH + "，停止继续调用");
            return "";
        }

        ChatRequest chatRequest = new ChatRequest();
        chatRequest.setModel(model);
        chatRequest.setStream(true);
        chatRequest.setTop_p(0.8);
        chatRequest.setTemperature(0.7);
        chatRequest.setEnable_search(false);
        chatRequest.setEnable_thinking(false);
        chatRequest.setThinking_budget(4000);
        chatRequest.setResult_format("message");
        chatRequest.setTools(functionManager.getTools());
        chatRequest.setMessages(messages);

        String jsonBody = JSON.toJSONString(chatRequest);

        Request request = new Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer " + apiKey)
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
            .build();

        StringBuilder fullContent = new StringBuilder();
        Map<Integer, StringBuilder> toolCallArgsBuffer = new HashMap<>();
        List<ToolCall> finalToolCalls = new ArrayList<>();

        try (Response response = httpClient.newCall(request).execute()) {
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
                    if (!line.startsWith("data: ")) {
                        continue;
                    }
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }

                    StreamResponse streamResponse = JSON.parseObject(data, StreamResponse.class);
                    if (streamResponse == null || streamResponse.getChoices() == null
                            || streamResponse.getChoices().length == 0) {
                        continue;
                    }
                    StreamChoice choice = streamResponse.getChoices()[0];
                    if (choice == null || choice.getDelta() == null) {
                        continue;
                    }
                    StreamDelta delta = choice.getDelta();

                    // 处理 tool_calls — 累积 arguments
                    if (delta.getTool_calls() != null && !delta.getTool_calls().isEmpty()) {
                        for (ToolCall tc : delta.getTool_calls()) {
                            int index = tc.getIndex() != null ? tc.getIndex() : 0;
                            if (tc.getFunction() == null) {
                                continue;
                            }
                            while (finalToolCalls.size() <= index) {
                                finalToolCalls.add(new ToolCall());
                            }
                            ToolCall existing = finalToolCalls.get(index);

                            if (tc.getId() != null && existing.getId() == null) {
                                existing.setId(tc.getId());
                            }
                            if (existing.getFunction() == null) {
                                existing.setFunction(new FunctionCall());
                            }
                            if (tc.getFunction().getName() != null && existing.getFunction().getName() == null) {
                                existing.getFunction().setName(tc.getFunction().getName());
                            }
                            if (tc.getFunction().getArguments() != null) {
                                toolCallArgsBuffer
                                    .computeIfAbsent(index, k -> new StringBuilder())
                                    .append(tc.getFunction().getArguments());
                            }
                        }
                    }

                    // 处理普通文本内容
                    String content = delta.getContent();
                    if (content != null && !content.isEmpty()) {
                        if (onToken != null) {
                            onToken.accept(content);
                        }
                        fullContent.append(content);
                    }
                }
            }

            // 将累积的 arguments 写回 finalToolCalls
            for (Map.Entry<Integer, StringBuilder> entry : toolCallArgsBuffer.entrySet()) {
                if (finalToolCalls.size() > entry.getKey()) {
                    ToolCall tc = finalToolCalls.get(entry.getKey());
                    if (tc.getFunction() != null) {
                        tc.getFunction().setArguments(entry.getValue().toString());
                    }
                }
            }

            // 执行所有 tool_calls，收集结果后统一发起下一轮对话
            if (!finalToolCalls.isEmpty()) {
                boolean anyExecuted = false;
                for (ToolCall toolCall : finalToolCalls) {
                    if (toolCall.getFunction() == null) {
                        continue;
                    }
                    String functionName = toolCall.getFunction().getName();
                    String args = toolCall.getFunction().getArguments();

                    if (functionManager.hasFunction(functionName)) {
                        if (onToken != null) {
                            onToken.accept("\n[调用 " + functionName + " 工具，参数：" + args + "]\n");
                        }
                        String result = functionManager.execute(functionName, args);
                        if (onToken != null) {
                            onToken.accept("[" + functionName + " 结果：" + result + "]\n");
                        }
                        messages.add(Message.fromTool(toolCall.getId(), result));
                        anyExecuted = true;
                    } else {
                        if (onToken != null) {
                            onToken.accept("[未知的函数：" + functionName + "]\n");
                        }
                    }
                }
                if (anyExecuted) {
                    return doChat(messages, onToken, depth + 1);
                }
            }

        } catch (IOException e) {
            System.err.println("请求失败：" + e.getMessage());
        }

        return fullContent.toString();
    }

    /**
     * 根据首轮对话内容异步生成一个简短标题（≤10字），完成后回调。
     *
     * @param userMessage 用户首条消息
     * @param onTitle     标题生成后的回调
     */
    public void generateTitleAsync(String userMessage, Consumer<String> onTitle) {
        Thread t = new Thread(() -> {

            List<Message> messages = List.of(
                Message.fromSystem("你是一个标题生成助手，根据用户的消息生成一个简短的对话标题。要求：不超过10个字，不加引号，不加标点，只输出标题本身。"),
                Message.fromUser(userMessage)
            );

            ChatRequest req = new ChatRequest();
            req.setModel("qwen-turbo");
            req.setStream(false);
            req.setTemperature(0.3);
            req.setEnable_thinking(false);
            req.setResult_format("message");
            req.setMessages(messages);

            String jsonBody = JSON.toJSONString(req);
            Request request = new Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return;
                String body = response.body().string();
                com.alibaba.fastjson.JSONObject json = JSON.parseObject(body);
                String title = json
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");
                if (title != null && !title.isBlank()) {
                    onTitle.accept(title.trim());
                }
            } catch (Exception ignored) {}
        });
        t.setDaemon(true);
        t.start();
    }
}
