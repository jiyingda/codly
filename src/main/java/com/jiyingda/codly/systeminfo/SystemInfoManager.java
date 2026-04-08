/**
 * @(#)SystemInfoManager.java, 4 月 7, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly.systeminfo;

import com.alibaba.fastjson.JSON;
import com.jiyingda.codly.config.Config;
import org.jline.terminal.Terminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 系统信息管理器，负责采集和提供系统环境、运行状态、项目信息等。
 */
public class SystemInfoManager {

    private static final Logger logger = LoggerFactory.getLogger(SystemInfoManager.class);

    private static SystemInfoManager instance;

    private Terminal terminal;
    private SystemInfo cachedInfo;

    private SystemInfoManager() {
    }

    public static SystemInfoManager getInstance() {
        if (instance == null) {
            instance = new SystemInfoManager();
        }
        return instance;
    }

    /**
     * 注入 JLine Terminal 实例，用于获取准确的终端尺寸。
     */
    public void setTerminal(Terminal terminal) {
        this.terminal = terminal;
        this.cachedInfo = null;
    }

    /**
     * 获取系统信息（惰性求值 + 缓存）。
     */
    public SystemInfo getSystemInfo() {
        if (cachedInfo == null) {
            cachedInfo = collectAll();
        }
        return cachedInfo;
    }

    /**
     * 强制重新采集全部系统信息。
     */
    public SystemInfo refresh() {
        cachedInfo = null;
        return getSystemInfo();
    }

    /**
     * 格式化为可读文本报告。
     */
    public String formatAsText() {
        SystemInfo info = getSystemInfo();
        return String.format(
            "## System Information\n\n" +
            "**OS:** %s %s (%s) | **Host:** %s | **User:** %s\n" +
            "**Terminal:** %s (%s) %dx%d\n" +
            "**Java:** %s (%s) | **Heap:** %s / %s | **NonHeap:** %s\n" +
            "**Git:** %s @ %s (%s) | **Status:** %s\n" +
            "**Maven:** %s | **Project:** %s | **Dependencies:** %d\n" +
            "**Time:** %s\n" +
            "**Project:** %s | **Config:** %s\n",
            info.osName(), info.osVersion(), info.osArch(),
            info.hostname(), info.username(),
            info.terminalType(), info.shellName(), info.terminalColumns(), info.terminalRows(),
            info.javaVersion(), info.javaVendor(),
            formatBytes(info.jvmHeapUsed()), formatBytes(info.jvmHeapMax()), formatBytes(info.jvmNonHeapUsed()),
            info.gitBranch(), info.gitCommitHash(), info.gitRemoteUrl(),
            info.gitStatusSummary(),
            info.mavenVersion(), info.projectVersion(), info.dependencyCount(),
            info.timestamp(),
            info.projectPath(), info.configFilePath()
        );
    }

    public String currentTime() {
        return String.format("## Time: %s", collectTimestamp());
    }

    /**
     * 格式化为 JSON 字符串。
     */
    public String formatAsJson() {
        return JSON.toJSONString(getSystemInfo(), true);
    }

    // ---- 内部采集方法 ----

    private SystemInfo collectAll() {
        Path projectPath = Paths.get("").toAbsolutePath().normalize();
        return new SystemInfo(
                collectOsName(), collectOsVersion(), collectOsArch(),
                collectHostname(), collectUsername(),
                collectTerminalType(), collectShellName(),
                collectTerminalRows(), collectTerminalColumns(),
                collectJavaVersion(), collectJavaVendor(), collectJavaRuntimeName(),
                collectJvmHeapMax(), collectJvmHeapUsed(), collectJvmNonHeapUsed(),
                collectGitBranch(projectPath), collectGITCommitHash(projectPath),
                collectGITRemoteUrl(projectPath), collectGITStatusSummary(projectPath),
                collectMavenVersion(), collectProjectVersion(projectPath),
                collectDependencyCount(projectPath),
                collectTimestamp(),
                projectPath.toString(),
                System.getProperty("user.dir"),
                Config.getConfigPath()
        );
    }

    private String collectOsName() {
        return System.getProperty("os.name", "N/A");
    }

    private String collectOsVersion() {
        return System.getProperty("os.version", "N/A");
    }

    private String collectOsArch() {
        return System.getProperty("os.arch", "N/A");
    }

    private String collectHostname() {
        try {
            String result = runCommand(new String[]{"hostname"}, 3);
            if (result != null && !result.isEmpty()) return result;
        } catch (Exception ignored) {}
        try {
            String env = System.getenv("HOSTNAME");
            if (env != null && !env.isEmpty()) return env;
        } catch (Exception ignored) {}
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "N/A";
        }
    }

    private String collectUsername() {
        return System.getProperty("user.name", "N/A");
    }

    private String collectTerminalType() {
        if (terminal != null) {
            return terminal.getType();
        }
        String env = System.getenv("TERM");
        return env != null ? env : "N/A";
    }

    private String collectShellName() {
        String shell = System.getenv("SHELL");
        if (shell != null && !shell.isEmpty()) {
            return shell.substring(shell.lastIndexOf('/') + 1);
        }
        return "N/A";
    }

    private int collectTerminalRows() {
        if (terminal != null) {
            return terminal.getHeight();
        }
        try {
            String lines = System.getenv("LINES");
            if (lines != null) return Integer.parseInt(lines);
        } catch (Exception ignored) {}
        return 24;
    }

    private int collectTerminalColumns() {
        if (terminal != null) {
            return terminal.getWidth();
        }
        try {
            String cols = System.getenv("COLUMNS");
            if (cols != null) return Integer.parseInt(cols);
        } catch (Exception ignored) {}
        return 80;
    }

    private String collectJavaVersion() {
        return System.getProperty("java.version", "N/A");
    }

    private String collectJavaVendor() {
        return System.getProperty("java.vendor", "N/A");
    }

    private String collectJavaRuntimeName() {
        return System.getProperty("java.runtime.name", "N/A");
    }

    private long collectJvmHeapMax() {
        return Runtime.getRuntime().maxMemory();
    }

    private long collectJvmHeapUsed() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private long collectJvmNonHeapUsed() {
        try {
            MemoryUsage usage = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
            return usage.getUsed();
        } catch (Exception e) {
            return -1;
        }
    }

    private String collectGitBranch(Path projectPath) {
        String result = runCommand(new String[]{"git", "-C", projectPath.toString(), "symbolic-ref", "--short", "HEAD"}, 5);
        if (result != null && !result.isEmpty()) return result;
        result = runCommand(new String[]{"git", "-C", projectPath.toString(), "rev-parse", "--abbrev-ref", "HEAD"}, 5);
        return result != null && !result.isEmpty() ? result : "N/A";
    }

    private String collectGITCommitHash(Path projectPath) {
        String result = runCommand(new String[]{"git", "-C", projectPath.toString(), "rev-parse", "--short", "HEAD"}, 5);
        return result != null && !result.isEmpty() ? result : "N/A";
    }

    private String collectGITRemoteUrl(Path projectPath) {
        String result = runCommand(new String[]{"git", "-C", projectPath.toString(), "remote", "get-url", "origin"}, 5);
        if (result != null && !result.isEmpty()) return result;
        result = runCommand(new String[]{"git", "-C", projectPath.toString(), "config", "--get", "remote.origin.url"}, 5);
        return result != null && !result.isEmpty() ? result : "N/A";
    }

    private String collectGITStatusSummary(Path projectPath) {
        String result = runCommand(new String[]{"git", "-C", projectPath.toString(), "status", "--porcelain"}, 5);
        if (result == null || result.isEmpty()) return "clean";
        long added = result.lines().filter(line -> line.startsWith("A ")).count();
        long modified = result.lines().filter(line -> line.startsWith(" M") || line.startsWith("M ")).count();
        long deleted = result.lines().filter(line -> line.startsWith("D ") || line.startsWith(" D")).count();
        long untracked = result.lines().filter(line -> line.startsWith("??")).count();
        return String.format("%d added, %d modified, %d deleted, %d untracked", added, modified, deleted, untracked);
    }

    private String collectMavenVersion() {
        String result = runCommand(new String[]{"mvn", "--version"}, 5);
        if (result == null) return "N/A";
        String firstLine = result.lines().findFirst().orElse("");
        // 提取版本号，如 "Apache Maven 3.9.6" -> "3.9.6"
        Matcher m = Pattern.compile("Apache Maven\\s+([\\d.]+)").matcher(firstLine);
        return m.find() ? m.group(1) : "N/A";
    }

    private String collectProjectVersion(Path projectPath) {
        Path pomPath = projectPath.resolve("pom.xml");
        if (!Files.exists(pomPath)) return "N/A";
        try {
            String content = Files.readString(pomPath);
            // 匹配 <project> 直接子级的 <version>，跳过 <parent> 内的
            // 先提取 <project> 标签内容，再找第一个 <version>
            Pattern projectPattern = Pattern.compile("<project[\\s>][\\s\\S]*?</project>");
            Matcher projectMatcher = projectPattern.matcher(content);
            if (projectMatcher.find()) {
                String projectContent = projectMatcher.group();
                // 移除 <parent>...</parent> 块
                String withoutParent = projectContent.replaceAll("<parent>[\\s\\S]*?</parent>", "");
                Pattern versionPattern = Pattern.compile("<version>\\s*([^<]+)\\s*</version>");
                Matcher versionMatcher = versionPattern.matcher(withoutParent);
                if (versionMatcher.find()) {
                    return versionMatcher.group(1).trim();
                }
            }
        } catch (IOException e) {
            logger.debug("读取 pom.xml 失败: {}", e.getMessage());
        }
        return "N/A";
    }

    private int collectDependencyCount(Path projectPath) {
        Path pomPath = projectPath.resolve("pom.xml");
        if (!Files.exists(pomPath)) return 0;
        try {
            String content = Files.readString(pomPath);
            return (int) Pattern.compile("<dependency>\\s*<groupId>")
                    .matcher(content)
                    .results()
                    .count();
        } catch (IOException e) {
            logger.debug("读取 pom.xml 失败: {}", e.getMessage());
            return 0;
        }
    }

    private String collectTimestamp() {
        return ZonedDateTime.now(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z"));
    }

    /**
     * 执行命令并返回输出。
     */
    private String runCommand(String[] cmd, long timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroy();
                logger.debug("命令超时: {}", String.join(" ", cmd));
                return null;
            }
            return p.exitValue() == 0 ? output.toString().trim() : null;
        } catch (Exception e) {
            logger.debug("命令执行失败 {}: {}", String.join(" ", cmd), e.getMessage());
            return null;
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 0) return "N/A";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /**
     * 系统信息数据载体（Java 17 record）。
     */
    public record SystemInfo(
            String osName,
            String osVersion,
            String osArch,
            String hostname,
            String username,
            String terminalType,
            String shellName,
            int terminalRows,
            int terminalColumns,
            String javaVersion,
            String javaVendor,
            String javaRuntimeName,
            long jvmHeapMax,
            long jvmHeapUsed,
            long jvmNonHeapUsed,
            String gitBranch,
            String gitCommitHash,
            String gitRemoteUrl,
            String gitStatusSummary,
            String mavenVersion,
            String projectVersion,
            int dependencyCount,
            String timestamp,
            String projectPath,
            String workingDirectory,
            String configFilePath
    ) {}
}
