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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
 * sessionMessages 作为临时缓存，生成标题后写入磁盘并清空
 */
public class MemoryManager {

    private static final Logger logger = LoggerFactory.getLogger(MemoryManager.class);

    private static final String BASE_PATH = System.getProperty("user.home") + "/.codly/memory/session";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private static MemoryManager instance;
    private final ExecutorService asyncExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MemoryManager-Async");
        t.setDaemon(true);
        return t;
    });

    private String currentSessionFile;
    private List<Message> sessionMessages = new ArrayList<>();
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
        logger.info("缓存消息数量：{}", sessionMessages.size());

        // 将缓存中的消息写入文件
        flushMessagesToFile();

        fileInitialized = true;
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
     * 添加消息到临时缓存
     */
    public synchronized void appendMessageAsync(Message message) {
        sessionMessages.add(message);
        logger.debug("消息添加到缓存：{}, 缓存大小：{}, 文件已初始化：{}", message.getRole(), sessionMessages.size(), fileInitialized);

        // 如果文件已初始化，直接异步写入
        if (fileInitialized) {
            logger.debug("文件已初始化，异步写入单条消息");
            flushSingleMessageAsync(message);
            sessionMessages.clear(); // 清空缓存
        }
        // 如果文件未初始化，消息保留在缓存中，等待 initializeWithSession 调用时一起写入
    }

    /**
     * 将缓存中的消息批量写入文件
     */
    private void flushMessagesToFile() {
        logger.debug("正在将缓存中的消息写入文件... size: {}", sessionMessages.size());
        if (sessionMessages.isEmpty()) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            synchronized (this) {
                try {
                    StringBuilder sb = new StringBuilder();
                    for (Message message : sessionMessages) {
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
                    Files.writeString(Paths.get(currentSessionFile), sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    logger.info("消息写入成功：{}", currentSessionFile);
                    sessionMessages.clear(); // 写入后清空缓存
                } catch (IOException e) {
                    logger.error("写入文件失败：{}", e.getMessage(), e);
                }
            }
        }, asyncExecutor);
    }

    /**
     * 异步写入单条消息到文件
     */
    private void flushSingleMessageAsync(Message message) {
        CompletableFuture.runAsync(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("role", message.getRole());
                json.put("content", message.getContent());
                if (message.getTool_calls() != null) {
                    json.put("tool_calls", JSON.toJSON(message.getTool_calls()));
                }
                if (message.getTool_call_id() != null) {
                    json.put("tool_call_id", message.getTool_call_id());
                }
                String jsonLine = json.toJSONString() + "\n";
                Files.writeString(Paths.get(currentSessionFile), jsonLine, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                logger.error("写入单条消息失败：{}", e.getMessage(), e);
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