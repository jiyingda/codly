package com.jiyingda.codly;

import com.jiyingda.codly.util.MarkdownRenderer;
import com.jiyingda.codly.util.ProgressIndicator;
import com.jiyingda.codly.util.TokenStats;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * LLM 流式响应的终端渲染器：管理进度指示器、Markdown 着色、token 统计。
 */
public class ResponseRenderer {

    private final TokenStats lastStats = new TokenStats();

    /**
     * 执行一次 LLM 请求并渲染流式输出（含 Markdown 着色和统计）。
     *
     * @param chatInvoker 接受 token 回调、执行 LLM 调用的函数
     * @return 完整响应文本
     */
    public String render(Consumer<Consumer<String>> chatInvoker) {
        AtomicBoolean responseStarted = new AtomicBoolean(false);
        StringBuilder fullResponse = new StringBuilder();
        MarkdownRenderer mdRenderer = new MarkdownRenderer();
        TokenStats stats = new TokenStats();
        stats.markStart();

        ProgressIndicator indicator = new ProgressIndicator();
        indicator.start();

        chatInvoker.accept(token -> {
            // 检测 usage 特殊标记
            if (token.startsWith("\u0000USAGE:")) {
                String[] parts = token.substring(7).split(":");
                if (parts.length == 3) {
                    try {
                        stats.setUsage(
                            Integer.parseInt(parts[0]),
                            Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2])
                        );
                    } catch (NumberFormatException ignored) {}
                }
                return;
            }
            if (responseStarted.compareAndSet(false, true)) {
                indicator.stop();
                indicator.clear();
                System.out.print(">> ");
                stats.markFirstToken();
            }
            stats.incrementTokens();
            // Markdown 渲染
            String rendered = mdRenderer.process(token);
            if (!rendered.isEmpty()) {
                System.out.print(rendered);
                System.out.flush();
            }
            fullResponse.append(token);
        });

        indicator.stop();

        if (!responseStarted.get()) {
            indicator.clear();
            System.out.print(">> ");
        }

        // flush markdown 渲染器中的剩余文本
        String remaining = mdRenderer.flush();
        if (!remaining.isEmpty()) {
            System.out.print(remaining);
        }

        stats.markEnd();
        System.out.println();

        // 输出统计信息
        if (stats.getTokenCount() > 0) {
            System.out.println(stats.formatSummary());
        }

        // 保存最近一次统计
        lastStats.reset();
        lastStats.setUsage(stats.getPromptTokens(), stats.getCompletionTokens(), stats.getTotalTokens());

        return fullResponse.toString();
    }

    /**
     * 获取上次请求的统计信息
     */
    public TokenStats getLastStats() {
        return lastStats;
    }
}
