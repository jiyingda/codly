package com.jiyingda.codly.skill;

import com.jiyingda.codly.command.CommandCompleter;
import org.jline.reader.Candidate;
import org.jline.reader.LineReader;
import com.jiyingda.codly.command.CommandLineReader;
import org.jline.reader.Parser;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class CommandCompleterTest {
    // xterm 在 JLine 启用 keypad/application 模式后发送的方向键序列。
    private static final String DOWN = "\033OB";
    private static final String UP = "\033OA";

    @Before
    public void registerSkills() {
        SkillRegistry.resetForTest();
        for (String name : List.of("alpha", "beta")) {
            SkillRegistry.getInstance().registerForTest(
                    new Skill(name, "Test skill " + name, "", Path.of("."), Map.of()));
        }
    }

    @After
    public void resetSkills() {
        SkillRegistry.resetForTest();
    }

    @Test(timeout = 5000)
    public void skillsCanBeSelectedWithoutTab() throws Exception {
        assertEquals("/skill-beta", read("/skill-" + DOWN + DOWN + "\r\r").trim());
        assertEquals("/skill-alpha", read("/skill-a" + DOWN + "\r\r").trim());
    }

    @Test(timeout = 5000)
    public void downSelectsWithoutTab() throws Exception {
        assertEquals("/clear", read("/c" + DOWN + "\r\r").trim());
    }

    @Test(timeout = 5000)
    public void arrowsNavigateCandidatesWithoutTab() throws Exception {
        assertEquals("/compact", read("/c" + DOWN + DOWN + "\r\r").trim());
        assertEquals("/clear", read("/c" + DOWN + DOWN + UP + "\r\r").trim());
        assertEquals("/clear", read("/c" + UP + "\r\r").trim());
    }

    @Test(timeout = 5000)
    public void backspaceRefreshesCandidates() throws Exception {
        assertEquals("/help", read("/hex\177" + DOWN + "\r\r").trim());
    }

    @Test(timeout = 5000)
    public void tabStillCompletes() throws Exception {
        assertEquals("/help", read("/he\t\r").trim());
    }

    @Test(timeout = 5000)
    public void ordinaryInputKeepsHistoryNavigation() throws Exception {
        assertEquals("previous message", read(UP + "\r"));
        assertEquals("hello", read("hello\r"));
        assertEquals("/not-a-command", read("/not-a-command\r"));
    }

    @Test(timeout = 5000)
    public void deletingSlashClosesMenuAndReturnsToNormalInput() throws Exception {
        List<Boolean> visible = new ArrayList<>();
        assertEquals("hello", read("/c" + DOWN + "\024\177\177\024hello\r", visible));
        assertEquals(List.of(true, false), visible);
        assertEquals("hello", read("/\177hello\r"));
    }

    @Test(timeout = 5000)
    public void escapeClosesMenuWithoutChangingTheTypedPrefix() throws Exception {
        List<Boolean> visible = new ArrayList<>();
        assertEquals("/ch", read("/c" + DOWN + "\033\024h\024\r", visible));
        assertEquals(List.of(false, false), visible);
        assertEquals("/c", read("/c\033\r"));
    }

    @Test(timeout = 5000)
    public void completeCommandsAndArgumentsDoNotRequireAnExtraEnter() throws Exception {
        assertEquals("/help", read("/help\r"));
        assertEquals("/permissions full", read("/permissions full\r"));
        assertEquals("/clear", read("/c\t\r").trim());
    }

    @Test
    public void completionOnlyAppliesToCommandPosition() {
        CommandCompleter completer = new CommandCompleter();
        DefaultParser parser = new DefaultParser();
        for (String text : List.of("hello /he", "/memory /he", "/he argument")) {
            List<Candidate> candidates = new ArrayList<>();
            completer.complete(null, parser.parse(text, text.length(), Parser.ParseContext.COMPLETE), candidates);
            assertTrue(text, candidates.isEmpty());
        }
        List<Candidate> candidates = new ArrayList<>();
        completer.complete(null, parser.parse("/he", 3, Parser.ParseContext.COMPLETE), candidates);
        assertEquals("/help", candidates.get(0).value());
    }

    private String read(String keys) throws Exception {
        return read(keys, new ArrayList<>());
    }

    private String read(String keys, List<Boolean> visible) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Terminal terminal = TerminalBuilder.builder()
                .system(false).dumb(false).type("xterm")
                .streams(new ByteArrayInputStream(keys.getBytes(StandardCharsets.UTF_8)), output)
                .size(new Size(120, 40)).build()) {
            CommandCompleter completer = new CommandCompleter();
            CommandLineReader reader = new CommandLineReader(terminal);
            reader.setCompleter(completer);
            completer.install(reader);
            reader.getWidgets().put("test-menu-state", () -> {visible.add(reader.commandMenuVisible());return true;});
            reader.getKeyMaps().get(LineReader.MAIN).bind(new org.jline.reader.Reference("test-menu-state"), "\024");
            reader.getHistory().add("previous message");
            return reader.readLine("> ");
        }
    }
}
