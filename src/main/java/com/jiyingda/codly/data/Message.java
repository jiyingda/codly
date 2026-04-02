/**
 * @(#)Message.java, 4 月 2, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly.data;

import java.util.List;

/**
 * 消息结构
 */
@SuppressWarnings("unused")
public class Message {
    private String role;
    private String content;
    private List<ToolCall> tool_calls;
    private String tool_call_id;

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<ToolCall> getTool_calls() {
        return tool_calls;
    }

    public void setTool_calls(List<ToolCall> tool_calls) {
        this.tool_calls = tool_calls;
    }

    public String getTool_call_id() {
        return tool_call_id;
    }

    public void setTool_call_id(String tool_call_id) {
        this.tool_call_id = tool_call_id;
    }

    // from user
    public static Message fromUser(String text) {
        Message message = new Message();
        message.setRole("user");
        message.setContent(text);
        return message;
    }

    public static Message formAssistant(String text) {
        Message message = new Message();
        message.setRole("assistant");
        message.setContent(text);
        return message;
    }

    public static Message fromSystem(String text) {
        Message message = new Message();
        message.setRole("system");
        message.setContent(text);
        return message;
    }

    public static Message fromTool(String toolCallId, String content) {
        Message message = new Message();
        message.setRole("tool");
        message.setTool_call_id(toolCallId);
        message.setContent(content);
        return message;
    }
}

