/**
 * @(#)DeepSeekChatRequest.java, 9 月 19, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly.data;

import java.util.List;

/**
 * DeepSeek Chat Completions 请求体。
 * <p>
 * 与通义千问的差异：用 {@code thinking} / {@code reasoning_effort} 控制思考模式，
 * 不存在 {@code enable_thinking} / {@code thinking_budget} / {@code enable_search} / {@code result_format}。
 * 字段为 null 时不会序列化，未设置的一律使用 DeepSeek 服务端默认值。
 */
@SuppressWarnings("unused")
public class DeepSeekChatRequest {

    private String model;
    private List<Message> messages;
    private boolean stream;
    private Double temperature;
    private List<Tool> tools;
    private Thinking thinking;
    private String reasoning_effort;
    private ChatRequest.StreamOptions stream_options;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    public boolean isStream() {
        return stream;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public List<Tool> getTools() {
        return tools;
    }

    public void setTools(List<Tool> tools) {
        this.tools = tools;
    }

    public Thinking getThinking() {
        return thinking;
    }

    public void setThinking(Thinking thinking) {
        this.thinking = thinking;
    }

    public String getReasoning_effort() {
        return reasoning_effort;
    }

    public void setReasoning_effort(String reasoning_effort) {
        this.reasoning_effort = reasoning_effort;
    }

    public ChatRequest.StreamOptions getStream_options() {
        return stream_options;
    }

    public void setStream_options(ChatRequest.StreamOptions stream_options) {
        this.stream_options = stream_options;
    }

    /**
     * 思考模式开关。type 取值：enabled / disabled
     */
    public static class Thinking {
        private String type;

        public Thinking() {}

        public Thinking(String type) {
            this.type = type;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }
    }
}
