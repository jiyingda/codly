package com.jiyingda.codly.llm;

/** 对话级思考选择；由 provider 映射为各自支持的参数。 */
public enum ThinkingDepth {
    OFF("不思考", 0, "none"),
    LOW("浅度思考", 1024, "low"),
    HIGH("深度思考", 4000, "high"),
    MAX("极深思考", 16384, "max");

    private final String label;
    private final int budget;
    private final String effort;
    ThinkingDepth(String label, int budget, String effort) { this.label = label; this.budget = budget; this.effort = effort; }
    public int budget() { return budget; }
    public String effort() { return effort; }
    public boolean enabled() { return this != OFF; }
    @Override public String toString() { return label; }
    public static ThinkingDepth fromEffort(String effort) {
        if (effort == null || effort.isBlank()) return HIGH;
        for (ThinkingDepth value : values()) if (value.effort.equals(effort)) return value;
        throw new IllegalArgumentException("不支持的思考深度：" + effort);
    }
}
