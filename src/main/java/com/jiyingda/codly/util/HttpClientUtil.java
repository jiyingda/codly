package com.jiyingda.codly.util;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * HTTP 客户端工具类，提供优化的 OkHttpClient 实例。
 * 包含超时配置、日志记录和自动重试功能。
 */
public class HttpClientUtil {

    /**
     * 创建优化的 OkHttpClient，包含超时、重试和异常处理配置
     *
     * @return 配置好的 OkHttpClient 实例
     */
    public static OkHttpClient createOptimizedHttpClient() {
        return new OkHttpClient.Builder()
                // 连接超时（10秒）
                .connectTimeout(10, TimeUnit.SECONDS)
                // 读超时（60秒，考虑流式响应）
                .readTimeout(60, TimeUnit.SECONDS)
                // 写超时（10秒）
                .writeTimeout(10, TimeUnit.SECONDS)
                // 禁用自动重定向
                .followRedirects(true)
                // 添加请求/响应日志拦截器
                .addInterceptor(new LoggingInterceptor())
                // 添加重试拦截器（最多重试2次）
                .addInterceptor(new RetryInterceptor(2))
                .build();
    }

    /**
     * 日志拦截器：记录请求和响应信息
     */
    private static class LoggingInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            long startTime = System.currentTimeMillis();

            try {
                Response response = chain.proceed(request);
                long duration = System.currentTimeMillis() - startTime;
                System.err.println("[LLM] " + request.method() + " " + request.url()
                        + " - 状态码: " + response.code() + " (耗时: " + duration + "ms)");
                return response;
            } catch (IOException e) {
                long duration = System.currentTimeMillis() - startTime;
                System.err.println("[LLM] " + request.method() + " " + request.url()
                        + " - 失败 (耗时: " + duration + "ms): " + e.getMessage());
                throw e;
            }
        }
    }

    /**
     * 重试拦截器：在网络错误时自动重试
     */
    private static class RetryInterceptor implements Interceptor {
        private final int maxRetry;

        public RetryInterceptor(int maxRetry) {
            this.maxRetry = maxRetry;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            IOException lastException = new IOException("未知错误");

            for (int i = 0; i <= maxRetry; i++) {
                try {
                    return chain.proceed(request);
                } catch (IOException e) {
                    lastException = e;
                    if (i < maxRetry) {
                        System.err.println("[重试] 第 " + (i + 1) + " 次重试 - " + e.getMessage());
                        try {
                            // 指数退避：1秒、2秒、4秒...
                            long delayMs = 1000L * (long) Math.pow(2, i);
                            Thread.sleep(delayMs);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new IOException("重试被中断", ex);
                        }
                    }
                }
            }

            System.err.println("[LLM] 已达最大重试次数 " + maxRetry + "，放弃重试");
            throw lastException;
        }
    }
}

