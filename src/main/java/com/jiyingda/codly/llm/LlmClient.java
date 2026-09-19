package com.jiyingda.codly.llm;

import com.jiyingda.codly.config.Config;

import java.util.List;

/**
 * LLM 客户端工厂类，提供统一的入口来获取 LlmProvider 实例。
 * 具体实现由配置文件的 {@code provider} 字段决定，当前支持：通义千问（qwen）、DeepSeek（deepseek）。
 */
public class LlmClient {

    /**
     * 获取当前 provider 的 LlmProvider 实例。
     *
     * @return LlmProvider 实例
     */
    public static LlmProvider create() {
        return create(null);
    }

    /**
     * 获取当前 provider 下指定模型的 LlmProvider 实例。
     *
     * @param model 模型名称，为空时使用配置文件中的默认模型
     * @return LlmProvider 实例
     */
    public static LlmProvider create(String model) {
        if (Config.PROVIDER_DEEPSEEK.equals(Config.getProviderSafe())) {
            return new DeepSeekLlmClient(model);
        }
        return new QwenLlmClient(model);
    }

    /**
     * 获取当前 provider 的可用模型列表。
     *
     * @return 可用模型列表
     */
    public static List<String> getAvailableModels() {
        return Config.getCurrentProviderModelsSafe();
    }
}
