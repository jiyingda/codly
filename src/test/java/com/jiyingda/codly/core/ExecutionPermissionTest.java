package com.jiyingda.codly.core;

import com.alibaba.fastjson.JSON;
import com.jiyingda.codly.function.FunctionManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static com.jiyingda.codly.core.ExecutionPermission.*;
import static org.junit.Assert.*;

public class ExecutionPermissionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private String args(String command) { return JSON.toJSONString(Map.of("command", command)); }

    @Test public void readCommandsAndReadPipelinesDoNotPromptEvenInAskMode() throws Exception {
        Path root = temporary.newFolder().toPath();
        Files.createDirectories(root.resolve("src"));
        for (String command : new String[] {"pwd", "ls -la", "cat README.md", "head -n 20 README.md",
                "rg -n 'hello world' src", "rg -g '*.java' hello src | head -n 20", "git status --short",
                "git diff --stat", "git log -5 --oneline", "git branch --show-current", "cd src && pwd",
                "find src -name '*.java'", "sed -n '1,80p' README.md", "/bin/ls", "pwd; ls -la;", "pwd\nls -la\n", "cat < in", "ls 2>/dev/null"}) {
            assertEquals(command, READ_ONLY, classify("Bash", args(command), root));
            assertTrue(command, ApprovalPolicy.ASK.approves("Bash", args(command), root));
        }
        ConversationContext ctx = new ConversationContext(new ArrayList<>(), null, root);
        ctx.setConfirmation(summary -> { fail("read query must not prompt"); return false; });
        assertTrue(new FunctionManager().execute("Bash", args("pwd"), ctx).contains(root.toString()));
    }

    @Test public void buildsAreProjectLevelAndDependOnSelectedPolicy() throws Exception {
        Path root = temporary.newFolder().toPath();
        for (String command : new String[] {"mvn -o test", "mvn -q -DskipTests package", "./mvnw verify",
                "mvn '-Dtest=ExampleTest#method' test", "npm run build", "pnpm test", "pnpm run typecheck",
                "yarn lint", "git status --short && mvn test", "mvn -pl module -am -DskipTests package",
                "mvn -Dfile.encoding=UTF-8 -Dtest=ExampleTest test", "./gradlew build --no-daemon", "npm install", "mvn clean install",
                "pnpm add flatlaf", "npm run test -- --runInBand", "python3 -m pytest tests", "mkdir -p output", "echo result | tee result.txt",
                "mvn test | tail -n 20", "cat file | npm run build", "mvn test > build.log 2>&1", "cat README.md >> notes.txt",
                "touch notes.txt", "cp file.txt copy.txt", "mv copy.txt renamed.txt", "git add .", "git fetch origin", "git pull --ff-only", "mvn -f /tmp/pom.xml test"}) {
            assertEquals(command, PROJECT, classify("Bash", args(command), root));
            assertFalse(command, ApprovalPolicy.ASK.approves("Bash", args(command), root));
            assertTrue(command, ApprovalPolicy.ASSISTED.approves("Bash", args(command), root));
        }
    }

    @Test public void sensitiveAndUnrecognizedShellSyntaxAlwaysAskInAssistedMode() throws Exception {
        Path root = temporary.newFolder().toPath();
        Files.createDirectories(root.resolve("src"));
        for (String command : new String[] {"rm -rf src", "npm install -g pkg", "pnpm install --prefix=/tmp/out", "mvn deploy", "git push", "git reset --hard",
                "git config core.editor sh", "git diff --output=result.txt", "git show --textconv", "git -c diff.external=sh diff",
                "rg --pre=sh .", "rg -nz pattern", "rg --hostname-bin sh .", "find . -exec sh {} +", "sed -i x README.md",
                "pwd; rm file", "pwd && rm file", "pwd || rm file", "pwd &", "cat README.md > /etc/passwd", "cat < /etc/passwd",
                "ls $(touch marker)", "ls `touch marker`", "ls \"$HOME\"", "ls $'escape'", "ls\nrm file",
                "bash -c 'pwd'", "env sh -c pwd", "X=1 pwd", "cat ../file", "cat /etc/passwd", "ls ~",
                "ls *", "cat 'unterminated", "pwd &&", "", "cd src | pwd", "cd .. && pwd",
                "sed -n '1p' README.md -e '1e touch marker'", "sed -n '1p' README.md -i", "find . -delete",
                "tee .git/config", "mkdir -p ../outside", "mvn -f /etc/pom.xml test", "echo hi | sh",
                "echo secret > .git/config", "echo secret > ../outside", "git switch --force main", "git push origin main"}) {
            assertEquals(command, SENSITIVE, classify("Bash", args(command), root));
            assertFalse(command, ApprovalPolicy.ASSISTED.approves("Bash", args(command), root));
            assertTrue(command, ApprovalPolicy.FULL.approves("Bash", args(command), root));
        }
    }

    @Test public void compoundCommandsCannotEscapeThroughSymlinkOrCd() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path outside = temporary.newFolder().toPath();
        Files.createSymbolicLink(root.resolve("link"), outside);
        Files.createSymbolicLink(root.resolve("mvnw"), outside.resolve("build"));
        for (String command : new String[] {"cat link/secret", "cd link && ls", "./mvnw test"}) {
            assertEquals(command, SENSITIVE, classify("Bash", args(command), root));
        }
        Files.createDirectories(root.resolve("src"));
        assertEquals(SENSITIVE, classify("Bash", args("cd src && cat ../../secret"), root));
    }

    @Test public void projectScriptsAndTemporaryLogsAreRoutineButInlineCodeAndSystemWritesAsk() throws Exception {
        Path root = temporary.newFolder().toPath();
        Files.writeString(root.resolve("script.py"), "print('ok')");
        Files.writeString(root.resolve("script.sh"), "echo ok");
        Files.writeString(root.resolve("script.js"), "console.log('ok')");
        for (String command : new String[] {"python3 script.py data", "bash script.sh", "node script.js",
                "mvn test > /tmp/codly-build.log 2>&1", "echo 'hello world' >> 'output file.txt'"}) {
            assertEquals(command, PROJECT, classify("Bash", args(command), root));
            assertTrue(command, ApprovalPolicy.ASSISTED.approves("Bash", args(command), root));
        }
        assertEquals(READ_ONLY, classify("Bash", args("cat < input.txt | head -n 2"), root));
        for (String command : new String[] {"python3 -c 'print(1)'", "bash -c 'rm file'", "node -e 'run()'", "mvn test >/etc/log",
                "echo $(rm file) > log", "cat <<EOF", "echo hi >log; rm file", "echo hi 2>&1rm", "echo hi > 'unterminated"}) {
            assertEquals(command, SENSITIVE, classify("Bash", args(command), root));
        }
    }

    @Test public void assistedExecutesProjectScriptWithRedirectedOutputWithoutPrompt() throws Exception {
        Path root = temporary.newFolder().toPath();
        Files.writeString(root.resolve("build.sh"), "printf 'done'\n");
        ConversationContext ctx = new ConversationContext(new ArrayList<>(), null, root);
        ctx.setApprovalPolicy(ApprovalPolicy.ASSISTED);
        ctx.setApprovalHandler(request -> { fail("project script and log must not prompt"); return ApprovalDecision.DENY; });
        String output = new FunctionManager().execute("Bash", args("bash build.sh > build.log 2>&1"), ctx);
        assertTrue(output, output.startsWith("执行成功"));
        assertEquals("done", Files.readString(root.resolve("build.log")));
    }

    @Test public void promptExplainsLevelAndFullSkipsIt() throws Exception {
        ConversationContext ctx = new ConversationContext(new ArrayList<>(), null, temporary.newFolder().toPath());
        AtomicInteger questions = new AtomicInteger();
        ctx.setApprovalPolicy(ApprovalPolicy.ASSISTED);
        ctx.setConfirmation(summary -> { questions.incrementAndGet(); assertTrue(summary.contains("敏感或未识别操作")); return false; });
        assertFalse(ctx.confirm("Bash", args("rm file"), "delete"));
        assertTrue(ctx.confirm("Bash", args("mvn test"), "test"));
        ctx.setApprovalPolicy(ApprovalPolicy.ASK);
        ctx.setConfirmation(summary -> { questions.incrementAndGet(); assertTrue(summary.contains("项目内修改 / 构建测试")); return false; });
        assertFalse(ctx.confirm("Bash", args("mvn test"), "test"));
        ctx.setApprovalPolicy(ApprovalPolicy.FULL);
        assertTrue(ctx.confirm("Bash", args("rm file"), "delete"));
        assertEquals(2, questions.get());
    }
}
