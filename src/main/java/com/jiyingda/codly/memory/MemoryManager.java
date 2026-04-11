package com.jiyingda.codly.memory;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.jiyingda.codly.data.Message;
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

    private MemoryManager() {
        // 初始时不创建文件，只初始化缓存
        File dir = new File(BASE_PATH);
        if (!dir.exists()) {
            dir.mkdirs();
        }
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
}