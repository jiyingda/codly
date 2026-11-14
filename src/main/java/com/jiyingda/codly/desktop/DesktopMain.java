package com.jiyingda.codly.desktop;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;

/** 可独立启动，也可通过 java -jar codly.jar --desktop [项目目录] 启动。 */
public final class DesktopMain {
    private DesktopMain() {}

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Swing 桌面入口需要图形显示环境。");
            return;
        }
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "Codly");
        SwingUtilities.invokeLater(() -> {
            try {
                DesktopTheme.install();
                Path workspace = resolveWorkspace(args);
                new DesktopWindow(workspace).setVisible(true);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, e.getMessage(), "Codly 启动失败", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    static Path resolveWorkspace(String[] args) throws java.io.IOException {
        Path workspace = Path.of(args.length > 0 ? args[0] : "").toRealPath();
        if (!java.nio.file.Files.isDirectory(workspace)) throw new java.io.IOException("工作目录不存在或不是目录：" + workspace);
        return workspace;
    }
}
