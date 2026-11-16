package com.jiyingda.codly.core;

/** 结构化的工具状态，界面无需解析 CLI 展示文本。 */
public record ToolEvent(String id, String name, String arguments, String result, State state) {
    public enum State { REQUESTED, OUTPUT, RETURNED, CANCELLED, FAILED }

    public boolean failed() {
        if (state == State.FAILED || state == State.CANCELLED) return true;
        if (result == null) return false;
        for (String prefix : java.util.List.of("用户拒绝", "执行失败", "执行 execBash 失败", "命令执行超时", "命令已取消", "MCP 工具执行失败")) {
            if (result.startsWith(prefix)) return true;
        }
        if (result.stripLeading().startsWith("{")) try {
            return com.alibaba.fastjson.JSON.parseObject(result).getBooleanValue("isError");
        } catch (RuntimeException ignored) { }
        return false;
    }
}
