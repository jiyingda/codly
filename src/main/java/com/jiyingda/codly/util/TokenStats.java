package com.jiyingda.codly.util;

/**
 * 跟踪 LLM 响应的统计信息：token 用量和耗时。
 */
public class TokenStats {

    private long startTimeMs;
    private long endTimeMs;
    private long firstTokenTimeMs;
    private int tokenCount;
    private boolean firstTokenReceived;

    // API 返回的 usage 信息（如果有）
    private int promptTokens;
    private int completionTokens;
    private int totalTokens;

    public TokenStats() {
        reset();
    }

    public void reset() {
        this.startTimeMs = 0;
        this.endTimeMs = 0;
        this.firstTokenTimeMs = 0;
        this.tokenCount = 0;
        this.firstTokenReceived = false;
        this.promptTokens = 0;
        this.completionTokens = 0;
        this.totalTokens = 0;
    }

    public void markStart() {
        this.startTimeMs = System.currentTimeMillis();
    }

    public void markFirstToken() {
        if (!firstTokenReceived) {
            this.firstTokenTimeMs = System.currentTimeMillis();
            this.firstTokenReceived = true;
        }
    }

    public void markEnd() {
        this.endTimeMs = System.currentTimeMillis();
    }

    public void incrementTokens() {
        this.tokenCount++;
    }

    public void setUsage(int promptTokens, int completionTokens, int totalTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    /**
     * 获取首 token 延迟（TTFT）毫秒
     */
    public long getTimeToFirstTokenMs() {
        if (!firstTokenReceived || startTimeMs == 0) return 0;
        return firstTokenTimeMs - startTimeMs;
    }

    /**
     * 获取总耗时毫秒
     */
    public long getTotalTimeMs() {
        if (startTimeMs == 0) return 0;
        long end = endTimeMs > 0 ? endTimeMs : System.currentTimeMillis();
        return end - startTimeMs;
    }

    /**
     * 获取生成速度 (tokens/s)
     */
    public double getTokensPerSecond() {
        long durationMs = getTotalTimeMs();
        if (durationMs == 0 || tokenCount == 0) return 0;
        return (double) tokenCount / durationMs * 1000.0;
    }

    public int getTokenCount() {
        return tokenCount;
    }

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
        if (totalTokens > 0) {
            // 使用 API 返回的精确值
            sb.append("📊 ").append(promptTokens).append(" prompt + ")
                    .append(completionTokens).append(" completion = ")
                    .append(totalTokens).append(" tokens");
        } else if (tokenCount > 0) {
            sb.append("📊 ~").append(tokenCount).append(" tokens");
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

