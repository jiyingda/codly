/**
 * @(#)ChatRequest.java, 4 月 2, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly.data;

import java.util.List;

/**
 * 请求体结构
 */
@SuppressWarnings("unused")
public class ChatRequest {
    private String model;
    private List<Message> messages;
    private boolean stream;
    private double top_p;
    private double temperature;
    private boolean enable_search;
    private boolean enable_thinking;
    private int thinking_budget;
    private String result_format;
    private List<Tool> tools;

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

    public double getTop_p() {
        return top_p;
    }

    public void setTop_p(double top_p) {
        this.top_p = top_p;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public boolean isEnable_search() {
        return enable_search;
    }

    public void setEnable_search(boolean enable_search) {
        this.enable_search = enable_search;
    }

    public boolean isEnable_thinking() {
        return enable_thinking;
    }

    public void setEnable_thinking(boolean enable_thinking) {
        this.enable_thinking = enable_thinking;
    }

    public int getThinking_budget() {
        return thinking_budget;
    }

    public void setThinking_budget(int thinking_budget) {
        this.thinking_budget = thinking_budget;
    }

    public String getResult_format() {
        return result_format;
    }

    public void setResult_format(String result_format) {
        this.result_format = result_format;
    }

    public List<Tool> getTools() {
        return tools;
    }

    public void setTools(List<Tool> tools) {
        this.tools = tools;
    }
}

