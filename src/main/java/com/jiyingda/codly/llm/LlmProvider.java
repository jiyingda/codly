package com.jiyingda.codly.llm;

import com.jiyingda.codly.core.ConversationContext;
import com.jiyingda.codly.core.TraceEvent;
import com.jiyingda.codly.data.Message;

import java.util.List;
import java.util.function.Consumer;

/**
 * 大语言模型客户端接口，定义了与 LLM 交互的基本操作。
 */
public interface LlmProvider {
    default int estimateRequestTokens(ConversationContext context,List<Message> messages){return com.jiyingda.codly.context.ContextBudget.estimate(com.alibaba.fastjson.JSON.toJSONString(messages));}
    default void close() { }
    default String mcpStatus(ConversationContext context,com.alibaba.fastjson.JSONObject refreshed){return "当前模型入口未提供 MCP 管理能力";}

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
    default String providerId() { return ""; }

    default ThinkingDepth getThinkingDepth() { return ThinkingDepth.OFF; }
    default void setThinkingDepth(ThinkingDepth depth) { throw new UnsupportedOperationException("当前模型入口不支持思考深度切换"); }
    default boolean supportsThinkingDepth() { return false; }

    /**
     * 发起对话，流式内容通过 onToken 回调实时输出。
     *
     * @param ctx     命令上下文
     * @param onToken 每个流式 token 的回调，传入 null 则静默
     * @return 完整的模型回复文本
     */
    String chat(ConversationContext ctx, Consumer<String> onToken);

    /**
     * 发起对话，使用指定的消息列表。
     *
     * @param ctx     命令上下文
     * @param messages 消息列表
     * @param onToken 每个流式 token 的回调，传入 null 则静默
     * @return 完整的模型回复文本
     */
    String chat(ConversationContext ctx, List<Message> messages, Consumer<String> onToken);

    /**
     * 发起一次非流式补全，不带工具、关闭思考，供标题生成等内部任务使用。
     *
     * @param messages 消息列表
     * @return 模型回复文本，失败返回 null
     */
    String complete(List<Message> messages);

    default String complete(List<Message> messages, Consumer<TraceEvent> trace, String purpose) { return complete(messages); }
    default String complete(List<Message> messages,Consumer<TraceEvent> trace,String purpose,com.jiyingda.codly.core.CancellationToken cancellation){return complete(messages,trace,purpose);}

    /**
     * 根据首轮对话内容异步生成一个简短标题。
     *
     * @param userMessage 用户首条消息
     * @param onTitle     标题生成后的回调
     */
    void generateTitleAsync(String userMessage, Consumer<String> onTitle);

    default void generateTitleAsync(String userMessage, Consumer<String> onTitle, Consumer<TraceEvent> trace) { generateTitleAsync(userMessage, onTitle); }

    /**
     * 获取可用的模型列表。
     *
     * @return 可用模型列表
     */
    List<String> getAvailableModels();
}
