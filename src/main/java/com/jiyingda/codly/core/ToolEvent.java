package com.jiyingda.codly.core;

/** 结构化的工具状态，界面无需解析 CLI 展示文本。 */
public record ToolEvent(String id, String name, String arguments, String result, State state) {
    public enum State { REQUESTED, RETURNED, CANCELLED, FAILED }
}
