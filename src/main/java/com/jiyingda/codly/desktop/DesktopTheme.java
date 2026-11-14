package com.jiyingda.codly.desktop;

import javax.swing.*;
import javax.swing.border.Border;
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import java.awt.*;

/** 共享配色与布局辅助；交互控件由 FlatLaf 渲染。 */
final class DesktopTheme {
    static final Color BACKGROUND = new Color(0xF7F8FA);
    static final Color SIDEBAR = new Color(0xEFF1F5);
    static final Color SURFACE = Color.WHITE;
    static final Color TEXT = new Color(0x202738);
    static final Color MUTED = new Color(0x667085);
    static final Color LINE = new Color(0xE2E6EE);
    static final Color ACCENT = new Color(0x6458E8);
    static final Color TINT = new Color(0xEEEAFE);
    static final Color CODE = new Color(0xF1F3F7);
    static final Color GREEN = new Color(0x2A856A);
    static final Color AMBER = new Color(0x9A6B25);
    static final int BODY_SIZE = 16;
    static final int CODE_SIZE = 14;
    static final int CONTROL_SIZE = 13;
    static final int CONTENT_WIDTH = 860;
    static final int CONTENT_GUTTER = 32;

    private DesktopTheme() {}

    static int contentInset(int width) { return Math.max(CONTENT_GUTTER, (width - CONTENT_WIDTH) / 2); }

    /** 必须在创建文件选择器或任何桌面窗口之前调用。 */
    static void install() {
        FlatLaf.registerCustomDefaultsSource("com.jiyingda.codly.desktop", DesktopTheme.class.getClassLoader());
        boolean installed = System.getProperty("os.name", "").startsWith("Mac")
                ? FlatMacLightLaf.setup() : FlatLightLaf.setup();
        if (!installed) throw new IllegalStateException("无法初始化 FlatLaf 桌面主题。");
    }

    static Font font(float size, boolean bold) {
        Font base = UIManager.getFont("Label.font");
        return base.deriveFont(bold ? Font.BOLD : Font.PLAIN, size);
    }

    static JLabel label(String text, float size, Color color, boolean bold) {
        JLabel label = new JLabel(text);
        label.setFont(font(size, bold));
        label.setForeground(color);
        return label;
    }

    static JPanel rounded(Color color, Color border, int radius) {
        JPanel panel = new JPanel() {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(color);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
                if (border != null) {
                    g.setColor(border);
                    g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
                }
                g.dispose();
            }
        };
        panel.setOpaque(false);
        return panel;
    }

    static Border padding(int vertical, int horizontal) {
        return BorderFactory.createEmptyBorder(vertical, horizontal, vertical, horizontal);
    }

    static void button(JButton button, boolean primary) {
        button.putClientProperty(FlatClientProperties.STYLE_CLASS, primary ? "primary" : "secondary");
        button.setFont(font(CONTROL_SIZE, primary));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    static JButton button(String text, boolean primary) {
        JButton button = new JButton(text);
        button(button, primary);
        return button;
    }

    static void field(javax.swing.text.JTextComponent field) {
        field.setFont(font(14, false));
        field.setForeground(TEXT);
        field.setBackground(SURFACE);
        field.setCaretColor(ACCENT);
        field.setSelectionColor(TINT);
        field.setMargin(new Insets(8, 10, 8, 10));
    }

    static JScrollPane scroll(Component view, Color background) {
        JScrollPane scroll = new JScrollPane(view);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(background);
        scroll.setBackground(background);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        return scroll;
    }

    static void tabs(JTabbedPane tabs) {
        tabs.setTabPlacement(JTabbedPane.LEFT);
        tabs.setFont(font(13, false));
        tabs.setForeground(TEXT);
        tabs.setBackground(BACKGROUND);
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_TYPE,
                FlatClientProperties.TABBED_PANE_TAB_TYPE_CARD);
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_SHOW_CONTENT_SEPARATOR, false);
    }
}
