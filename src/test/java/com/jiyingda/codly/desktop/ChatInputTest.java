package com.jiyingda.codly.desktop;

import org.junit.Test;
import javax.swing.*;
import java.awt.event.*;
import java.text.AttributedString;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ChatInputTest {
    @Test public void enterSendsAndShiftEnterInsertsANewline() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicInteger sent = new AtomicInteger();
            ChatInput input = new ChatInput(sent::incrementAndGet);
            input.setText("hello");
            press(input, KeyEvent.VK_ENTER, 0);
            assertEquals(1, sent.get());
            input.setCaretPosition(input.getText().length());
            press(input, KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK);
            assertEquals("hello\n", input.getText());
            assertEquals(1, sent.get());
            press(input, KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK);
            press(input, KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK);
            assertEquals(3, sent.get());
        });
    }

    @Test public void enterDoesNotSendWhileTheInputMethodIsComposing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicInteger sent = new AtomicInteger();
            ChatInput input = new ChatInput(sent::incrementAndGet);
            input.processInputMethodEvent(new InputMethodEvent(input, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                    new AttributedString("pin").getIterator(), 0, null, null));
            press(input, KeyEvent.VK_ENTER, 0);
            assertEquals(0, sent.get());
            input.processInputMethodEvent(new InputMethodEvent(input, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                    new AttributedString("拼").getIterator(), 1, null, null));
            press(input, KeyEvent.VK_ENTER, 0);
            assertEquals(1, sent.get());
        });
    }

    @Test public void markdownFormattingKeepsCodeAndTreatsHtmlAsPlainText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MarkdownPane pane = new MarkdownPane();
            pane.render("## 标题\n**重点**和 `code`\n```java\nSystem.out.println(1);\n```\n<script>literal</script>");
            assertTrue(pane.getText().contains("System.out.println(1);"));
            assertTrue(pane.getText().contains("<script>literal</script>"));
            assertFalse(pane.getText().contains("**"));
            assertFalse(pane.getText().contains("```"));
        });
    }

    private static void press(ChatInput input, int key, int modifiers) {
        Object binding = input.getInputMap().get(KeyStroke.getKeyStroke(key, modifiers));
        assertNotNull(binding);
        input.getActionMap().get(binding).actionPerformed(new ActionEvent(input, ActionEvent.ACTION_PERFORMED, "test"));
    }
}
