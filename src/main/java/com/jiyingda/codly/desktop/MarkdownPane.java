package com.jiyingda.codly.desktop;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;

/** 轻量块级 Markdown 呈现，所有内容均作为文本插入，不执行 HTML。 */
final class MarkdownPane extends JTextPane {
    MarkdownPane() {
        setEditorKit(new WrappingEditorKit());
        setEditable(false);
        setOpaque(false);
        setForeground(DesktopTheme.TEXT);
        setFont(DesktopTheme.font(DesktopTheme.BODY_SIZE, false));
        setBorder(BorderFactory.createEmptyBorder());
        setMargin(new Insets(0, 0, 0, 0));
        ((DefaultCaret) getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        SimpleAttributeSet paragraph = new SimpleAttributeSet();
        StyleConstants.setLineSpacing(paragraph, 0.2f);
        setParagraphAttributes(paragraph, false);
    }

    @Override public Dimension getPreferredSize() {
        int width = getParent() == null ? 620 : getParent().getWidth();
        if (getParent() instanceof JComponent parent) width -= parent.getInsets().left + parent.getInsets().right;
        return preferredForWidth(width > 0 ? width : 620);
    }

    Dimension preferredForWidth(int width) {
        Insets insets = getInsets();
        View root = getUI().getRootView(this);
        root.setSize(Math.max(1, width - insets.left - insets.right), Integer.MAX_VALUE);
        return new Dimension(Math.max(1, width), (int) Math.ceil(root.getPreferredSpan(View.Y_AXIS)) + insets.top + insets.bottom);
    }

    @Override public Dimension getMinimumSize() { return new Dimension(0, getFontMetrics(getFont()).getHeight()); }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }

    private static final class WrappingEditorKit extends StyledEditorKit {
        @Override public ViewFactory getViewFactory() {
            return element -> switch (element.getName()) {
                case AbstractDocument.ContentElementName -> new LabelView(element) {
                    @Override public float getMinimumSpan(int axis) { return axis == View.X_AXIS ? 0 : super.getMinimumSpan(axis); }
                };
                case AbstractDocument.ParagraphElementName -> new ParagraphView(element);
                case AbstractDocument.SectionElementName -> new BoxView(element, View.Y_AXIS);
                case StyleConstants.ComponentElementName -> new ComponentView(element);
                case StyleConstants.IconElementName -> new IconView(element);
                default -> new LabelView(element);
            };
        }
    }

    void append(String text) { insert(text, new SimpleAttributeSet()); }

    void render(String markdown) {
        setText("");
        boolean code = false;
        for (String line : markdown.replaceFirst("\\n+$", "").split("\n", -1)) {
            if (line.stripLeading().startsWith("```")) {
                code = !code;
                if (code) {
                    int start = getDocument().getLength();
                    String language = line.stripLeading().substring(3).trim();
                    SimpleAttributeSet header = new SimpleAttributeSet();
                    StyleConstants.setForeground(header, DesktopTheme.MUTED);
                    StyleConstants.setFontSize(header, 12);
                    insert((language.isEmpty() ? "代码" : language) + "\n", header);
                    paragraph(start, 4, 0);
                } else paragraphGap(12);
                continue;
            }
            if (!code && line.isBlank()) { paragraphGap(12); continue; }
            SimpleAttributeSet style = new SimpleAttributeSet();
            StyleConstants.setFontFamily(style, getFont().getFamily());
            StyleConstants.setFontSize(style, getFont().getSize());
            if (code) {
                StyleConstants.setFontFamily(style, Font.MONOSPACED);
                StyleConstants.setFontSize(style, DesktopTheme.CODE_SIZE);
                StyleConstants.setBackground(style, DesktopTheme.CODE);
                int start = getDocument().getLength();
                insert("  " + line + "\n", style);
                paragraph(start, 1, 0.15f);
            } else {
                int start = getDocument().getLength();
                boolean heading = line.matches("#{1,6} .*");
                boolean list = line.startsWith("- ") || line.startsWith("* ");
                if (heading) {
                    int level = line.indexOf(' ');
                    line = line.substring(level + 1);
                    StyleConstants.setBold(style, true);
                    StyleConstants.setFontSize(style, level == 1 ? 23 : level == 2 ? 20 : 17);
                }
                if (list) line = "•  " + line.substring(2);
                inline(line, style);
                insert("\n", style);
                paragraph(start, heading ? 10 : list ? 4 : 8, 0.2f);
            }
        }
    }

    private void paragraphGap(int spacing) {
        if (getDocument().getLength() == 0) return;
        SimpleAttributeSet paragraph = new SimpleAttributeSet();
        StyleConstants.setSpaceBelow(paragraph, spacing);
        getStyledDocument().setParagraphAttributes(getDocument().getLength() - 1, 1, paragraph, false);
    }

    private void paragraph(int start, int spacing, float lineSpacing) {
        SimpleAttributeSet paragraph = new SimpleAttributeSet();
        StyleConstants.setSpaceBelow(paragraph, spacing);
        StyleConstants.setLineSpacing(paragraph, lineSpacing);
        getStyledDocument().setParagraphAttributes(start, getDocument().getLength() - start, paragraph, false);
    }

    private void inline(String line, SimpleAttributeSet base) {
        for (int position = 0; position < line.length();) {
            String marker = line.startsWith("**", position) ? "**" : line.charAt(position) == '`' ? "`" : null;
            if (marker != null) {
                int end = line.indexOf(marker, position + marker.length());
                if (end > position + marker.length()) {
                    SimpleAttributeSet emphasis = new SimpleAttributeSet(base);
                    if ("**".equals(marker)) StyleConstants.setBold(emphasis, true);
                    else {
                        StyleConstants.setFontFamily(emphasis, Font.MONOSPACED);
                        StyleConstants.setBackground(emphasis, DesktopTheme.CODE);
                    }
                    insert(line.substring(position + marker.length(), end), emphasis);
                    position = end + marker.length();
                    continue;
                }
            }
            int end = position + 1;
            while (end < line.length() && line.charAt(end) != '`' && !line.startsWith("**", end)) end++;
            insert(line.substring(position, end), base);
            position = end;
        }
    }

    private void insert(String text, AttributeSet style) {
        try { getStyledDocument().insertString(getDocument().getLength(), text, style); }
        catch (BadLocationException e) { throw new IllegalStateException(e); }
    }
}
