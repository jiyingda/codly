/**
 * @(#)StreamDelta.java, 4 月 2, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly.data;

import java.util.List;

/**
 * Delta 内容结构
 */
@SuppressWarnings("unused")
public class StreamDelta {
    private String content;
    private String reasoning_content;
    private String role;
    private List<ToolCall> tool_calls;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getReasoning_content() {
        return reasoning_content;
    }

    public void setReasoning_content(String reasoning_content) {
        this.reasoning_content = reasoning_content;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public List<ToolCall> getTool_calls() {
        return tool_calls;
    }

    public void setTool_calls(List<ToolCall> tool_calls) {
        this.tool_calls = tool_calls;
    }
}

