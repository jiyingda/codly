package com.jiyingda.codly.llm;

import com.alibaba.fastjson.JSON;
import com.jiyingda.codly.config.Config;
import com.jiyingda.codly.config.ConfigException;
import com.jiyingda.codly.data.ChatRequest;
import com.jiyingda.codly.data.DeepSeekChatRequest;
import com.jiyingda.codly.data.Message;

import java.util.List;

/**
 * DeepSeek 大模型调用客户端。
 * <p>
 * 流式解析与 tool_calls 编排由 {@link AbstractLlmClient} 统一处理，
 * 本类只负责 DeepSeek 特有的请求体构造（thinking / reasoning_effort）。
 *
 * @see <a href="https://api-docs.deepseek.com/zh-cn/">DeepSeek API 文档</a>
 */
public class DeepSeekLlmClient extends AbstractLlmClient {

    public DeepSeekLlmClient() {
        this(null);
    }

    public DeepSeekLlmClient(String model) {
        super(Config.getDeepseekApiUrlSafe(), requireApiKey(), resolveModel(model), Config.getEnableThinkingSafe());
    }

    @Override
    public List<String> getAvailableModels() {
        return Config.getDeepseekAvailableModelsSafe();
    }

    /**
     * DeepSeek 在 thinking 模式下要求把 reasoning_content 随 assistant 消息一起回传，
     * 否则携带 tool_calls 的后续请求会返回 400。
     */
    @Override
    protected boolean replayReasoningContent() {
        return true;
    }

    @Override
    protected String buildChatRequestBody(List<Message> messages) {
        DeepSeekChatRequest req = new DeepSeekChatRequest();
        req.setModel(model);
        req.setMessages(messages);
        req.setStream(true);
        req.setStream_options(new ChatRequest.StreamOptions(true));
        req.setTools(functionManager.getTools());
        req.setThinking(new DeepSeekChatRequest.Thinking(enableThinking ? "enabled" : "disabled"));
        if (enableThinking) {
            req.setReasoning_effort(Config.getDeepseekReasoningEffortSafe());
        }
        return JSON.toJSONString(req);
    }

    @Override
    protected String buildSimpleRequestBody(List<Message> messages) {
        DeepSeekChatRequest req = new DeepSeekChatRequest();
        req.setModel(model);
        req.setMessages(messages);
        req.setStream(false);
        req.setTemperature(0.3);
        req.setThinking(new DeepSeekChatRequest.Thinking("disabled"));
        return JSON.toJSONString(req);
    }

    private static String requireApiKey() {
        requireConfigLoaded();
        String apiKey = Config.getDeepseekApiKeySafe();
        if (apiKey == null || apiKey.isBlank()) {
            throw new ConfigException("配置文件中必须配置 deepseek.apiKey");
        }
        return apiKey;
    }

    private static String resolveModel(String model) {
        requireConfigLoaded();
        return model != null && !model.isBlank() ? model : Config.getDeepseekModelSafe();
    }
}
