/**
 * @(#)StreamResponse.java, 4 月 2, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly.data;

/**
 * 流式响应结构
 */
@SuppressWarnings("unused")
public class StreamResponse {
    private StreamChoice[] choices;
    private Usage usage;

    public StreamChoice[] getChoices() {
        return choices;
    }

    public void setChoices(StreamChoice[] choices) {
        this.choices = choices;
    }

    public Usage getUsage() {
        return usage;
    }

    public void setUsage(Usage usage) {
        this.usage = usage;
    }

    /**
     * Token 用量统计（通常在最后一个 chunk 返回）
     */
    public static class Usage {
        private int prompt_tokens;
        private int completion_tokens;
        private int total_tokens;

        public int getPrompt_tokens() { return prompt_tokens; }
        public void setPrompt_tokens(int prompt_tokens) { this.prompt_tokens = prompt_tokens; }
        public int getCompletion_tokens() { return completion_tokens; }
        public void setCompletion_tokens(int completion_tokens) { this.completion_tokens = completion_tokens; }
        public int getTotal_tokens() { return total_tokens; }
        public void setTotal_tokens(int total_tokens) { this.total_tokens = total_tokens; }
    }
}

