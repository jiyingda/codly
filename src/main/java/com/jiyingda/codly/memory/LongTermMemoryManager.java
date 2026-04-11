package com.jiyingda.codly.memory;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.jiyingda.codly.command.CommandContext;
import com.jiyingda.codly.data.Message;
import com.jiyingda.codly.prompt.SystemPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆管理器，负责跨会话持久化用户偏好。
 * 存储路径：~/.codly/memory/long-term/user-preferences.json
 */
public class LongTermMemoryManager {

    private static final Logger logger = LoggerFactory.getLogger(LongTermMemoryManager.class);
    private static final String DIR_PATH = System.getProperty("user.home") + "/.codly/memory/long-term";
    private static final String FILE_PATH = DIR_PATH + "/user-preferences.json";
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private static LongTermMemoryManager instance;

    private final List<MemoryEntry> entries = new ArrayList<>();

    public record MemoryEntry(String key, String value, String createdAt, String updatedAt) {}

    private LongTermMemoryManager() {
        File dir = new File(DIR_PATH);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        load();
    }

    public static LongTermMemoryManager getInstance() {
        if (instance == null) {
            instance = new LongTermMemoryManager();
        }
        return instance;
    }

    /**
     * 从磁盘加载偏好文件
     */
    private void load() {
        File file = new File(FILE_PATH);
        if (!file.exists()) {
            logger.debug("长期记忆文件不存在，跳过加载");
            return;
        }
        try {
            String content = Files.readString(Paths.get(FILE_PATH));
            JSONObject root = JSON.parseObject(content);
            if (root == null) {
                return;
            }
            JSONArray arr = root.getJSONArray("preferences");
            if (arr == null) {
                return;
            }
            for (int i = 0; i < arr.size(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                entries.add(new MemoryEntry(
                        obj.getString("key"),
                        obj.getString("value"),
                        obj.getString("createdAt"),
                        obj.getString("updatedAt")
                ));
            }
            logger.info("加载长期记忆 {} 条", entries.size());
        } catch (Exception e) {
            logger.error("加载长期记忆失败：{}", e.getMessage(), e);
        }
    }

    /**
     * 持久化到磁盘
     */
    private void save() {
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (MemoryEntry entry : entries) {
                JSONObject obj = new JSONObject();
                obj.put("key", entry.key());
                obj.put("value", entry.value());
                obj.put("createdAt", entry.createdAt());
                obj.put("updatedAt", entry.updatedAt());
                arr.add(obj);
            }
            root.put("preferences", arr);
            Files.writeString(Paths.get(FILE_PATH), JSON.toJSONString(root, true));
            logger.debug("长期记忆已保存，共 {} 条", entries.size());
        } catch (IOException e) {
            logger.error("保存长期记忆失败：{}", e.getMessage(), e);
        }
    }

    /**
     * 添加或更新一条记忆。key 相同则更新 value 和 updatedAt。
     */
    public synchronized String addOrUpdate(String key, String value) {
        if (key == null || key.isBlank()) {
            return "key 不能为空";
        }
        if (value == null || value.isBlank()) {
            return "value 不能为空";
        }

        String now = LocalDateTime.now().format(DT_FMT);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).key().equals(key)) {
                entries.set(i, new MemoryEntry(key, value, entries.get(i).createdAt(), now));
                save();
                return "已更新记忆：" + key;
            }
        }

        entries.add(new MemoryEntry(key, value, now, now));
        save();
        return "已保存新记忆：" + key;
    }

    /**
     * 删除指定 key 的记忆
     */
    public synchronized String remove(String key) {
        boolean removed = entries.removeIf(e -> e.key().equals(key));
        if (removed) {
            save();
            return "已删除记忆：" + key;
        }
        return "未找到记忆：" + key;
    }

    /**
     * 获取所有记忆条目（只读副本）
     */
    public synchronized List<MemoryEntry> getAll() {
        return new ArrayList<>(entries);
    }

    /**
     * 清空所有长期记忆
     */
    public synchronized void clearAll() {
        entries.clear();
        save();
    }

    /**
     * 将所有记忆格式化为可注入 system prompt 的文本段。
     * 若无记忆则返回空字符串。
     */
    public synchronized String toPromptSection() {
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## User Preferences (长期记忆)\n");
        sb.append("以下是用户在之前会话中表达过的偏好，请在回复中参考：\n");
        for (MemoryEntry entry : entries) {
            sb.append("- **").append(entry.key()).append("**: ").append(entry.value()).append("\n");
        }
        return sb.toString();
    }

    private final List<String> pendingRounds = new ArrayList<>();

    /**
     * 暂存一轮对话（用户输入 + 助手回复），不立即提取。
     */
    public synchronized void appendRound(String userInput, String assistantReply) {
        pendingRounds.add("用户: " + userInput + "\n助手: " + assistantReply);
        logger.debug("暂存对话轮次，当前待提取轮数：{}", pendingRounds.size());
    }

    /**
     * 将暂存的多轮对话一次性提交给模型分析，异步提取偏好并保存。
     * 提取完成后清空暂存。不阻塞主循环。
     */
    public void flushAndExtractAsync(CommandContext ctx) {
        List<String> rounds = takePendingRounds();
        if (rounds == null) {
            return;
        }

        Thread t = new Thread(() -> doExtract(rounds, ctx), "LongTermMemory-Extract");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 同步版本：退出时调用，会阻塞直到提取完成（最多等 LLM 返回）。
     * 如果没有待提取的轮次则立即返回。
     */
    public void flushAndExtractSync(CommandContext ctx) {
        List<String> rounds = takePendingRounds();
        if (rounds == null) {
            return;
        }
        doExtract(rounds, ctx);
    }

    private synchronized List<String> takePendingRounds() {
        if (pendingRounds.isEmpty()) {
            return null;
        }
        List<String> rounds = new ArrayList<>(pendingRounds);
        pendingRounds.clear();
        return rounds;
    }

    private void doExtract(List<String> rounds, CommandContext ctx) {
        try {
            String existingMemory = toExistingMemoryText();
            String prompt = String.format(SystemPrompt.EXTRACT_MEMORY_PROMPT, existingMemory);

            String conversation = String.join("\n---\n", rounds);

            List<Message> messages = new ArrayList<>();
            messages.add(Message.fromSystem(prompt));
            messages.add(Message.fromUser(conversation));

            StringBuilder result = new StringBuilder();
            ctx.getLlmClient().chat(ctx, messages, result::append);

            String response = result.toString().trim();
            logger.debug("记忆提取模型返回：{}", response);

            parseAndSaveMemories(response);
        } catch (Exception e) {
            logger.error("提取记忆失败：{}", e.getMessage(), e);
        }
    }

    private synchronized String toExistingMemoryText() {
        if (entries.isEmpty()) {
            return "（暂无）";
        }
        StringBuilder sb = new StringBuilder();
        for (MemoryEntry entry : entries) {
            sb.append("- ").append(entry.key()).append(": ").append(entry.value()).append("\n");
        }
        return sb.toString();
    }

    private void parseAndSaveMemories(String response) {
        String json = response.trim();
        // 提取可能被 markdown 代码块包裹的 JSON
        if (json.contains("```")) {
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
        }

        try {
            JSONArray arr = JSON.parseArray(json);
            if (arr == null || arr.isEmpty()) {
                logger.debug("本轮对话未提取到新偏好");
                return;
            }
            for (int i = 0; i < arr.size(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String key = obj.getString("key");
                String value = obj.getString("value");
                if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                    String result = addOrUpdate(key, value);
                    logger.info("自动提取记忆：{}", result);
                }
            }
        } catch (Exception e) {
            logger.warn("解析记忆提取结果失败：{}", e.getMessage());
        }
    }
}
