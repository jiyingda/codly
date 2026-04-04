package com.jiyingda.codly.llm;

import com.jiyingda.codly.config.Config;

import java.util.List;

/**
 * LLM 客户端工厂类，提供统一的入口来获取 LlmProvider 实例。
 * 支持多种 LLM 提供商，当前支持：通义千问
 */
public class LlmClient {

    /**
     * 获取默认的 LlmProvider 实例（通义千问）。
     *
     * @return LlmProvider 实例
     */
    public static LlmProvider create() {
        return new QwenLlmClient();
    }

    /**
     * 获取指定模型的 LlmProvider 实例（通义千问）。
     *
     * @param model 模型名称
     * @return LlmProvider 实例
     */
    public static LlmProvider create(String model) {
        return new QwenLlmClient(model);
    }

    /**
     * 获取可用的模型列表。
     *
     * @return 可用模型列表
     */
    public static List<String> getAvailableModels() {
        return Config.getAvailableModelsSafe();
    }
}