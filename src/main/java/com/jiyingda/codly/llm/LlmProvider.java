package com.jiyingda.codly.llm;

import com.jiyingda.codly.command.CommandContext;
import com.jiyingda.codly.data.Message;

import java.util.List;
import java.util.function.Consumer;

/**
 * 大语言模型客户端接口，定义了与 LLM 交互的基本操作。
 */
public interface LlmProvider {

    /**
     * 获取当前使用的模型名称。
     *
     * @return 模型名称
     */
    String getModel();

    /**
     * 设置要使用的模型名称。
     *
     * @param model 模型名称
     */
    void setModel(String model);

    /**
     * 发起对话，流式内容通过 onToken 回调实时输出。
     *
     * @param ctx     命令上下文
     * @param onToken 每个流式 token 的回调，传入 null 则静默
     * @return 完整的模型回复文本
     */
    String chat(CommandContext ctx, Consumer<String> onToken);

    /**
     * 发起对话，使用指定的消息列表。
     *
     * @param ctx     命令上下文
     * @param messages 消息列表
     * @param onToken 每个流式 token 的回调，传入 null 则静默
     * @return 完整的模型回复文本
     */
    String chat(CommandContext ctx, List<Message> messages, Consumer<String> onToken);

    /**
     * 发起一次非流式补全，不带工具、关闭思考，供标题生成等内部任务使用。
     *
     * @param messages 消息列表
     * @return 模型回复文本，失败返回 null
     */
    String complete(List<Message> messages);

    /**
     * 根据首轮对话内容异步生成一个简短标题。
     *
     * @param userMessage 用户首条消息
     * @param onTitle     标题生成后的回调
     */
    void generateTitleAsync(String userMessage, Consumer<String> onTitle);

    /**
     * 获取可用的模型列表。
     *
     * @return 可用模型列表
     */
    List<String> getAvailableModels();
}