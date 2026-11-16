package com.jiyingda.codly.core;

import com.alibaba.fastjson.JSON;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 确认分级使用有限命令语法；不能识别的 shell 语法一律交给用户批准。 */
public enum ExecutionPermission {
    READ_ONLY("只读查询"), PROJECT("项目内修改 / 构建测试"), SENSITIVE("敏感或未识别操作");

    private final String label;
    ExecutionPermission(String label) { this.label = label; }
    public String label() { return label; }

    public static ExecutionPermission classify(String tool, String arguments, Path workspace) {
        try {
            var args = JSON.parseObject(arguments);
            if (args == null) return SENSITIVE;
            if("task_stop".equals(tool)&&args.getString("id")!=null&&args.getString("id").matches("[a-f0-9]{32}"))return PROJECT;
            if ("write_file".equals(tool) || "edit_file".equals(tool)) {
                Object path = args.get("filePath");
                return path instanceof String s && projectPath(s, workspace, true) ? PROJECT : SENSITIVE;
            }
            if ("Bash".equals(tool)) {
                Object command = args.get("command");
                return command instanceof String s ? shell(s, workspace) : SENSITIVE;
            }
        } catch (Exception ignored) { }
        return SENSITIVE;
    }

    static boolean projectPath(String value, Path workspace, boolean protectConfiguration) {
        try {
            if (value.isBlank() || value.startsWith("~")) return false;
            Path root = workspace.toAbsolutePath().normalize();
            Path target = root.resolve(value.trim()).normalize();
            if (!target.startsWith(root)) return false;
            Path current = root;
            Path realRoot = root.toRealPath();
            for (Path segment : root.relativize(target)) {
                if (protectConfiguration && Set.of(".git", ".codly", ".codex", ".agents").contains(segment.toString())) return false;
                current = current.resolve(segment);
                if (Files.isSymbolicLink(current)) return false;
                if (Files.exists(current) && !current.toRealPath().startsWith(realRoot)) return false;
            }
            return !protectConfiguration || !target.equals(root) && (!Files.exists(target) || Files.isRegularFile(target));
        } catch (Exception ignored) { return false; }
    }

    private static ExecutionPermission shell(String command, Path workspace) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        char quote = 0;
        boolean started = false;
        ExecutionPermission result = READ_ONLY;
        Path directory = workspace;
        boolean pipeline = false;
        boolean processed = false;
        boolean trailingSeparator = false;
        for (int i = 0; i <= command.length(); i++) {
            char c = i == command.length() ? '\0' : command.charAt(i);
            // 不解释展开、重定向、子 shell、后台和转义语法。
            if (c == '$' || c == '`' || c == '\\' || c == '\0' && i < command.length()) return SENSITIVE;
            if (quote != 0) {
                if (c == '\0') return SENSITIVE;
                if (c == quote) quote = 0; else word.append(c);
                continue;
            }
            if (c == '\'' || c == '"') { quote = c; started = true; continue; }
            if (c == '>' || c == '<') {
                if (started && !Set.of("0", "1", "2").contains(word.toString())) words.add(word.toString());
                word.setLength(0); started = false;
                boolean output = c == '>';
                int next = i + 1;
                if (next < command.length() && command.charAt(next) == c) {
                    if (!output) return SENSITIVE;
                    next++;
                }
                if (output && next + 1 < command.length() && command.charAt(next) == '&'
                        && "12".indexOf(command.charAt(next + 1)) >= 0
                        && (next + 2 == command.length() || " \t\n;|&".indexOf(command.charAt(next + 2)) >= 0)) {
                    i = next + 1;
                    continue;
                }
                Redirect redirect = redirect(command, next);
                if (redirect == null) return SENSITIVE;
                String target = redirect.path();
                boolean permitted = "/dev/null".equals(target) || (output ? ordinaryProjectPath(target, directory) : projectPath(target, directory, false))
                        || temporaryPath(target, directory);
                if (!permitted) return SENSITIVE;
                if (output && !"/dev/null".equals(target)) result = PROJECT;
                i = redirect.end() - 1;
                continue;
            }
            boolean chain = c == '&' && i + 1 < command.length() && command.charAt(i + 1) == '&';
            boolean pipe = c == '|' && (i + 1 == command.length() || command.charAt(i + 1) != '|');
            boolean separator = c == ';' || c == '\n' || c == '\r';
            if (c == '\0' || Character.isWhitespace(c) || chain || pipe || separator) {
                if (started) { words.add(word.toString()); word.setLength(0); started = false; }
                if (c == '\0' || chain || pipe || separator) {
                    if (words.isEmpty() && processed && trailingSeparator && (separator || c == '\0')) continue;
                    ExecutionPermission part = simple(words, directory);
                    if (part == SENSITIVE) return SENSITIVE;
                    if ((pipe || pipeline) && "cd".equals(words.get(0))) return SENSITIVE;
                    if (part == PROJECT) result = PROJECT;
                    if (!words.isEmpty() && "cd".equals(words.get(0))) directory = directory.resolve(words.get(1)).normalize();
                    words.clear();
                    pipeline = pipe;
                    processed = true;
                    trailingSeparator = separator;
                    if (chain) i++;
                }
                continue;
            }
            if (";|&<>(){}*?[]#!".indexOf(c) >= 0) return SENSITIVE;
            word.append(c); started = true;
        }
        return result;
    }

    private static ExecutionPermission simple(List<String> words, Path workspace) {
        if (words.isEmpty()) return SENSITIVE;
        String executable = program(words.get(0));
        List<String> args = words.subList(1, words.size());
        // 参数中的路径和符号链接也要检查；不能用绝对可执行文件或环境赋值绕过。
        for (String arg : args) {
            if (arg.startsWith("-") && arg.contains("=")) {
                String value = arg.substring(arg.indexOf('=') + 1);
                if (!projectPath(value, workspace, false) && !temporaryPath(value, workspace)) return SENSITIVE;
            } else if (!arg.startsWith("-") && !projectPath(arg, workspace, false) && !temporaryPath(arg, workspace)) return SENSITIVE;
        }
        if ("cd".equals(executable)) return args.size() == 1 && Files.isDirectory(workspace.resolve(args.get(0))) ? READ_ONLY : SENSITIVE;
        if ("pwd".equals(executable)) return args.isEmpty() ? READ_ONLY : SENSITIVE;
        if (Set.of("ls", "cat", "head", "tail", "wc", "grep", "echo", "printf", "du", "file", "stat", "which", "whoami", "uname").contains(executable)) return READ_ONLY;
        if ("find".equals(executable)) {
            return args.stream().anyMatch(s -> Set.of("-L", "-exec", "-execdir", "-ok", "-okdir", "-delete", "-fprint", "-fprint0", "-fprintf", "-fls").contains(s)) ? SENSITIVE : READ_ONLY;
        }
        if ("sed".equals(executable)) {
            // 只放行常用的选行打印，避免 sed 的 e/w 等执行和写文件指令。
            return args.size() >= 2 && "-n".equals(args.get(0)) && args.get(1).matches("[0-9]+(,[0-9$]+)?p")
                    && args.subList(2, args.size()).stream().noneMatch(s -> s.startsWith("-")) ? READ_ONLY : SENSITIVE;
        }
        if ("rg".equals(executable)) {
            return args.stream().anyMatch(s -> s.startsWith("--pre") || s.startsWith("--hostname-bin") || s.startsWith("-") && !s.startsWith("--") && s.contains("z") || s.equals("--search-zip")) ? SENSITIVE : READ_ONLY;
        }
        if ("git".equals(executable) && !args.isEmpty()) {
            if (args.stream().anyMatch(s -> s.startsWith("--output") || s.startsWith("--ext-diff") || s.startsWith("--textconv") || s.startsWith("--no-index") || s.startsWith("--upload-pack") || s.startsWith("--receive-pack"))) return SENSITIVE;
            return switch (args.get(0)) {
                case "status", "diff", "log", "show", "rev-parse", "ls-files" -> READ_ONLY;
                case "branch" -> args.equals(List.of("branch", "--show-current")) ? READ_ONLY : SENSITIVE;
                case "add", "fetch", "pull", "switch" -> args.stream().anyMatch(s -> Set.of("-f", "--force", "--discard-changes", "--force-with-lease").contains(s)) ? SENSITIVE : PROJECT;
                default -> SENSITIVE;
            };
        }
        if (Set.of("mvn", "./mvnw").contains(executable)) {
            if (executable.startsWith("./") && !projectPath(executable, workspace, true)) return SENSITIVE;
            Set<String> goals = Set.of("clean", "compile", "test", "verify", "package", "validate", "test-compile", "install", "dependency:tree", "dependency:resolve", "dependency:get", "help:effective-pom", "help:evaluate", "compiler:compile", "surefire:test");
            return buildArguments(args, goals) ? PROJECT : SENSITIVE;
        }
        if (Set.of("gradle", "./gradlew").contains(executable)) {
            if (executable.startsWith("./") && !projectPath(executable, workspace, true)) return SENSITIVE;
            return buildArguments(args, Set.of("clean", "build", "test", "check", "assemble", "classes", "testClasses", "dependencies")) ? PROJECT : SENSITIVE;
        }
        if (Set.of("npm", "pnpm", "yarn").contains(executable)) {
            if (args.stream().anyMatch(s -> Set.of("-g", "--global", "link", "publish", "--prefix", "--cwd", "--dir", "-C").contains(s) || s.startsWith("--prefix=") || s.startsWith("--cwd=") || s.startsWith("--dir="))) return SENSITIVE;
            List<String> script = !args.isEmpty() && "run".equals(args.get(0)) ? args.subList(1, args.size()) : args;
            return !script.isEmpty() && Set.of("test", "build", "lint", "typecheck", "check", "install", "ci", "add").contains(script.get(0)) ? PROJECT : SENSITIVE;
        }
        if (Set.of("python", "python3").contains(executable)) {
            if (args.size() >= 2 && args.get(0).equals("-m") && Set.of("pytest", "unittest", "compileall").contains(args.get(1))) return PROJECT;
            return projectScript(args, workspace) ? PROJECT : SENSITIVE;
        }
        if (Set.of("bash", "sh", "node").contains(executable)) return projectScript(args, workspace) ? PROJECT : SENSITIVE;
        if ("pytest".equals(executable)) return PROJECT;
        if (Set.of("mkdir", "tee", "touch").contains(executable)) {
            return !args.isEmpty() && args.stream().filter(s -> !s.startsWith("-")).allMatch(s -> ordinaryProjectPath(s, workspace) || temporaryPath(s, workspace)) ? PROJECT : SENSITIVE;
        }
        if (Set.of("cp", "mv").contains(executable)) {
            if (args.stream().filter(s -> s.startsWith("-")).anyMatch(s -> !Set.of("-p", "-n", "-f", "-v", "--").contains(s))) return SENSITIVE;
            return args.stream().filter(s -> !s.startsWith("-")).count() >= 2
                    && args.stream().filter(s -> !s.startsWith("-")).allMatch(s -> ordinaryProjectPath(s, workspace) || temporaryPath(s, workspace)) ? PROJECT : SENSITIVE;
        }
        return SENSITIVE;
    }

    private static boolean projectScript(List<String> args, Path workspace) {
        return !args.isEmpty() && !args.get(0).startsWith("-") && Files.isRegularFile(workspace.resolve(args.get(0)))
                && (ordinaryProjectPath(args.get(0), workspace) || temporaryPath(args.get(0), workspace));
    }

    private static boolean temporaryPath(String value, Path workspace) {
        try {
            if (!Path.of(value).isAbsolute()) return false;
            Path target = workspace.resolve(value).toAbsolutePath().normalize();
            if (target.startsWith(workspace.toAbsolutePath().normalize())) return false;
            Path existing = target;
            while (existing != null && !Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
            if (existing == null) return false;
            Path resolved = existing.toRealPath().resolve(existing.relativize(target)).normalize();
            return resolved.startsWith(Path.of("/tmp").toRealPath())
                    || resolved.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath());
        } catch (Exception ignored) { return false; }
    }

    private record Redirect(String path, int end) {}

    private static Redirect redirect(String command, int start) {
        int i = start;
        while (i < command.length() && " \t".indexOf(command.charAt(i)) >= 0) i++;
        StringBuilder path = new StringBuilder();
        char quote = 0;
        for (; i < command.length(); i++) {
            char c = command.charAt(i);
            if ("$`\\".indexOf(c) >= 0) return null;
            if (quote != 0) { if (c == quote) quote = 0; else path.append(c); }
            else if (c == '\'' || c == '"') quote = c;
            else if (Character.isWhitespace(c) || ";|&<>".indexOf(c) >= 0) break;
            else if ("(){}*?[]#!".indexOf(c) >= 0) return null;
            else path.append(c);
        }
        return quote == 0 && path.length() > 0 ? new Redirect(path.toString(), i) : null;
    }

    private static boolean ordinaryProjectPath(String value, Path workspace) {
        if (!projectPath(value, workspace, false)) return false;
        for (Path segment : workspace.toAbsolutePath().normalize().relativize(workspace.resolve(value).toAbsolutePath().normalize())) {
            if (Set.of(".git", ".codly", ".codex", ".agents").contains(segment.toString())) return false;
        }
        return true;
    }

    private static boolean buildArguments(List<String> args, Set<String> goals) {
        boolean hasGoal = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (goals.contains(arg)) { hasGoal = true; continue; }
            if (arg.startsWith("-D") || arg.startsWith("-P")) continue;
            if (Set.of("-o", "--offline", "-q", "--quiet", "-B", "--batch-mode", "-U", "--update-snapshots", "-e", "-X", "-am", "-amd", "--also-make", "--also-make-dependents", "--no-daemon", "--console=plain", "--info", "--stacktrace").contains(arg)) continue;
            if (Set.of("-pl", "--projects", "-T", "--threads", "-f", "--file").contains(arg) && i + 1 < args.size()) { i++; continue; }
            return false;
        }
        return hasGoal;
    }

    static String program(String executable) {
        if (executable.startsWith("/usr/bin/") || executable.startsWith("/bin/") || executable.startsWith("/opt/homebrew/bin/")) {
            Path path = Path.of(executable);
            if (path.getParent().toString().equals("/usr/bin") || path.getParent().toString().equals("/bin") || path.getParent().toString().equals("/opt/homebrew/bin")) return path.getFileName().toString();
        }
        return executable;
    }

    /** 只为无组合操作的命令生成授权范围；复杂语法使用精确匹配。 */
    static List<String> simpleWords(String command) {
        List<String> result = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i <= command.length(); i++) {
            char c = i == command.length() ? '\0' : command.charAt(i);
            if ("$`\\\n\r".indexOf(c) >= 0) return null;
            if (quote != 0) {
                if (c == '\0') return null;
                if (c == quote) quote = 0; else word.append(c);
            } else if (c == '\'' || c == '"') { quote = c; started = true; }
            else if (c == '\0' || Character.isWhitespace(c)) {
                if (started) { result.add(word.toString()); word.setLength(0); started = false; }
            } else {
                if (";|&<>(){}*?[]#!".indexOf(c) >= 0) return null;
                word.append(c); started = true;
            }
        }
        return result;
    }
}
