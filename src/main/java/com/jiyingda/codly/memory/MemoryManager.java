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
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 会话数据管理器，负责存储和读取会话数据
 * 存储路径：~/.codly/memory/session/日期_时分_标题_6 位随机字符串.json
 * 存储格式：每行一条 JSON 记录，直接追加到文件尾部
 * 消息先暂存到内存缓存，每 FLUSH_THRESHOLD 轮对话批量写入磁盘
 */
public class MemoryManager {

    private static final Logger logger = LoggerFactory.getLogger(MemoryManager.class);

    // ========== 会话管理字段 ==========
    private static final String BASE_PATH = System.getProperty("user.home") + "/.codly/memory/session";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    /** 每积攒多少轮对话（一问一答算一轮）后批量写入磁盘 */
    private static final int FLUSH_THRESHOLD = 3;

    private static MemoryManager instance;
    private final ExecutorService asyncExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MemoryManager-Async");
        t.setDaemon(true);
        return t;
    });

    private String currentSessionFile;
    private final List<Message> sessionMessages = new ArrayList<>();
    private final List<Message> pendingMessages = new ArrayList<>();
    private int roundsSinceFlush = 0;
    private volatile boolean fileInitialized = false;

    // ========== 长期记忆字段 ==========
    private static final String LTM_DIR_PATH = System.getProperty("user.home") + "/.codly/memory/long-term";
    private static final String LTM_FILE_PATH = LTM_DIR_PATH + "/user-preferences.json";
    private static final DateTimeFormatter LTM_DT_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /** 长期记忆条目 */
    public record MemoryEntry(String key, String value, String createdAt, String updatedAt) {}
    private final List<MemoryEntry> ltmEntries = new ArrayList<>();
    private final List<String> ltmPendingRounds = new ArrayList<>();

    private MemoryManager() {
        // 初始时不创建文件，只初始化缓存
        File sessionDir = new File(BASE_PATH);
        if (!sessionDir.exists()) {
            sessionDir.mkdirs();
        }
        // 初始化长期记忆目录并加载
        File ltmDir = new File(LTM_DIR_PATH);
        if (!ltmDir.exists()) {
            ltmDir.mkdirs();
        }
        loadLongTermMemory();
    }

    public static MemoryManager getInstance() {
        if (instance == null) {
            instance = new MemoryManager();
        }
        return instance;
    }

    /**
     * 初始化会话文件（生成标题后调用）
     * 将缓存中的消息写入文件
     * @param title 会话标题
     */
    public synchronized void initializeWithSession(String title) {
        if (fileInitialized) {
            return; // 已经初始化过，不再重复初始化
        }

        String dateTime = LocalDateTime.now().format(DATE_FORMATTER);
        String randomSuffix = generateRandomString(6);
        // 标题中的非法字符替换为下划线
        String safeTitle = title != null ? title.replaceAll("[^a-zA-Z0-9\u4e00-\u9fa5_-]", "_") : "chat";
        currentSessionFile = BASE_PATH + "/" + dateTime + "_" + safeTitle + "_" + randomSuffix + ".txt";

        logger.info("初始化会话文件：{}", currentSessionFile);
        logger.info("缓存消息数量：{}", pendingMessages.size());

        fileInitialized = true;

        // 把标题生成前暂存的消息写入磁盘（不重置轮次计数）
        if (!pendingMessages.isEmpty()) {
            flushPendingAsync(false);
        }
    }

    /**
     * 生成指定长度的随机字符串
     */
    private String generateRandomString(int length) {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt((int) (Math.random() * chars.length())));
        }
        return sb.toString();
    }

    /**
     * 添加消息到缓存。assistant 消息视为一轮结束，每 FLUSH_THRESHOLD 轮批量写入磁盘。
     *
     * @return true 如果本次追加触发了批量写盘（达到阈值）
     */
    public synchronized boolean appendMessage(Message message) {
        sessionMessages.add(message);
        pendingMessages.add(message);

        if ("assistant".equals(message.getRole())) {
            roundsSinceFlush++;
        }

        logger.debug("消息添加到缓存：{}, 待写入：{}, 轮数：{}/{}, 文件已初始化：{}",
                message.getRole(), pendingMessages.size(), roundsSinceFlush, FLUSH_THRESHOLD, fileInitialized);

        if (fileInitialized && roundsSinceFlush >= FLUSH_THRESHOLD) {
            flushPendingAsync(true);
            return true;
        }
        return false;
    }

    /**
     * 强制将所有待写入消息刷盘（退出或 /clear 时调用）
     */
    public synchronized void flushNow() {
        if (fileInitialized && !pendingMessages.isEmpty()) {
            flushPendingAsync(false);
        }
    }

    /**
     * 异步将 pendingMessages 批量写入磁盘
     *
     * @param resetRounds 是否重置轮次计数（正常阈值触发时重置，initializeWithSession 时不重置）
     */
    private void flushPendingAsync(boolean resetRounds) {
        List<Message> toWrite = new ArrayList<>(pendingMessages);
        pendingMessages.clear();
        if (resetRounds) {
            roundsSinceFlush = 0;
        }
        logger.debug("批量写入 {} 条消息到磁盘, resetRounds={}", toWrite.size(), resetRounds);

        CompletableFuture.runAsync(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                for (Message message : toWrite) {
                    JSONObject json = new JSONObject();
                    json.put("role", message.getRole());
                    json.put("content", message.getContent());
                    if (message.getTool_calls() != null) {
                        json.put("tool_calls", JSON.toJSON(message.getTool_calls()));
                    }
                    if (message.getTool_call_id() != null) {
                        json.put("tool_call_id", message.getTool_call_id());
                    }
                    sb.append(json.toJSONString()).append("\n");
                }
                Files.writeString(Paths.get(currentSessionFile), sb.toString(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                logger.info("批量写入成功：{} 条消息 -> {}", toWrite.size(), currentSessionFile);
            } catch (IOException e) {
                logger.error("批量写入失败：{}", e.getMessage(), e);
            }
        }, asyncExecutor);
    }

    /**
     * 获取当前会话的所有消息（从缓存读取）
     */
    public synchronized List<Message> getSessionMessages() {
        return new ArrayList<>(sessionMessages);
    }

    /**
     * 获取当前会话文件路径
     */
    public String getCurrentSessionFile() {
        return currentSessionFile;
    }

    /**
     * 清空当前会话
     */
    public synchronized void clearSession() {
        sessionMessages.clear();
        pendingMessages.clear();
        roundsSinceFlush = 0;
        fileInitialized = false;
        currentSessionFile = null;
    }

    /**
     * 关闭异步执行器，等待所有任务完成
     */
    public void shutdown() {
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                logger.error("异步任务未能在 5 秒内完成");
            }
        } catch (InterruptedException e) {
            logger.error("shutdown 被中断", e);
        }
    }

    // ==================== 长期记忆方法 ====================

    /**
     * 从磁盘加载偏好文件
     */
    private void loadLongTermMemory() {
        File file = new File(LTM_FILE_PATH);
        if (!file.exists()) {
            logger.debug("长期记忆文件不存在，跳过加载");
            return;
        }
        try {
            String content = Files.readString(Paths.get(LTM_FILE_PATH));
            JSONObject root = JSON.parseObject(content);
            if (root == null) return;
            JSONArray arr = root.getJSONArray("preferences");
            if (arr == null) return;
            for (int i = 0; i < arr.size(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                ltmEntries.add(new MemoryEntry(
                        obj.getString("key"),
                        obj.getString("value"),
                        obj.getString("createdAt"),
                        obj.getString("updatedAt")
                ));
            }
            logger.info("加载长期记忆 {} 条", ltmEntries.size());
        } catch (Exception e) {
            logger.error("加载长期记忆失败：{}", e.getMessage(), e);
        }
    }

    /**
     * 持久化到磁盘
     */
    private void saveLongTermMemory() {
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (MemoryEntry entry : ltmEntries) {
                JSONObject obj = new JSONObject();
                obj.put("key", entry.key());
                obj.put("value", entry.value());
                obj.put("createdAt", entry.createdAt());
                obj.put("updatedAt", entry.updatedAt());
                arr.add(obj);
            }
            root.put("preferences", arr);
            Files.writeString(Paths.get(LTM_FILE_PATH), JSON.toJSONString(root, true));
            logger.debug("长期记忆已保存，共 {} 条", ltmEntries.size());
        } catch (IOException e) {
            logger.error("保存长期记忆失败：{}", e.getMessage(), e);
        }
    }

    /**
     * 添加或更新一条记忆。key 相同则更新 value 和 updatedAt。
     */
    public synchronized String addOrUpdateMemory(String key, String value) {
        if (key == null || key.isBlank()) return "key 不能为空";
        if (value == null || value.isBlank()) return "value 不能为空";

        String now = LocalDateTime.now().format(LTM_DT_FMT);
        for (int i = 0; i < ltmEntries.size(); i++) {
            if (ltmEntries.get(i).key().equals(key)) {
                ltmEntries.set(i, new MemoryEntry(key, value, ltmEntries.get(i).createdAt(), now));
                saveLongTermMemory();
                return "已更新记忆：" + key;
            }
        }
        ltmEntries.add(new MemoryEntry(key, value, now, now));
        saveLongTermMemory();
        return "已保存新记忆：" + key;
    }

    /**
     * 删除指定 key 的记忆
     */
    public synchronized String removeMemory(String key) {
        boolean removed = ltmEntries.removeIf(e -> e.key().equals(key));
        if (removed) {
            saveLongTermMemory();
            return "已删除记忆：" + key;
        }
        return "未找到记忆：" + key;
    }

    /**
     * 获取所有记忆条目（只读副本）
     */
    public synchronized List<MemoryEntry> getAllMemory() {
        return new ArrayList<>(ltmEntries);
    }

    /**
     * 清空所有长期记忆
     */
    public synchronized void clearAllMemory() {
        ltmEntries.clear();
        saveLongTermMemory();
    }

    /**
     * 将所有记忆格式化为可注入 system prompt 的文本段。
     * 若无记忆则返回空字符串。
     */
    public synchronized String toLongTermPromptSection() {
        if (ltmEntries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## User Preferences (长期记忆)\n");
        sb.append("以下是用户在之前会话中表达过的偏好，请在回复中参考：\n");
        for (MemoryEntry entry : ltmEntries) {
            sb.append("- **").append(entry.key()).append("**: ").append(entry.value()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 暂存一轮对话（用户输入 + 助手回复），不立即提取。
     */
    public synchronized void appendLtmRound(String userInput, String assistantReply) {
        ltmPendingRounds.add("用户: " + userInput + "\n助手: " + assistantReply);
        logger.debug("暂存对话轮次，当前待提取轮数：{}", ltmPendingRounds.size());
    }

    /**
     * 将暂存的多轮对话一次性提交给模型分析，异步提取偏好并保存。
     */
    public void flushAndExtractLtmAsync(CommandContext ctx) {
        List<String> rounds = takePendingLtmRounds();
        if (rounds == null) return;
        Thread t = new Thread(() -> doExtractLtm(rounds, ctx), "LongTermMemory-Extract");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 同步版本：退出时调用，会阻塞直到提取完成。
     */
    public void flushAndExtractLtmSync(CommandContext ctx) {
        List<String> rounds = takePendingLtmRounds();
        if (rounds == null) return;
        doExtractLtm(rounds, ctx);
    }

    private synchronized List<String> takePendingLtmRounds() {
        if (ltmPendingRounds.isEmpty()) return null;
        List<String> rounds = new ArrayList<>(ltmPendingRounds);
        ltmPendingRounds.clear();
        return rounds;
    }

    private void doExtractLtm(List<String> rounds, CommandContext ctx) {
        try {
            String existingMemory = toExistingLtmMemoryText();
            String prompt = String.format(SystemPrompt.EXTRACT_MEMORY_PROMPT, existingMemory);
            String conversation = String.join("\n---\n", rounds);
            List<Message> messages = new ArrayList<>();
            messages.add(Message.fromSystem(prompt));
            messages.add(Message.fromUser(conversation));
            StringBuilder result = new StringBuilder();
            ctx.getLlmClient().chat(ctx, messages, result::append);
            String response = result.toString().trim();
            logger.debug("记忆提取模型返回：{}", response);
            parseAndSaveLtmMemories(response);
        } catch (Exception e) {
            logger.error("提取记忆失败：{}", e.getMessage(), e);
        }
    }

    private synchronized String toExistingLtmMemoryText() {
        if (ltmEntries.isEmpty()) return "（暂无）";
        StringBuilder sb = new StringBuilder();
        for (MemoryEntry entry : ltmEntries) {
            sb.append("- ").append(entry.key()).append(": ").append(entry.value()).append("\n");
        }
        return sb.toString();
    }

    private void parseAndSaveLtmMemories(String response) {
        String json = response.trim();
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
                    String result = addOrUpdateMemory(key, value);
                    logger.info("自动提取记忆：{}", result);
                }
            }
        } catch (Exception e) {
            logger.warn("解析记忆提取结果失败：{}", e.getMessage());
        }
    }}
