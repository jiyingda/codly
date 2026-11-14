package com.jiyingda.codly.desktop;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.ui.FlatButtonUI;
import com.formdev.flatlaf.ui.FlatTabbedPaneUI;
import com.formdev.flatlaf.ui.FlatTextBorder;
import org.junit.Test;

import javax.swing.*;
import java.awt.Color;

import static org.junit.Assert.*;

public class DesktopThemeTest {
    @Test public void themeLoadsAndStyledControlsKeepFlatLafDelegates() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LookAndFeel previous = UIManager.getLookAndFeel();
            try {
                DesktopTheme.install();
                assertTrue(UIManager.getLookAndFeel() instanceof FlatLaf);
                JButton primary = DesktopTheme.button("发送", true);
                JButton reject = DesktopTheme.button("拒绝", false);
                JRootPane root = new JRootPane();
                root.getContentPane().add(reject);
                root.setDefaultButton(reject);
                assertTrue(primary.getUI() instanceof FlatButtonUI);
                assertEquals(new Color(0x6458E8), primary.getBackground());
                // 确认窗口默认操作仍为拒绝，且不会被默认按钮样式画成主要执行动作。
                assertEquals(Color.WHITE, ((FlatButtonUI) reject.getUI()).getStyleableValue(reject, "default.background"));
                JTextField field = new JTextField();
                DesktopTheme.field(field);
                assertTrue(field.getBorder() instanceof FlatTextBorder);
                JTabbedPane tabs = new JTabbedPane();
                DesktopTheme.tabs(tabs);
                assertTrue(tabs.getUI() instanceof FlatTabbedPaneUI);
                assertEquals(42, UIManager.getInt("TabbedPane.tabHeight"));
            } finally {
                try { UIManager.setLookAndFeel(previous); }
                catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
            }
        });
    }
}
