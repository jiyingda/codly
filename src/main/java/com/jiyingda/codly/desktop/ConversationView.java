package com.jiyingda.codly.desktop;

import com.jiyingda.codly.core.ToolEvent;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.function.Consumer;

/** 自适应宽度的聊天时间线，仅展示最终答复，保留用户向上阅读的位置。 */
final class ConversationView extends JPanel {
    private final JPanel messages = new MessageList();
    private final JScrollPane scroll;
    private final JButton latest = DesktopTheme.button("回到最新消息 ↓", false);
    private final Consumer<String> suggestion;
    private AssistantCard current;
    private boolean hasMessages;
    private boolean following = true;
    private boolean changing;
    private final java.util.List<com.jiyingda.codly.core.ConversationRecord.Turn> turns = new java.util.ArrayList<>();
    private String currentUser;
    private boolean currentSaved;

    ConversationView(Consumer<String> suggestion) {
        super(new BorderLayout());
        this.suggestion = suggestion;
        setBackground(DesktopTheme.BACKGROUND);
        messages.setLayout(new BoxLayout(messages, BoxLayout.Y_AXIS));
        messages.setBackground(DesktopTheme.BACKGROUND);
        messages.setBorder(BorderFactory.createEmptyBorder(24, DesktopTheme.CONTENT_GUTTER, 24, DesktopTheme.CONTENT_GUTTER));
        scroll = DesktopTheme.scroll(messages, DesktopTheme.BACKGROUND);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setName("conversation.scroll");
        add(scroll, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 2));
        bottom.setOpaque(false);
        latest.setVisible(false);
        latest.addActionListener(event -> scrollToLatest());
        bottom.add(latest);
        add(bottom, BorderLayout.SOUTH);
        scroll.getVerticalScrollBar().addAdjustmentListener(event -> {
            if (changing) return;
            JScrollBar bar = scroll.getVerticalScrollBar();
            following = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 56;
            latest.setVisible(hasMessages && !following);
        });
        welcome();
    }

    void welcome() {
        turns.clear();
        hasMessages = false;
        current = null;
        messages.removeAll();
        JPanel hero = new JPanel();
        hero.setOpaque(false);
        hero.setLayout(new BoxLayout(hero, BoxLayout.Y_AXIS));
        hero.setBorder(BorderFactory.createEmptyBorder(32, 0, 24, 0));
        hero.add(DesktopTheme.label("CODLY / YOUR CODING COMPANION", 12, DesktopTheme.ACCENT, true));
        hero.add(Box.createVerticalStrut(14));
        hero.add(DesktopTheme.label("今天，想一起完成什么？", 30, DesktopTheme.TEXT, true));
        hero.add(Box.createVerticalStrut(10));
        hero.add(DesktopTheme.label("从理解项目、定位问题，到实现你的下一个想法。", 15, DesktopTheme.MUTED, false));
        hero.add(Box.createVerticalStrut(24));
        JPanel suggestions = new JPanel(new GridLayout(2, 2, 12, 12));
        suggestions.setOpaque(false);
        String[][] examples = {
                {"了解这个项目", "请阅读当前项目，概述架构、入口和关键模块。"},
                {"定位一个问题", "请帮我排查一个问题，先确认需要哪些错误信息和上下文。"},
                {"完善一个功能", "我想在当前项目中完善一个功能，请先了解现有实现。"},
                {"补齐关键测试", "请分析项目中最值得补充测试的核心逻辑，并给出建议。"}};
        for (String[] example : examples) {
            JButton button = DesktopTheme.button(example[0] + "  ↗", false);
            button.setHorizontalAlignment(SwingConstants.LEFT);
            button.setMargin(new Insets(14, 16, 14, 16));
            button.addActionListener(event -> suggestion.accept(example[1]));
            suggestions.add(button);
        }
        suggestions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 108));
        suggestions.setAlignmentX(LEFT_ALIGNMENT);
        hero.add(suggestions);
        hero.setAlignmentX(LEFT_ALIGNMENT);
        messages.add(hero);
        latest.setVisible(false);
        following = true;
        changed();
    }

    void beginTurn(String text) {
        currentUser = text;
        currentSaved = false;
        if (!hasMessages) messages.removeAll();
        hasMessages = true;
        JPanel bubble = DesktopTheme.rounded(DesktopTheme.TINT, null, 20);
        bubble.setLayout(new BorderLayout(0, 8));
        bubble.setBorder(DesktopTheme.padding(12, 16));
        MarkdownPane body = new MarkdownPane();
        body.setFont(DesktopTheme.font(15, false));
        body.setName("message.user");
        body.setText(text);
        bubble.add(body, BorderLayout.CENTER);
        JPanel row = new UserRow(bubble, body, text);
        addRow(row);
        current = new AssistantCard();
        addRow(current);
        following = true;
        changed();
    }

    void append(String text) { if (current != null) { current.append(text); changed(); } }
    void beginReasoning() { if (current != null) { current.beginReasoning(); changed(); } }
    void endReasoning() { if (current != null) { current.endReasoning(); changed(); } }
    void tool(ToolEvent event) { if (current != null) { current.tool(event); changed(); } }
    void activity(String text) { if (current != null) current.activity(text); }
    void resumeTool() { if (current != null) current.resumeTool(); }
    void finish(String state) {
        if (current != null) {
            current.finish(state);
            if (!currentSaved) { turns.add(new com.jiyingda.codly.core.ConversationRecord.Turn(currentUser, current.raw.toString(), state)); currentSaved = true; }
            changed();
        }
    }
    java.util.List<com.jiyingda.codly.core.ConversationRecord.Turn> snapshotTurns() { return java.util.List.copyOf(turns); }
    void restore(java.util.List<com.jiyingda.codly.core.ConversationRecord.Turn> saved) {
        welcome();
        for (var turn : saved) { beginTurn(turn.user()); append(turn.assistant()); finish(turn.state()); }
    }

    void notice(String text) {
        JPanel note = new JPanel(new BorderLayout());
        note.setOpaque(false);
        MarkdownPane body = new MarkdownPane();
        body.setForeground(DesktopTheme.MUTED);
        body.setText(text);
        note.add(body);
        addRow(note);
        changed();
    }

    void scrollToLatest() { following = true; changed(); }

    private void addRow(JPanel row) {
        row.setAlignmentX(LEFT_ALIGNMENT);
        JPanel wrapper = new JPanel(new BorderLayout()) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        wrapper.setOpaque(false);
        wrapper.setBorder(BorderFactory.createEmptyBorder(0, 0, 20, 0));
        wrapper.add(row);
        wrapper.setAlignmentX(LEFT_ALIGNMENT);
        messages.add(wrapper);
    }

    private void changed() {
        changed(following);
    }

    private void changed(boolean follow) {
        changing = true;
        messages.revalidate();
        messages.repaint();
        SwingUtilities.invokeLater(() -> {
            if (follow) {
                JScrollBar bar = scroll.getVerticalScrollBar();
                bar.setValue(bar.getMaximum());
                latest.setVisible(false);
            }
            changing = false;
        });
    }

    private static final class MessageList extends JPanel implements Scrollable {
        @Override public void doLayout() {
            int side = DesktopTheme.contentInset(getWidth());
            if (getInsets().left != side) setBorder(BorderFactory.createEmptyBorder(24, side, 24, side));
            super.doLayout();
        }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 18; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(18, visible.height - 18); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private static final class UserRow extends JPanel {
        private final JPanel bubble;
        private final MarkdownPane body;
        private final int naturalWidth;
        UserRow(JPanel bubble, MarkdownPane body, String text) {
            super(null);
            this.bubble = bubble; this.body = body;
            setOpaque(false);
            int longest = 0;
            for (String line : text.split("\n", -1)) longest = Math.max(longest, body.getFontMetrics(body.getFont()).stringWidth(line));
            naturalWidth = longest + bubble.getInsets().left + bubble.getInsets().right + 8;
            add(bubble);
        }
        private int availableWidth() {
            Container parent = getParent();
            while (parent != null && !(parent instanceof MessageList)) parent = parent.getParent();
            int width = parent == null ? 0 : parent.getWidth() - parent.getInsets().left - parent.getInsets().right;
            return width > 0 ? width : 620;
        }
        private int bubbleWidth() { return Math.min(Math.max(1, (int) (availableWidth() * 0.85)), Math.max(100, naturalWidth)); }
        @Override public Dimension getPreferredSize() {
            Insets padding = bubble.getInsets();
            return new Dimension(availableWidth(), body.preferredForWidth(Math.max(1, bubbleWidth() - padding.left - padding.right)).height + padding.top + padding.bottom);
        }
        @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }
        @Override public void doLayout() {
            int width = bubbleWidth();
            bubble.setBounds(Math.max(0, getWidth() - width), 0, width, getPreferredSize().height);
            bubble.doLayout();
        }
    }

    private static final class AssistantCard extends JPanel {
        private final JPanel answer = new JPanel(new BorderLayout());
        private final JLabel state = DesktopTheme.label("正在请求模型…", 13, DesktopTheme.MUTED, false);
        private final StringBuilder raw = new StringBuilder();
        private boolean thinking;
        private String toolName;
        private final JButton copy = DesktopTheme.button("复制", false);

        AssistantCard() {
            super(new BorderLayout());
            setOpaque(false);
            JPanel card = new JPanel(new BorderLayout(0, 8));
            card.setOpaque(false);
            JPanel footer = new JPanel(new BorderLayout());
            footer.setOpaque(false);
            copy.setMargin(new Insets(4, 10, 4, 10));
            copy.setToolTipText("复制助手正文");
            copy.setVisible(false);
            copy.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(raw.toString()), null));
            footer.add(copy, BorderLayout.EAST);
            footer.add(state, BorderLayout.CENTER);
            answer.setOpaque(false);
            card.add(answer, BorderLayout.CENTER);
            card.add(footer, BorderLayout.SOUTH);
            add(card, BorderLayout.CENTER);
        }

        void activity(String text) { state.setText(text); state.setVisible(true); }
        void append(String text) {
            if (!thinking) { raw.append(text); activity("正在生成回复…"); }
        }
        void beginReasoning() { thinking = true; activity("正在思考…"); }
        void endReasoning() { thinking = false; activity("正在生成回复…"); }
        void resumeTool() { activity(toolName == null ? "正在执行操作…" : "正在调用 " + toolName + "…"); }
        void tool(ToolEvent event) {
            if(event.state()==ToolEvent.State.OUTPUT){activity("正在调用 "+event.name()+"…");return;}
            if (event.state() == ToolEvent.State.REQUESTED) {
                toolName = event.name();
                raw.setLength(0); // 工具前的解释属于中间过程，完整内容保留在轨迹。
                activity("正在调用 " + event.name() + "…");
            } else activity("正在生成回复…");
        }
        void finish(String result) {
            thinking = false;
            if (!raw.isEmpty()) {
                MarkdownPane body = new MarkdownPane();
                body.setName("message.assistant");
                body.render(raw.toString());
                answer.add(body);
                copy.setVisible(true);
            }
            state.setText(result);
            state.setVisible(!"已完成".equals(result));
        }
    }
}
