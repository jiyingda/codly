package com.jiyingda.codly.desktop;

import com.jiyingda.codly.core.TraceEvent;
import org.junit.Test;
import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class TraceViewTest {
    @Test public void chronologicalNodesKeepFullPromptReasoningArgumentsAndOutputWithTopAlignedDetails() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TraceView view = new TraceView();
            Instant now = Instant.now();
            view.accept(new TraceEvent("tool", now.plusSeconds(2), TraceEvent.Kind.TOOL, TraceEvent.Phase.START, "工具调用 · Bash", "参数\n{\"command\":\"pwd\"}"));
            view.accept(new TraceEvent("prompt", now, TraceEvent.Kind.MODEL_REQUEST, TraceEvent.Phase.START, "模型请求", "完整系统提示词\n历史消息\n工具定义"));
            view.accept(new TraceEvent("thinking", now.plusSeconds(1), TraceEvent.Kind.THINKING, TraceEvent.Phase.START, "思考过程", "先读取文件\n"));
            view.accept(new TraceEvent("thinking", now.plusSeconds(3), TraceEvent.Kind.THINKING, TraceEvent.Phase.APPEND, "思考过程", "继续分析\n" + "\n".repeat(100)));
            view.accept(new TraceEvent("tool", now.plusSeconds(4), TraceEvent.Kind.TOOL, TraceEvent.Phase.COMPLETE, "工具调用 · Bash", "\n返回结果\n/project\n" + "\n".repeat(100)));
            List<JTextArea> texts = find(view, JTextArea.class);
            assertEquals(3, texts.size());
            assertTrue(texts.get(0).getText().contains("完整系统提示词"));
            assertTrue(texts.get(1).getText().contains("先读取文件\n继续分析"));
            assertTrue(texts.get(2).getText().contains("参数\n{\"command\":\"pwd\"}\n返回结果\n/project"));
            for (JButton button : find(view, JButton.class)) if ("展开".equals(button.getText())) button.doClick();
            for (JTextArea text : texts) assertEquals(0, text.getCaretPosition());
            for (JScrollPane pane : find(view, JScrollPane.class)) if (pane.getViewport().getView() instanceof JTextArea) {
                assertTrue(pane.isVisible());
                assertEquals(0, pane.getViewport().getViewPosition().y);
            }
        });
        SwingUtilities.invokeAndWait(() -> {});
    }

    @Test public void failuresChangeColorAndResetRemovesAllPreviousDetails() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TraceView view = new TraceView();
            view.accept(TraceEvent.of("request", TraceEvent.Kind.MODEL_REQUEST, TraceEvent.Phase.START, "模型请求", "原始请求"));
            Color before = find(view, JLabel.class).stream().filter(label -> label.getText().startsWith("进行中")).findFirst().orElseThrow().getForeground();
            view.accept(TraceEvent.of("request", TraceEvent.Kind.MODEL_REQUEST, TraceEvent.Phase.FAILED, "模型请求失败", "\nHTTP 500"));
            Color after = find(view, JLabel.class).stream().filter(label -> label.getText().startsWith("失败")).findFirst().orElseThrow().getForeground();
            assertNotEquals(before, after);
            assertEquals(1, find(view, JTextArea.class).size());
            assertEquals("原始请求\nHTTP 500", find(view, JTextArea.class).get(0).getText());
            view.clear();
            assertTrue(find(view, JTextArea.class).isEmpty());
            assertTrue(find(view, JLabel.class).stream().anyMatch(label -> "尚无执行记录".equals(label.getText())));
        });
    }

    private static <T> List<T> find(Container root, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) result.add(type.cast(child));
            if (child instanceof Container container) result.addAll(find(container, type));
        }
        return result;
    }
}
