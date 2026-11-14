package com.jiyingda.codly.llm;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.jiyingda.codly.core.ConversationContext;
import com.jiyingda.codly.core.CancellationToken;
import com.jiyingda.codly.core.ToolEvent;
import com.jiyingda.codly.core.TraceEvent;
import com.jiyingda.codly.config.Config;
import com.jiyingda.codly.config.ConfigException;
import com.jiyingda.codly.data.FunctionCall;
import com.jiyingda.codly.data.Message;
import com.jiyingda.codly.data.StreamChoice;
import com.jiyingda.codly.data.StreamDelta;
import com.jiyingda.codly.data.StreamResponse;
import com.jiyingda.codly.data.ToolCall;
import com.jiyingda.codly.function.FunctionManager;
import com.jiyingda.codly.prompt.SystemPrompt;
import com.jiyingda.codly.util.HttpClientUtil;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenAI 兼容协议的 LLM 客户端基类。
 * <p>
 * 负责流式 SSE 解析、reasoning_content 输出、tool_calls 聚合与工具执行回环；
 * 各 provider 子类只需提供请求体构造与可用模型列表。
 */
public abstract class AbstractLlmClient implements LlmProvider {

    /** 流式回调中携带 usage 的哨兵前缀，格式：\0USAGE:prompt:completion:total */
    public static final String USAGE_PREFIX = "\u0000USAGE:";
    /** 流式回调中标记思考过程开始的哨兵 */
    public static final String REASONING_START = "\u0000REASONING:START";
    /** 流式回调中标记思考过程结束的哨兵 */
    public static final String REASONING_END = "\u0000REASONING:END";

    protected static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    protected static final int MAX_TOOL_CALL_DEPTH = 10;

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected final String apiUrl;
    protected final String apiKey;
    protected final OkHttpClient httpClient;
    protected final FunctionManager functionManager;
    @Override public void close() { functionManager.close(); }
    protected volatile ThinkingDepth thinkingDepth;
    protected String model;

    protected AbstractLlmClient(String apiUrl, String apiKey, String model, boolean enableThinking) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClientUtil.createOptimizedHttpClient();
        this.functionManager = new FunctionManager();
        this.model = model;
        this.thinkingDepth = enableThinking ? ThinkingDepth.HIGH : ThinkingDepth.OFF;
    }

    /**
     * 校验配置文件已加载，未加载则抛出 {@link ConfigException}
     */
    protected static void requireConfigLoaded() {
        Config config = Config.getInstance();
        if (config.isNotConfigLoaded()) {
            throw new ConfigException("配置文件加载失败：" + config.getLoadError());
        }
    }

    @Override
    public String getModel() {
        return model;
    }

    @Override
    public void setModel(String model) {
        this.model = model;
    }

    @Override public ThinkingDepth getThinkingDepth() { return thinkingDepth; }
    @Override public void setThinkingDepth(ThinkingDepth depth) { thinkingDepth = java.util.Objects.requireNonNull(depth); }
    @Override public boolean supportsThinkingDepth() { return true; }

    @Override
    public String chat(ConversationContext ctx, Consumer<String> onToken) {
        List<Message> messages = new ArrayList<>();
        messages.add(ctx.getSystemPrompt());
        messages.addAll(ctx.getMemory());
        return doChat(ctx, messages, onToken);
    }

    @Override
    public String chat(ConversationContext ctx, List<Message> messages, Consumer<String> onToken) {
        return doChat(ctx, messages, onToken);
    }

    @Override
    public String complete(List<Message> messages) {
        return complete(messages, null, "辅助模型请求");
    }

    @Override
    public String complete(List<Message> messages, Consumer<TraceEvent> trace, String purpose) {
        String jsonBody = buildSimpleRequestBody(messages);
        String id = TraceEvent.newId();
        trace(trace, TraceEvent.of(id, TraceEvent.Kind.MODEL_REQUEST, TraceEvent.Phase.START, purpose + " · " + model, requestDetails(messages, jsonBody)));
        logger.info("同步补全请求入参: model={}, apiUrl={}, jsonBody={}", model, apiUrl, jsonBody);

        try (Response response = httpClient.newCall(newRequest(jsonBody)).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                String error = safeErrorBody(response);
                trace(trace, TraceEvent.of(id, TraceEvent.Kind.MODEL_REQUEST, TraceEvent.Phase.FAILED, purpose + "失败", "\nHTTP " + response.code() + "\n" + error));
                logger.error("同步补全请求失败：{} - {} - {}", response.code(), response.message(), error);
                return null;
            }
            String body = response.body().string();
            trace(trace, TraceEvent.of(id, TraceEvent.Kind.MODEL_REQUEST, TraceEvent.Phase.COMPLETE, purpose + " · " + model, "\nHTTP " + response.code()));
            trace(trace, TraceEvent.of(TraceEvent.newId(), TraceEvent.Kind.MODEL_RESPONSE, TraceEvent.Phase.COMPLETE, purpose + "响应", pretty(body)));
            logger.info("同步补全请求返回: body={}", body);
            return extractMessageContent(body);
        } catch (IOException | RuntimeException e) {
            trace(trace, TraceEvent.of(id, TraceEvent.Kind.MODEL_REQUEST, TraceEvent.Phase.FAILED, purpose + "失败", "\n" + e.getMessage()));
            logger.error("同步补全请求异常：{}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public void generateTitleAsync(String userMessage, Consumer<String> onTitle) {
        generateTitleAsync(userMessage, onTitle, null);
    }

    @Override
    public void generateTitleAsync(String userMessage, Consumer<String> onTitle, Consumer<TraceEvent> trace) {
        Thread t = new Thread(() -> {
            try {
                logger.debug("开始生成标题");

                int end = userMessage.offsetByCodePoints(0, Math.min(4000, userMessage.codePointCount(0, userMessage.length())));
                List<Message> messages = List.of(
                    Message.fromSystem(SystemPrompt.GEN_TITLE_PROMPT),
                    Message.fromUser(userMessage.substring(0, end))
                );

                String title = complete(messages, trace, "自动命名");
                if (title != null && !title.isBlank()) {
                    logger.debug("生成标题：{}", title);
                    onTitle.accept(title.trim());
                }
            } catch (RuntimeException e) {
                logger.warn("生成对话标题失败，保留临时名称：{}", e.getClass().getSimpleName());
            }
        }, "Codly-Conversation-Title");
        t.setDaemon(true);
        t.start();
    }

    private static void trace(Consumer<TraceEvent> listener, TraceEvent event) { if (listener != null) listener.accept(event); }
    private static String pretty(String json) {
        try { return JSON.toJSONString(JSON.parse(json), true); }
        catch (RuntimeException e) { return json; }
    }

    private static String requestDetails(List<Message> messages, String json) {
        StringBuilder details = new StringBuilder();
        for (Message message : messages) {
            if ("system".equals(message.getRole()) || "developer".equals(message.getRole())) {
                details.append("提示词（").append(message.getRole()).append("）\n").append(message.getContent()).append("\n\n");
            }
        }
        return details.append("完整请求体（JSON）\n").append(pretty(json)).toString();
    }

    /**
     * 构造主对话请求体：流式 + 携带工具 + 按配置决定是否开启思考。
     */
    protected abstract String buildChatRequestBody(List<Message> messages);

    /**
     * 构造一次性同步请求体：非流式、无工具、关闭思考。
     */
    protected abstract String buildSimpleRequestBody(List<Message> messages);

    /**
     * 带 tool_calls 的 assistant 消息是否需要回传 reasoning_content。
     * <p>
     * DeepSeek 在 thinking 模式下强制要求回传，否则下一轮请求返回 400；
     * 通义千问无此要求，保持 false 以维持原有请求体不变。
     */
    protected boolean replayReasoningContent() {
        return false;
    }

    protected Request newRequest(String jsonBody) {
        return new Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer " + apiKey)
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
            .build();
    }

    /**
     * 从非流式响应体中取出 choices[0].message.content
     */
    protected String extractMessageContent(String body) {
        JSONObject json = JSON.parseObject(body);
        if (json == null) {
            return null;
        }
        JSONArray choices = json.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        return message == null ? null : message.getString("content");
    }

    /**
     * 读取失败响应体，便于定位 4xx/5xx 的具体原因（如余额不足、参数不合法）。
     */
    private static String safeErrorBody(Response response) {
        try {
            ResponseBody body = response.body();
            return body == null ? "<empty>" : body.string();
        } catch (IOException e) {
            return "<读取响应体失败: " + e.getMessage() + ">";
        }
    }

    private String doChat(ConversationContext ctx, List<Message> messages, Consumer<String> onToken) {
        StringBuilder totalContent = new StringBuilder();
        int depth = 0;

        CancellationToken cancellation = ctx.cancellation();
        while (depth < MAX_TOOL_CALL_DEPTH) {
            if (ctx.shouldQuit() || cancellation.isCancelled()) {
                break;
            }
            functionManager.prepareMcp(ctx);
            if (cancellation.isCancelled()) break;
            String jsonBody = buildChatRequestBody(messages);
            String requestId = TraceEvent.newId();
            String thinkingId = TraceEvent.newId();
            boolean[] thinkingStarted = {false};
            ctx.emitTrace(TraceEvent.of(requestId, TraceEvent.Kind.MODEL_REQUEST, TraceEvent.Phase.START,
                    "模型请求 " + (depth + 1) + " · " + model, requestDetails(messages, jsonBody)));
            logger.info("大模型请求入参: model={}, apiUrl={}, jsonBody={}", model, apiUrl, jsonBody);

            StringBuilder fullContent = new StringBuilder();
            StringBuilder reasoningContent = new StringBuilder();
            Map<Integer, StringBuilder> toolCallArgsBuffer = new HashMap<>();
            List<ToolCall> finalToolCalls = new ArrayList<>();
            int[] usageInfo = new int[3]; // [prompt_tokens, completion_tokens, total_tokens]
            boolean reasoningOpen = false;
            boolean contentRecorded = false;

            Call call = httpClient.newCall(newRequest(jsonBody));
            Runnable cancelRequest = call::cancel;
            cancellation.register(cancelRequest);
            try (Response response = call.execute()) {
                if (!response.isSuccessful()) {
                    String error = safeErrorBody(response);
                    ctx.emitTrace(TraceEvent.of(requestId, TraceEvent.Kind.MODEL_REQUEST, TraceEvent.Phase.APPEND, "模型请求 " + (depth + 1), "\nHTTP " + response.code() + "\n" + error));
                    logger.error("请求失败：{} - {} - {}", response.code(), response.message(), error);
                    throw new IllegalStateException("模型请求失败，HTTP " + response.code());
                }

                ResponseBody body = response.body();
                if (body == null) {
                    throw new IllegalStateException("模型响应为空");
                }

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(body.byteStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (cancellation.isCancelled()) break;
                        if (!line.startsWith("data: ")) {
                            continue;
                        }
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) {
                            break;
                        }

                        StreamResponse streamResponse = JSON.parseObject(data, StreamResponse.class);
                        if (streamResponse == null) {
                            continue;
                        }
                        // 捕获 usage（通常在最后一个 chunk）
                        if (streamResponse.getUsage() != null) {
                            usageInfo[0] = streamResponse.getUsage().getPrompt_tokens();
                            usageInfo[1] = streamResponse.getUsage().getCompletion_tokens();
                            usageInfo[2] = streamResponse.getUsage().getTotal_tokens();
                        }
                        if (streamResponse.getChoices() == null || streamResponse.getChoices().length == 0) {
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
                                if (tc.getType() != null && existing.getType() == null) {
                                    existing.setType(tc.getType());
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

                        // 处理思考过程（thinking 模式），以哨兵告知调用方进入/退出灰色输出
                        String reasoning = delta.getReasoning_content();
                        if (reasoning != null && !reasoning.isEmpty()) {
                            ctx.emitTrace(TraceEvent.of(thinkingId, TraceEvent.Kind.THINKING,
                                    thinkingStarted[0] ? TraceEvent.Phase.APPEND : TraceEvent.Phase.START, "思考过程", reasoning));
                            thinkingStarted[0] = true;
                            reasoningContent.append(reasoning);
                            if (onToken != null) {
                                if (!reasoningOpen) {
                                    onToken.accept(REASONING_START);
                                    reasoningOpen = true;
                                }
                                onToken.accept(reasoning);
                            }
                        }

                        // 处理普通文本内容
                        String content = delta.getContent();
                        if (content != null && !content.isEmpty()) {
                            if (reasoningOpen && onToken != null) {
                                onToken.accept(REASONING_END);
                                reasoningOpen = false;
                            }
                            if (onToken != null) {
                                onToken.accept(content);
                            }
                            fullContent.append(content);
                        }
                    }
                }

                if (reasoningOpen && onToken != null) {
                    onToken.accept(REASONING_END);
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
                logger.info("本轮请求完成，累计内容长度：{}，待执行工具：{}", fullContent.length(), JSON.toJSONString(finalToolCalls));
                if (thinkingStarted[0]) ctx.emitTrace(TraceEvent.of(thinkingId, TraceEvent.Kind.THINKING,
                        cancellation.isCancelled() ? TraceEvent.Phase.CANCELLED : TraceEvent.Phase.COMPLETE, "思考过程", ""));
                ctx.emitTrace(TraceEvent.of(requestId, TraceEvent.Kind.MODEL_REQUEST,
                        cancellation.isCancelled() ? TraceEvent.Phase.CANCELLED : TraceEvent.Phase.COMPLETE,
                        "模型请求 " + (depth + 1) + " · " + model, "\nHTTP " + response.code()));
                Map<String, Object> resultDetails = new java.util.LinkedHashMap<>();
                resultDetails.put("content", fullContent.toString());
                resultDetails.put("tool_calls", finalToolCalls);
                resultDetails.put("usage", Map.of("prompt_tokens", usageInfo[0], "completion_tokens", usageInfo[1], "total_tokens", usageInfo[2]));
                ctx.emitTrace(TraceEvent.of(TraceEvent.newId(), TraceEvent.Kind.MODEL_RESPONSE,
                        cancellation.isCancelled() ? TraceEvent.Phase.CANCELLED : TraceEvent.Phase.COMPLETE,
                        "模型响应 " + (depth + 1), JSON.toJSONString(resultDetails, true)));

                totalContent.append(fullContent);
                contentRecorded = true;
                // 执行所有 tool_calls，收集结果后继续循环
                if (!finalToolCalls.isEmpty()) {
                    // 先将 assistant 的 tool_calls 回复加入消息列表
                    Message assistantMsg = new Message();
                    assistantMsg.setRole("assistant");
                    assistantMsg.setContent(fullContent.toString());
                    assistantMsg.setTool_calls(finalToolCalls);
                    if (replayReasoningContent() && !reasoningContent.isEmpty()) {
                        assistantMsg.setReasoning_content(reasoningContent.toString());
                    }
                    messages.add(assistantMsg);

                    boolean anyExecuted = false;
                    for (ToolCall toolCall : finalToolCalls) {
                        if (ctx.shouldQuit() || cancellation.isCancelled()) {
                            if (onToken != null) {
                                onToken.accept("\n[收到退出信号，终止工具调用]\n");
                            }
                            break;
                        }
                        if (toolCall.getFunction() == null) {
                            continue;
                        }
                        String functionName = toolCall.getFunction().getName();
                        String args = toolCall.getFunction().getArguments();

                        if (functionManager.hasFunction(functionName)) {
                            ctx.emitTool(new ToolEvent(toolCall.getId(), functionName, args, null, ToolEvent.State.REQUESTED));
                            if (onToken != null && !ctx.hasToolEvents()) {
                                onToken.accept("\n[调用 " + functionName + " 工具，参数：" + args + "]\n");
                            }
                            String result;
                            try {
                                result = functionManager.execute(functionName, args, ctx);
                            } catch (RuntimeException e) {
                                ctx.emitTool(new ToolEvent(toolCall.getId(), functionName, args,
                                        e.getMessage(), ToolEvent.State.FAILED));
                                throw e;
                            }
                            ctx.emitTool(new ToolEvent(toolCall.getId(), functionName, args, result,
                                    cancellation.isCancelled() ? ToolEvent.State.CANCELLED : ToolEvent.State.RETURNED));
                            if (onToken != null && !ctx.hasToolEvents()) {
                                onToken.accept("[" + functionName + " 结果：" + result + "]\n");
                            }
                            messages.add(Message.fromTool(toolCall.getId(), result));
                            anyExecuted = true;
                        } else {
                            ctx.emitTool(new ToolEvent(toolCall.getId(), functionName, args, null, ToolEvent.State.REQUESTED));
                            ctx.emitTool(new ToolEvent(toolCall.getId(), functionName, args, "未知的函数：" + functionName, ToolEvent.State.FAILED));
                            if (onToken != null && !ctx.hasToolEvents()) {
                                onToken.accept("[未知的函数：" + functionName + "]\n");
                            }
                        }
                    }
                    if (anyExecuted) {
                        if (ctx.shouldQuit() || cancellation.isCancelled()) {
                            break;
                        }
                        depth++;
                        continue; // 继续下一轮请求
                    }
                }

                // 本轮对话结束（无 tool_call 或工具未找到），返回结果
                // 传递 usage 信息（通过特殊标记）
                if (usageInfo[2] > 0 && onToken != null) {
                    onToken.accept(USAGE_PREFIX + usageInfo[0] + ":" + usageInfo[1] + ":" + usageInfo[2]);
                }
            } catch (RuntimeException e) {
                if (!contentRecorded) {
                    ctx.emitTrace(TraceEvent.of(requestId, TraceEvent.Kind.MODEL_REQUEST, TraceEvent.Phase.FAILED, "模型请求 " + (depth + 1), "\n" + e.getMessage()));
                    if (thinkingStarted[0]) ctx.emitTrace(TraceEvent.of(thinkingId, TraceEvent.Kind.THINKING, TraceEvent.Phase.FAILED, "思考过程", ""));
                }
                throw e;
            } catch (IOException e) {
                TraceEvent.Phase phase = cancellation.isCancelled() ? TraceEvent.Phase.CANCELLED : TraceEvent.Phase.FAILED;
                ctx.emitTrace(TraceEvent.of(requestId, TraceEvent.Kind.MODEL_REQUEST, phase, "模型请求 " + (depth + 1), "\n" + e.getMessage()));
                if (thinkingStarted[0]) ctx.emitTrace(TraceEvent.of(thinkingId, TraceEvent.Kind.THINKING, phase, "思考过程", ""));
                if (!contentRecorded && !fullContent.isEmpty()) ctx.emitTrace(TraceEvent.of(TraceEvent.newId(), TraceEvent.Kind.MODEL_RESPONSE, phase, "未完成的模型响应", fullContent.toString()));
                if (!cancellation.isCancelled()) {
                    logger.error("请求失败：{}", e.getMessage(), e);
                    throw new IllegalStateException("模型连接失败：" + e.getMessage(), e);
                }
                if (!contentRecorded) totalContent.append(fullContent);
            } finally {
                cancellation.unregister(cancelRequest);
            }
            // 请求成功（或异常后），直接返回
            break;
        }

        return totalContent.toString();
    }
}
