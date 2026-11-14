package com.jiyingda.codly.core;

import java.time.Instant;
import java.util.UUID;

/** 无界面依赖的执行轨迹；同一 id 的事件更新同一个时间轴节点。 */
public record TraceEvent(String id, Instant at, Kind kind, Phase phase, String title, String detail) {
    public enum Kind { INPUT, MODEL_REQUEST, MODEL_RESPONSE, THINKING, TOOL, APPROVAL, LIFECYCLE }
    public enum Phase { START, APPEND, COMPLETE, FAILED, CANCELLED }
    public static String newId() { return UUID.randomUUID().toString(); }
    public static TraceEvent of(String id, Kind kind, Phase phase, String title, String detail) {
        return new TraceEvent(id, Instant.now(), kind, phase, title, detail == null ? "" : detail);
    }
}
