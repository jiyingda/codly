package com.jiyingda.codly.core;

/** 对话标题的显示规则，模型返回无效内容时保留首条消息的临时名称。 */
public final class ConversationTitle {
    private ConversationTitle() {}
    public static final int MAX_LENGTH = 20;

    public static String fromModel(String response) {
        if (response == null) return null;
        String title = response.replaceAll("(?s)<think>.*?</think>", "").strip();
        if (title.startsWith("<think>") || title.startsWith("```") || title.startsWith("{") || title.startsWith("[")) return null;
        title = title.lines().map(String::strip).filter(s -> !s.isEmpty()).findFirst().orElse("");
        title = title.replaceFirst("^(?:对话标题|标题|Title)[:：]\\s*", "");
        title = title.replaceAll("^[\\\"'“‘「]+|[\\\"'”’」]+$", "").strip();
        if (title.isEmpty() || title.equalsIgnoreCase("null")) return null;
        return shorten(title);
    }

    public static String fallback(String message) {
        if (message == null || message.isBlank()) return "新的对话";
        return shorten(message.strip().replaceAll("\\s+", " "));
    }

    private static String shorten(String title) {
        int count = title.codePointCount(0, title.length());
        return count <= MAX_LENGTH ? title : title.substring(0, title.offsetByCodePoints(0, MAX_LENGTH));
    }
}
