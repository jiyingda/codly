package com.jiyingda.codly.desktop;

import com.jiyingda.codly.core.TraceEvent;
import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** 按事件发生时间排列的会话轨迹，详情仅保存在当前窗口内存中。仅在 EDT 更新。 */
final class TraceView extends JPanel {
    private static final Color BLUE = new Color(0x3478C7);
    private static final Color RED = new Color(0xC44949);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private final JPanel timeline = new TimelinePanel();
    private final JScrollPane scroll;
    private final JLabel count = DesktopTheme.label("尚无执行记录", 12, DesktopTheme.MUTED, false);
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean following = true;
    private boolean restoring;
    private final java.util.List<TraceEvent> events = new ArrayList<>();

    TraceView() {
        super(new BorderLayout(0, 12));
        setBackground(DesktopTheme.BACKGROUND);
        setBorder(DesktopTheme.padding(20, 32));
        setName("trace.view");
        JPanel header = new JPanel(new BorderLayout(0, 8));
        header.setOpaque(false);
        header.add(DesktopTheme.label("执行轨迹", 18, DesktopTheme.TEXT, true), BorderLayout.NORTH);
        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        legend.setOpaque(false);
        legend.add(DesktopTheme.label("● 模型请求", 12, BLUE, false));
        legend.add(DesktopTheme.label("● 思考", 12, DesktopTheme.ACCENT, false));
        legend.add(DesktopTheme.label("● 工具 / 批准", 12, DesktopTheme.AMBER, false));
        legend.add(DesktopTheme.label("● 响应 / 完成", 12, DesktopTheme.GREEN, false));
        legend.add(DesktopTheme.label("● 失败", 12, RED, false));
        header.add(legend, BorderLayout.CENTER);
        header.add(count, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);
        timeline.setLayout(new BoxLayout(timeline, BoxLayout.Y_AXIS));
        timeline.setOpaque(false);
        scroll = DesktopTheme.scroll(timeline, DesktopTheme.BACKGROUND);
        scroll.getVerticalScrollBar().addAdjustmentListener(event -> {
            if (!event.getValueIsAdjusting()) {
                JScrollBar bar = scroll.getVerticalScrollBar();
                following = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 40;
            }
        });
        add(scroll, BorderLayout.CENTER);
        clear();
    }

    void clear() {
        events.clear();
        entries.clear();
        timeline.removeAll();
        timeline.add(DesktopTheme.label("发送消息后，这里会按时间展示请求、提示词、思考和执行详情。", 12, DesktopTheme.MUTED, false));
        count.setText("尚无执行记录");
        following = true;
        changed(false);
    }

    void accept(TraceEvent event) {
        events.add(event);
        boolean follow = following;
        Entry entry = entries.get(event.id());
        if (entry == null) {
            if (entries.isEmpty()) timeline.removeAll();
            entry = new Entry(event);
            entries.put(event.id(), entry);
            var ordered = new ArrayList<>(entries.values());
            ordered.sort(Comparator.comparing(item -> item.first.at()));
            int index = ordered.indexOf(entry);
            timeline.add(entry, index);
        }
        entry.update(event);
        count.setText(entries.size() + " 个节点 · 本地时间 · 点击展开查看完整内容");
        if (!restoring) changed(follow);
    }
    java.util.List<TraceEvent> snapshot() { return java.util.List.copyOf(events); }
    void restore(java.util.List<TraceEvent> saved) {
        clear();
        restoring = true;
        try { saved.forEach(this::accept); }
        finally { restoring = false; }
        changed(true);
    }

    private void changed(boolean follow) {
        timeline.revalidate();
        timeline.repaint();
        if (follow) SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum()));
    }

    private static Color color(TraceEvent event) {
        if (event.phase() == TraceEvent.Phase.FAILED) return RED;
        if (event.phase() == TraceEvent.Phase.CANCELLED) return DesktopTheme.AMBER;
        return switch (event.kind()) {
            case MODEL_REQUEST -> BLUE;
            case THINKING -> DesktopTheme.ACCENT;
            case TOOL -> DesktopTheme.AMBER;
            case APPROVAL -> event.phase() == TraceEvent.Phase.COMPLETE ? DesktopTheme.GREEN : DesktopTheme.AMBER;
            case MODEL_RESPONSE -> DesktopTheme.GREEN;
            default -> DesktopTheme.MUTED;
        };
    }

    private static final class TimelinePanel extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 18; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(18, visible.height - 18); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private final class Entry extends JPanel {
        final TraceEvent first;
        final JLabel title = DesktopTheme.label("", 13, DesktopTheme.TEXT, true);
        final JLabel phase = DesktopTheme.label("", 12, DesktopTheme.MUTED, false);
        final JLabel preview = DesktopTheme.label("", 12, DesktopTheme.MUTED, false);
        private Color marker = DesktopTheme.MUTED;
        final JTextArea text = new JTextArea();
        final JPanel card = new JPanel(new BorderLayout(0, 8));
        final JScrollPane details;
        final JButton toggle = DesktopTheme.button("展开", false);

        Entry(TraceEvent event) {
            super(new BorderLayout(12, 0));
            first = event;
            setOpaque(false);
            setAlignmentX(LEFT_ALIGNMENT);
            setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
            JPanel time = new JPanel(new BorderLayout(0, 6)) {
                @Override protected void paintComponent(Graphics graphics) {
                    super.paintComponent(graphics);
                    graphics.setColor(DesktopTheme.LINE);
                    graphics.drawLine(100, 14, 100, getHeight());
                    graphics.setColor(marker);
                    graphics.fillOval(96, 3, 9, 9);
                }
            };
            time.setOpaque(false);
            time.setPreferredSize(new Dimension(108, 50));
            JLabel stamp = DesktopTheme.label(TIME.format(event.at()), 11, DesktopTheme.MUTED, false);
            stamp.setToolTipText(event.at().toString());
            time.add(stamp, BorderLayout.NORTH);
            add(time, BorderLayout.WEST);
            card.setBackground(DesktopTheme.SURFACE);
            JPanel heading = new JPanel(new BorderLayout(8, 0));
            heading.setOpaque(false);
            heading.add(title, BorderLayout.CENTER);
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            actions.setOpaque(false);
            actions.add(phase);
            toggle.setMargin(new Insets(2, 8, 2, 8));
            actions.add(toggle);
            heading.add(actions, BorderLayout.EAST);
            card.add(heading, BorderLayout.NORTH);
            text.setName("trace.details");
            text.setEditable(false);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            text.setBackground(DesktopTheme.SURFACE);
            text.setForeground(DesktopTheme.TEXT);
            ((DefaultCaret) text.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
            details = DesktopTheme.scroll(text, DesktopTheme.SURFACE);
            details.setPreferredSize(new Dimension(450, 230));
            details.setVisible(false);
            JPanel content = new JPanel(new BorderLayout(0, 8));
            content.setOpaque(false);
            content.add(preview, BorderLayout.NORTH);
            content.add(details, BorderLayout.CENTER);
            card.add(content, BorderLayout.CENTER);
            add(card, BorderLayout.CENTER);
            toggle.addActionListener(action -> {
                boolean open = !details.isVisible();
                details.setVisible(open);
                preview.setVisible(!open);
                toggle.setText(open ? "收起" : "展开");
                revalidate();
                changed(false);
                if (open) SwingUtilities.invokeLater(() -> details.getViewport().setViewPosition(new Point(0, 0)));
            });
        }

        void update(TraceEvent event) {
            title.setText(event.title());
            title.setToolTipText(event.title());
            text.append(event.detail());
            try {
                String summary = text.getText(0, Math.min(130, text.getDocument().getLength())).replaceAll("\\s+", " ").strip();
                // JLabel 的 HTML 自动识别不应用于原始提示词或命令内容。
                preview.putClientProperty("html.disable", true);
                preview.setText(summary.length() > 90 ? summary.substring(0, 90) + "…" : summary);
            } catch (javax.swing.text.BadLocationException e) { throw new IllegalStateException(e); }
            Color tint = color(event);
            marker = tint;
            phase.setForeground(tint);
            String label = switch (event.phase()) {
                case START, APPEND -> "进行中";
                case COMPLETE -> "已完成";
                case FAILED -> "失败";
                case CANCELLED -> "已停止";
            };
            long millis = Math.max(0, Duration.between(first.at(), event.at()).toMillis());
            phase.setText(label + (millis > 0 ? " · " + (millis < 1000 ? millis + "ms" : String.format("%.1fs", millis / 1000.0)) : ""));
            card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, tint),
                    BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(DesktopTheme.LINE), DesktopTheme.padding(12, 14))));
        }

        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
    }
}
