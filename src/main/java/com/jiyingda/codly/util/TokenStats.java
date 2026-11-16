package com.jiyingda.codly.util;

/**
 * 跟踪 LLM 响应的统计信息：token 用量和耗时。
 */
public class TokenStats {
    private final java.util.function.LongSupplier clock;

    private long startTimeMs;
    private long endTimeMs;
    private long firstTokenTimeMs;
    private int chunkCount;
    private boolean usageReported;
    private boolean firstTokenReceived;

    // API 返回的 usage 信息（如果有）
    private int promptTokens;
    private int completionTokens;
    private int totalTokens;

    public TokenStats() {
        this(System::currentTimeMillis);
    }

    TokenStats(java.util.function.LongSupplier clock) {
        this.clock = clock;
        reset();
    }

    public void reset() {
        this.startTimeMs = -1;
        this.endTimeMs = -1;
        this.firstTokenTimeMs = 0;
        this.chunkCount = 0;
        this.usageReported = false;
        this.firstTokenReceived = false;
        this.promptTokens = 0;
        this.completionTokens = 0;
        this.totalTokens = 0;
    }

    public void markStart() {
        this.startTimeMs = clock.getAsLong();
    }

    public void markFirstToken() {
        if (!firstTokenReceived) {
            this.firstTokenTimeMs = clock.getAsLong();
            this.firstTokenReceived = true;
        }
    }

    public void markEnd() {
        this.endTimeMs = clock.getAsLong();
    }

    public void recordChunk() {
        this.chunkCount++;
    }

    public void setUsage(int promptTokens, int completionTokens, int totalTokens) {
        if (promptTokens < 0 || completionTokens < 0 || totalTokens < 0) return;
        this.usageReported = true;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    /**
     * 获取首 token 延迟（TTFT）毫秒
     */
    public long getTimeToFirstTokenMs() {
        if (!firstTokenReceived || startTimeMs < 0) return 0;
        return firstTokenTimeMs - startTimeMs;
    }

    /**
     * 获取总耗时毫秒
     */
    public long getTotalTimeMs() {
        if (startTimeMs < 0) return 0;
        long end = endTimeMs >= 0 ? endTimeMs : clock.getAsLong();
        return Math.max(0, end - startTimeMs);
    }

    /**
     * API 完成 token / 本轮请求总耗时（包含首 token 等待、工具执行）。无 usage 时不估算。
     */
    public double getTokensPerSecond() {
        long durationMs = getTotalTimeMs();
        if (durationMs == 0 || !usageReported || completionTokens == 0) return 0;
        return (double) completionTokens / durationMs * 1000.0;
    }

    public int getChunkCount() { return chunkCount; }
    public boolean hasUsage() { return usageReported; }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    /**
     * 格式化输出统计摘要
     */
    public String formatSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("\033[2m"); // DIM

        long totalMs = getTotalTimeMs();
        long ttft = getTimeToFirstTokenMs();

        // 耗时
        sb.append("⏱ ");
        if (totalMs < 1000) {
            sb.append(totalMs).append("ms");
        } else {
            sb.append(String.format("%.1fs", totalMs / 1000.0));
        }

        // 首 token 延迟
        if (ttft > 0) {
            sb.append(" (首token ");
            if (ttft < 1000) {
                sb.append(ttft).append("ms");
            } else {
                sb.append(String.format("%.1fs", ttft / 1000.0));
            }
            sb.append(")");
        }

        // Token 统计
        sb.append(" │ ");
        if (usageReported) {
            // 使用 API 返回的精确值
            sb.append("📊 ").append(promptTokens).append(" prompt + ")
                    .append(completionTokens).append(" completion = ")
                    .append(totalTokens).append(" tokens");
        } else {
            sb.append("未返回 token 用量");
        }

        // 速度
        double speed = getTokensPerSecond();
        if (speed > 0) {
            sb.append(" │ ⚡ ").append(String.format("%.0f", speed)).append(" tok/s");
        }

        sb.append("\033[0m"); // RESET
        return sb.toString();
    }
}

