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

    public StreamChoice[] getChoices() {
        return choices;
    }

    public void setChoices(StreamChoice[] choices) {
        this.choices = choices;
    }
}

