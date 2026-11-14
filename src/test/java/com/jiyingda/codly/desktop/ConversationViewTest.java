package com.jiyingda.codly.desktop;

import com.jiyingda.codly.core.ToolEvent;
import org.junit.Test;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class ConversationViewTest {
    @Test public void chatShowsOnlyTemporaryStatusThenFinalAnswerWithoutIntermediateProcess() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ConversationView view = new ConversationView(text -> {});
            view.beginTurn("分析项目");
            view.beginReasoning();
            view.append("模型的中间思考");
            assertTrue(find(view, JLabel.class).stream().anyMatch(label -> label.isVisible() && "正在思考…".equals(label.getText())));
            view.endReasoning();
            view.append("先执行一条命令，这是中间解释");
            view.tool(new ToolEvent("bash", "Bash", "{\"command\":\"pwd\"}", null, ToolEvent.State.REQUESTED));
            assertTrue(find(view, JLabel.class).stream().anyMatch(label -> label.isVisible() && "正在调用 Bash…".equals(label.getText())));
            view.activity("正在等待操作批准…");
            view.resumeTool();
            assertTrue(find(view, JLabel.class).stream().anyMatch(label -> label.isVisible() && "正在调用 Bash…".equals(label.getText())));
            view.tool(new ToolEvent("bash", "Bash", null, "执行结果仅在轨迹展示", ToolEvent.State.RETURNED));
            view.append("最终答复：**项目检查完成**");
            assertTrue(find(view, MarkdownPane.class).stream().noneMatch(pane -> "message.assistant".equals(pane.getName())));
            view.finish("已完成");
            String answer = find(view, MarkdownPane.class).stream().filter(pane -> "message.assistant".equals(pane.getName())).findFirst().orElseThrow().getText();
            assertTrue(answer.contains("项目检查完成"));
            assertFalse(answer.contains("中间"));
            assertFalse(answer.contains("执行结果"));
            assertTrue(find(view, JTextArea.class).isEmpty());
            assertTrue(find(view, JButton.class).stream().noneMatch(button -> "展开".equals(button.getText())));
            assertTrue(find(view, JLabel.class).stream().noneMatch(label -> label.isVisible() && label.getText().startsWith("正在")));
        });
        SwingUtilities.invokeAndWait(() -> {});
    }

    @Test public void errorAndCancellationStayVisibleAsFinalStatus() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ConversationView view = new ConversationView(text -> {});
            view.beginTurn("继续");
            view.beginReasoning();
            view.append("不展示的思考");
            view.finish("已停止");
            assertTrue(find(view, JLabel.class).stream().anyMatch(label -> label.isVisible() && "已停止".equals(label.getText())));
            view.beginTurn("再次执行");
            view.append("请求失败：HTTP 500");
            view.finish("请求失败");
            assertTrue(find(view, MarkdownPane.class).stream().anyMatch(pane -> pane.getText().contains("HTTP 500")));
        });
        SwingUtilities.invokeAndWait(() -> {});
    }

    @Test public void restoresOnlySavedFinalAnswersAndContinuesWithoutDuplicatingOldTurns() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var turns = java.util.List.of(new com.jiyingda.codly.core.ConversationRecord.Turn("旧问题", "旧答案", "已完成"));
            ConversationView view = new ConversationView(text -> {});
            view.restore(turns);
            assertEquals(turns, view.snapshotTurns());
            assertTrue(find(view, MarkdownPane.class).stream().anyMatch(pane -> pane.getText().contains("旧答案")));
            view.beginTurn("继续"); view.append("新答案"); view.finish("已完成");
            assertEquals(2, view.snapshotTurns().size());
            assertEquals("新答案", view.snapshotTurns().get(1).assistant());
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
