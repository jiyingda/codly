package com.jiyingda.codly.util;

/**
 * 终端 Markdown 流式渲染器：检测代码块、行内代码、标题、粗体等，
 * 并输出 ANSI 着色后的文本。
 * <p>
 * 设计为逐 token 喂入（append），内部缓冲处理边界情况。
 */
public class MarkdownRenderer {

    // ANSI 颜色常量
    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String CYAN = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String MAGENTA = "\033[35m";
    private static final String GRAY = "\033[90m";

    // 代码块状态
    private boolean inCodeBlock = false;
    private String codeBlockLang = "";

    // 缓冲区，逐行处理
    private final StringBuilder lineBuffer = new StringBuilder();
    private final StringBuilder outputBuffer = new StringBuilder();

    /**
     * 喂入一个 token，返回可以立即输出的渲染文本。
     * <p>
     * 核心策略：
     * - 代码块内：可以逐 token 输出（着色不依赖完整行语义）
     * - 非代码块：必须缓冲到完整行（收到 \n），否则表格/粗体/行内代码会被拆断
     */
    public String process(String token) {
        outputBuffer.setLength(0);
        lineBuffer.append(token);

        // 逐行处理：只输出包含完整换行符的行
        while (true) {
            int newlineIdx = lineBuffer.indexOf("\n");
            if (newlineIdx == -1) {
                // 没有完整行
                if (inCodeBlock && !lineBuffer.isEmpty() && !isPossibleFencePrefix(lineBuffer.toString())) {
                    // 代码块内可以安全地逐 token 输出
                    String partial = lineBuffer.toString();
                    lineBuffer.setLength(0);
                    outputBuffer.append(CYAN).append(syntaxHighlight(partial, codeBlockLang)).append(RESET);
                }
                // 非代码块：保持缓冲，等待换行符到来
                break;
            }
            // 取出完整行
            String line = lineBuffer.substring(0, newlineIdx);
            lineBuffer.delete(0, newlineIdx + 1);
            outputBuffer.append(renderLine(line));
            outputBuffer.append("\n");
        }
        return outputBuffer.toString();
    }

    /**
     * flush：处理缓冲区中剩余文本（流结束时调用）。
     */
    public String flush() {
        if (lineBuffer.isEmpty()) {
            return inCodeBlock ? RESET : "";
        }
        String remaining = lineBuffer.toString();
        lineBuffer.setLength(0);
        // 作为最后一行渲染
        String result;
        if (inCodeBlock) {
            result = CYAN + "│ " + syntaxHighlight(remaining, codeBlockLang) + RESET;
        } else {
            result = renderMarkdownLine(remaining);
        }
        return result;
    }

    private String renderLine(String line) {
        // 检测代码块开始/结束
        String trimmed = line.trim();
        if (trimmed.startsWith("```")) {
            if (!inCodeBlock) {
                // 进入代码块
                inCodeBlock = true;
                codeBlockLang = trimmed.length() > 3 ? trimmed.substring(3).trim() : "";
                return DIM + GRAY + "┌─" + (codeBlockLang.isEmpty() ? "" : " " + codeBlockLang + " ") + "─" + RESET;
            } else {
                // 退出代码块
                inCodeBlock = false;
                codeBlockLang = "";
                return DIM + GRAY + "└─────" + RESET;
            }
        }

        if (inCodeBlock) {
            return renderCodeLine(line);
        }

        return renderMarkdownLine(line);
    }

    private String renderCodeLine(String line) {
        // 代码行：浅灰背景 + 语法着色
        return CYAN + "│ " + syntaxHighlight(line, codeBlockLang) + RESET;
    }

    private String renderMarkdownLine(String line) {
        // 标题
        if (line.startsWith("### ")) {
            return BOLD + MAGENTA + line + RESET;
        }
        if (line.startsWith("## ")) {
            return BOLD + YELLOW + line + RESET;
        }
        if (line.startsWith("# ")) {
            return BOLD + GREEN + line + RESET;
        }
        // 表格分隔线 (|---|---|)
        if (line.matches("^\\|?[\\s:]*-[-\\s:|]+$")) {
            return DIM + line + RESET;
        }
        // 表格行 (| cell | cell |)
        if (line.contains("|") && (line.startsWith("|") || line.trim().startsWith("|"))) {
            return renderTableRow(line);
        }
        // 列表项
        if (line.matches("^\\s*[-*+] .*")) {
            return renderInline(line);
        }
        // 有序列表
        if (line.matches("^\\s*\\d+\\. .*")) {
            return renderInline(line);
        }
        // 分隔线
        if (line.matches("^---+$") || line.matches("^\\*\\*\\*+$")) {
            return DIM + "─".repeat(40) + RESET;
        }
        // 引用块
        if (line.startsWith("> ")) {
            return DIM + GRAY + "▌ " + RESET + renderInline(line.substring(2));
        }
        // 普通行：处理行内格式
        return renderInline(line);
    }

    private String renderTableRow(String line) {
        StringBuilder sb = new StringBuilder();
        String[] cells = line.split("\\|", -1);
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(DIM).append("│").append(RESET);
            }
            String cell = cells[i].trim();
            if (!cell.isEmpty()) {
                sb.append(" ").append(renderInline(cell)).append(" ");
            }
        }
        return sb.toString();
    }

    private String renderInline(String text) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            // 行内代码 `...`
            if (text.charAt(i) == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i) {
                    sb.append(CYAN).append(text, i, end + 1).append(RESET);
                    i = end + 1;
                    continue;
                }
            }
            // 粗体 **...**
            if (i + 1 < text.length() && text.charAt(i) == '*' && text.charAt(i + 1) == '*') {
                int end = text.indexOf("**", i + 2);
                if (end > i) {
                    sb.append(BOLD).append(text, i + 2, end).append(RESET);
                    i = end + 2;
                    continue;
                }
            }
            sb.append(text.charAt(i));
            i++;
        }
        return sb.toString();
    }

    /**
     * 基本语法高亮（关键字着色）
     */
    private String syntaxHighlight(String line, String lang) {
        if (lang == null || lang.isEmpty()) {
            return GREEN + line;
        }
        // 通用关键字着色
        return switch (lang.toLowerCase()) {
            case "java", "kotlin", "scala" -> highlightJavaLike(line);
            case "python", "py" -> highlightPython(line);
            case "javascript", "js", "typescript", "ts" -> highlightJs(line);
            case "bash", "sh", "shell", "zsh" -> highlightBash(line);
            case "json" -> highlightJson(line);
            case "xml", "html" -> highlightXml(line);
            case "sql" -> highlightSql(line);
            default -> GREEN + line;
        };
    }

    private String highlightJavaLike(String line) {
        String trimmed = line.stripLeading();
        String indent = line.substring(0, line.length() - trimmed.length());
        // 注释
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
            return indent + GRAY + trimmed;
        }
        // 注解
        if (trimmed.startsWith("@")) {
            return indent + YELLOW + trimmed;
        }
        // 关键字
        String highlighted = trimmed
                .replaceAll("\\b(public|private|protected|class|interface|enum|extends|implements|static|final|void|return|new|import|package|if|else|for|while|try|catch|throw|throws|this|super|null|true|false|int|long|boolean|double|float|String|var)\\b",
                        MAGENTA + "$1" + GREEN);
        return indent + GREEN + highlighted;
    }

    private String highlightPython(String line) {
        String trimmed = line.stripLeading();
        String indent = line.substring(0, line.length() - trimmed.length());
        if (trimmed.startsWith("#")) {
            return indent + GRAY + trimmed;
        }
        String highlighted = trimmed
                .replaceAll("\\b(def|class|import|from|return|if|elif|else|for|while|try|except|finally|with|as|yield|lambda|None|True|False|self|in|not|and|or|is|pass|break|continue|raise)\\b",
                        MAGENTA + "$1" + GREEN);
        return indent + GREEN + highlighted;
    }

    private String highlightJs(String line) {
        String trimmed = line.stripLeading();
        String indent = line.substring(0, line.length() - trimmed.length());
        if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
            return indent + GRAY + trimmed;
        }
        String highlighted = trimmed
                .replaceAll("\\b(const|let|var|function|return|if|else|for|while|class|export|import|from|new|this|null|undefined|true|false|async|await|try|catch|throw|typeof|instanceof)\\b",
                        MAGENTA + "$1" + GREEN);
        return indent + GREEN + highlighted;
    }

    private String highlightBash(String line) {
        String trimmed = line.stripLeading();
        String indent = line.substring(0, line.length() - trimmed.length());
        if (trimmed.startsWith("#")) {
            return indent + GRAY + trimmed;
        }
        return indent + YELLOW + trimmed;
    }

    private String highlightJson(String line) {
        // JSON: keys in cyan, values in green
        String result = line.replaceAll("\"([^\"]+)\"\\s*:", CYAN + "\"$1\"" + RESET + ":");
        return GREEN + result;
    }

    private String highlightXml(String line) {
        return YELLOW + line;
    }

    private String highlightSql(String line) {
        String trimmed = line.stripLeading();
        String indent = line.substring(0, line.length() - trimmed.length());
        if (trimmed.startsWith("--")) {
            return indent + GRAY + trimmed;
        }
        String highlighted = trimmed
                .replaceAll("(?i)\\b(SELECT|FROM|WHERE|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|TABLE|INDEX|JOIN|LEFT|RIGHT|INNER|OUTER|ON|AND|OR|NOT|NULL|AS|ORDER|BY|GROUP|HAVING|LIMIT|INTO|VALUES|SET)\\b",
                        MAGENTA + "$1" + GREEN);
        return indent + GREEN + highlighted;
    }


    /**
     * 检查文本是否可能是代码围栏(```)的前缀，避免提前输出。
     */
    private boolean isPossibleFencePrefix(String text) {
        String trimmed = text.trim();
        return trimmed.equals("`") || trimmed.equals("``") || trimmed.equals("```");
    }
}

