package com.jiyingda.codly.desktop;

import org.junit.Test;
import javax.swing.*;
import java.awt.*;
import static org.junit.Assert.*;

public class MarkdownWrappingTest {
    @Test public void longUrlsAndCodeWrapInsideAvailableWidthWithoutChangingText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String token = "https://example.com/" + "very_long_unbroken_identifier_".repeat(35);
            MarkdownPane pane = new MarkdownPane();
            pane.render("## 检查结果\n" + token + "\n```java\nString value = \"" + token + "\";\n```");
            pane.setSize(pane.preferredForWidth(260));
            pane.doLayout();
            assertTrue(pane.getText().contains(token));
            assertEquals(260, pane.preferredForWidth(260).width);
            assertEquals(0, pane.getMinimumSize().width);
            try {
                int start = pane.getText().indexOf(token);
                var first = pane.modelToView2D(start);
                var last = pane.modelToView2D(start + token.length() - 1);
                assertNotNull(first); assertNotNull(last);
                assertTrue("long tokens must wrap vertically", last.getY() > first.getY());
                assertTrue("last character must stay within the component", last.getMaxX() <= pane.getWidth() + 1);
                int codeStart = pane.getText().lastIndexOf(token);
                var codeLast = pane.modelToView2D(codeStart + token.length() - 1);
                assertTrue(codeLast.getMaxX() <= pane.getWidth() + 1);
            } catch (javax.swing.text.BadLocationException e) { throw new AssertionError(e); }
        });
    }

    @Test public void narrowerWidthsIncreaseHeightWithoutMutatingTheComponentSizeDuringMeasurement() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MarkdownPane pane = new MarkdownPane();
            pane.render("较长的中英文混合段落，包含代码标识符 and_number_123。".repeat(30));
            pane.setSize(500, 300);
            Dimension before = pane.getSize();
            int wide = pane.preferredForWidth(700).height;
            int narrow = pane.preferredForWidth(280).height;
            assertTrue(narrow > wide);
            assertEquals(before, pane.getSize());
        });
    }
}
