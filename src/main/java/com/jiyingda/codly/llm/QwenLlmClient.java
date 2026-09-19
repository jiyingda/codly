package com.jiyingda.codly.llm;

import com.alibaba.fastjson.JSON;
import com.jiyingda.codly.config.Config;
import com.jiyingda.codly.config.ConfigException;
import com.jiyingda.codly.data.ChatRequest;
import com.jiyingda.codly.data.Message;

import java.util.List;

/**
 * 通义千问大模型调用客户端。
 * <p>
 * 流式解析与 tool_calls 编排由 {@link AbstractLlmClient} 统一处理，
 * 本类只负责千问特有的请求体构造（enable_thinking / thinking_budget / enable_search / result_format）。
 */
public class QwenLlmClient extends AbstractLlmClient {

    public QwenLlmClient() {
        this(null);
    }

    public QwenLlmClient(String model) {
        super(Config.getApiUrlSafe(), requireApiKey(), resolveModel(model), Config.getEnableThinkingSafe());
    }

    @Override
    public List<String> getAvailableModels() {
        return Config.getAvailableModelsSafe();
    }

    @Override
    protected String buildChatRequestBody(List<Message> messages) {
        ChatRequest chatRequest = new ChatRequest();
        chatRequest.setModel(model);
        chatRequest.setStream(true);
        chatRequest.setTop_p(0.5);
        chatRequest.setTemperature(0.5);
        chatRequest.setEnable_search(false);
        chatRequest.setEnable_thinking(enableThinking);
        chatRequest.setThinking_budget(4000);
        chatRequest.setResult_format("message");
        chatRequest.setTools(functionManager.getTools());
        chatRequest.setMessages(messages);
        chatRequest.setStream_options(new ChatRequest.StreamOptions(true));
        return JSON.toJSONString(chatRequest);
    }

    @Override
    protected String buildSimpleRequestBody(List<Message> messages) {
        ChatRequest req = new ChatRequest();
        req.setModel(model);
        req.setStream(false);
        req.setTemperature(0.3);
        req.setEnable_thinking(false);
        req.setResult_format("message");
        req.setMessages(messages);
        return JSON.toJSONString(req);
    }

    private static String requireApiKey() {
        requireConfigLoaded();
        String apiKey = Config.getApiKeySafe();
        if (apiKey == null || apiKey.isBlank()) {
            throw new ConfigException("配置文件中必须配置 apiKey");
        }
        return apiKey;
    }

    private static String resolveModel(String model) {
        requireConfigLoaded();
        String configModel = Config.getDefaultModelSafe();
        if (configModel == null || configModel.isBlank()) {
            throw new ConfigException("配置文件中必须配置 defaultModel");
        }
        return model != null && !model.isBlank() ? model : configModel;
    }
}
