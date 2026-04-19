package com.jiyingda.codly;

import com.jiyingda.codly.util.ProgressIndicator;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * LLM 流式响应的终端渲染器：管理进度指示器、打印 token、输出前缀。
 */
public class ResponseRenderer {

    /**
     * 执行一次 LLM 请求并渲染流式输出。
     *
     * @param chatInvoker 接受 token 回调、执行 LLM 调用的函数
     * @return 完整响应文本
     */
    public String render(Consumer<Consumer<String>> chatInvoker) {
        AtomicBoolean responseStarted = new AtomicBoolean(false);
        StringBuilder fullResponse = new StringBuilder();

        ProgressIndicator indicator = new ProgressIndicator();
        indicator.start();

        chatInvoker.accept(token -> {
            if (responseStarted.compareAndSet(false, true)) {
                indicator.stop();
                indicator.clear();
                System.out.print(">> ");
            }
            System.out.print(token);
            System.out.flush();
            fullResponse.append(token);
        });

        indicator.stop();

        if (!responseStarted.get()) {
            indicator.clear();
            System.out.print(">> ");
        }

        System.out.println();
        return fullResponse.toString();
    }
}
