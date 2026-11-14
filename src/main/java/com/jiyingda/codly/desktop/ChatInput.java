package com.jiyingda.codly.desktop;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultEditorKit;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.InputMethodEvent;
import java.awt.event.KeyEvent;

/** 输入法组合文本未确认时，Enter 不触发发送。 */
final class ChatInput extends JTextArea {
    private boolean composing;

    ChatInput(Runnable send) {
        super(1, 36);
        setName("chat.input");
        setLineWrap(true);
        setWrapStyleWord(true);
        setFont(DesktopTheme.font(15, false));
        setForeground(DesktopTheme.TEXT);
        setBackground(DesktopTheme.SURFACE);
        setCaretColor(DesktopTheme.ACCENT);
        setSelectionColor(DesktopTheme.TINT);
        setBorder(DesktopTheme.padding(4, 2));
        getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send-message");
        getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), DefaultEditorKit.insertBreakAction);
        getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK), "send-message");
        getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "send-message");
        getActionMap().put("send-message", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { if (!composing) send.run(); }
        });
        getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { changed(); }
            public void removeUpdate(DocumentEvent e) { changed(); }
            public void changedUpdate(DocumentEvent e) { changed(); }
            private void changed() { updateRows(); repaint(); }
        });
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) { updateRows(); }
        });
    }

    private void updateRows() {
        int width = getWidth() - getInsets().left - getInsets().right;
        if (width <= 0) width = getColumns() * getFontMetrics(getFont()).charWidth('m');
        int lines = 0;
        for (String line : getText().split("\n", -1)) {
            lines += Math.max(1, (int) Math.ceil(getFontMetrics(getFont()).stringWidth(line) / (double) Math.max(1, width)));
            if (lines >= 4) break;
        }
        int rows = Math.min(4, Math.max(1, lines));
        if (getRows() != rows) { setRows(rows); revalidate(); }
    }

    @Override protected void processInputMethodEvent(InputMethodEvent event) {
        if (event.getID() == InputMethodEvent.INPUT_METHOD_TEXT_CHANGED) {
            var text = event.getText();
            composing = text != null && text.getEndIndex() - text.getBeginIndex() > event.getCommittedCharacterCount();
        }
        super.processInputMethodEvent(event);
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (getDocument().getLength() == 0 && !composing) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setColor(DesktopTheme.MUTED);
            g.setFont(getFont());
            g.drawString("描述你的任务，或粘贴代码与错误信息…", getInsets().left + 2,
                    getInsets().top + getFontMetrics(getFont()).getAscent());
            g.dispose();
        }
    }
}
