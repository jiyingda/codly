package com.jiyingda.codly.desktop;

import com.jiyingda.codly.core.ApprovalDecision;
import com.jiyingda.codly.core.ApprovalRequest;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** 后台工具等待确认，UI 保持可响应停止和关闭操作。 */
final class SwingConfirmation implements Function<ApprovalRequest, ApprovalDecision> {
    private final Window owner;
    private boolean cancelled;
    private JDialog dialog;
    private CompletableFuture<ApprovalDecision> pending;

    SwingConfirmation(Window owner) {
        this.owner = owner;
    }

    void beginTurn() {
        cancelled = false;
    }

    void cancel() {
        cancelled = true;
        finish(ApprovalDecision.DENY);
    }

    private void finish(ApprovalDecision allowed) {
        if (pending != null) pending.complete(allowed);
        if (dialog != null) dialog.dispose();
        pending = null;
        dialog = null;
    }

    @Override
    public ApprovalDecision apply(ApprovalRequest request) {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("工具确认必须从后台线程发起");
        }
        CompletableFuture<ApprovalDecision> answer = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            if (cancelled || !owner.isDisplayable()) {
                answer.complete(ApprovalDecision.DENY);
                return;
            }
            pending = answer;
            dialog = new JDialog(owner, "确认工具操作", Dialog.ModalityType.MODELESS);
            JPanel content = new JPanel(new BorderLayout(0, 18));
            content.setBackground(DesktopTheme.BACKGROUND);
            content.setBorder(DesktopTheme.padding(24, 26));
            JPanel heading = new JPanel(new BorderLayout(0, 8));
            heading.setOpaque(false);
            heading.add(DesktopTheme.label("需要你的确认", 22, DesktopTheme.TEXT, true), BorderLayout.NORTH);
            heading.add(DesktopTheme.label("检查以下操作，允许后 Codly 才会继续执行。", 12, DesktopTheme.MUTED, false), BorderLayout.CENTER);
            content.add(heading, BorderLayout.NORTH);
            JTextArea details = new JTextArea(request.summary(), 7, 52);
            details.setEditable(false);
            details.setLineWrap(true);
            details.setWrapStyleWord(true);
            details.setCaretPosition(0);
            DesktopTheme.field(details);
            details.setBackground(DesktopTheme.CODE);
            content.add(new JScrollPane(details), BorderLayout.CENTER);
            JButton reject = DesktopTheme.button("拒绝", false);
            JButton allow = DesktopTheme.button("允许执行", true);
            JCheckBox remember = new JCheckBox("本会话类似操作不再申请");
            remember.setName("approval.remember");
            remember.setOpaque(false);
            remember.setToolTipText(request.scope().description() + "；新对话后失效");
            reject.addActionListener(event -> finish(ApprovalDecision.DENY));
            allow.addActionListener(event -> finish(remember.isSelected() ? ApprovalDecision.ALLOW_SESSION : ApprovalDecision.ALLOW_ONCE));
            JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            controls.setOpaque(false);
            controls.add(reject);
            controls.add(allow);
            JPanel footer = new JPanel(new BorderLayout(0, 8));
            footer.setOpaque(false);
            JPanel scope = new JPanel(new BorderLayout(0, 4));
            scope.setOpaque(false);
            scope.add(remember, BorderLayout.NORTH);
            JTextArea scopeText = new JTextArea("记忆范围：" + request.scope().description() + "。新对话后失效。", 2, 52);
            scopeText.setEditable(false);
            scopeText.setFocusable(false);
            scopeText.setLineWrap(true);
            scopeText.setWrapStyleWord(true);
            scopeText.setOpaque(false);
            scopeText.setFont(DesktopTheme.font(11, false));
            scopeText.setForeground(DesktopTheme.MUTED);
            scope.add(scopeText, BorderLayout.CENTER);
            footer.add(scope, BorderLayout.CENTER);
            footer.add(controls, BorderLayout.SOUTH);
            content.add(footer, BorderLayout.SOUTH);
            dialog.setContentPane(content);
            dialog.getRootPane().setDefaultButton(reject);
            dialog.pack();
            dialog.setLocationRelativeTo(owner);
            dialog.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) { finish(ApprovalDecision.DENY); }
            });
            dialog.setVisible(true);
        });
        try {
            return answer.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            SwingUtilities.invokeLater(this::cancel);
            return ApprovalDecision.DENY;
        } catch (java.util.concurrent.ExecutionException e) {
            return ApprovalDecision.DENY;
        }
    }
}
