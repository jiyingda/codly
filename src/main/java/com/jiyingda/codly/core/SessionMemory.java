package com.jiyingda.codly.core;

import com.jiyingda.codly.data.Message;

/** 会话编排依赖的存储能力，与具体文件布局和用户界面无关。 */
public interface SessionMemory {
    void initializeWithSession(String title);
    boolean appendMessage(Message message);
    void appendLtmRound(String userInput, String assistantReply);
    String toLongTermPromptSection();
    void flushAndExtractLtmAsync(ConversationContext ctx);
    void flushAndExtractLtmSync(ConversationContext ctx);
    void flushNow();
    void clearSession();
    default String getCurrentSessionFile() { return null; }
    default void restoreSession(String reference, java.util.List<Message> messages) {
        clearSession();
        messages.forEach(this::appendMessage);
    }
    void shutdown();
}
