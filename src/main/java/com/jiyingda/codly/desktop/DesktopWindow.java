package com.jiyingda.codly.desktop;

import com.jiyingda.codly.config.Config;
import com.jiyingda.codly.config.SettingsStore;
import com.jiyingda.codly.core.ChatSession;
import com.jiyingda.codly.core.CancellationToken;
import com.jiyingda.codly.core.ToolEvent;
import com.jiyingda.codly.core.ApprovalPolicy;
import com.jiyingda.codly.core.ConversationTitle;
import com.jiyingda.codly.core.TraceEvent;
import com.jiyingda.codly.core.ConversationRecord;
import com.jiyingda.codly.memory.ConversationStore;
import com.jiyingda.codly.llm.AbstractLlmClient;
import com.jiyingda.codly.llm.LlmClient;
import com.jiyingda.codly.llm.ThinkingDepth;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

/** 单窗口、单项目的首版桌面界面。组件仅在 EDT 上访问。 */
final class DesktopWindow extends JFrame {
    private Path workspace;
    private final ChatInput input = new ChatInput(this::sendMessage);
    private final ConversationView transcript = new ConversationView(text -> {
        input.setText(text);
        input.requestFocusInWindow();
    });
    private final TraceView trace = new TraceView();
    private final JTabbedPane conversationTabs = new JTabbedPane(JTabbedPane.TOP);
    private long traceGeneration;
    private final JButton send = new JButton("发送");
    private final JButton settings = new JButton("设置");
    private final JButton stop = new JButton("停止");
    private final JComboBox<String> models = new JComboBox<>();
    private final JComboBox<ThinkingDepth> thinkingDepth = new JComboBox<>(ThinkingDepth.values());
    private final JComboBox<ApprovalPolicy> approvalPolicies = new JComboBox<>(ApprovalPolicy.values());
    private final JLabel status = DesktopTheme.label("正在初始化…", 12, DesktopTheme.MUTED, false);
    private final JLabel stateDot = DesktopTheme.label("●", 10, DesktopTheme.ACCENT, false);
    private final JLabel usage = DesktopTheme.label("本地会话", 12, DesktopTheme.MUTED, false);
    private final JLabel chatTitle = DesktopTheme.label("新的对话", 16, DesktopTheme.TEXT, true);
    private final JLabel rounds = DesktopTheme.label("开始你的第一个任务", 12, DesktopTheme.MUTED, false);
    private final JButton currentChat = DesktopTheme.button("新的对话", false);
    private final JButton newChat = DesktopTheme.button("＋ 新对话", false);
    private final JButton newChatDirectory = DesktopTheme.button("▾", false);
    private final JLabel projectName = DesktopTheme.label("", 14, DesktopTheme.TEXT, true);
    private final JLabel projectPath = DesktopTheme.label("", 11, DesktopTheme.MUTED, false);
    private final JPanel sidebar = new JPanel();
    private final CardLayout actionLayout = new CardLayout();
    private final JPanel actionHost = new JPanel(actionLayout);
    private int turnCount;
    private final SwingConfirmation confirmation = new SwingConfirmation(this);
    private final Timer streamTimer;
    private final StringBuilder pendingText = new StringBuilder();
    private ChatSession session;
    private CancellationToken currentTurn;
    private boolean busy = true;
    private boolean closing;
    private SwingWorker<ChatSession, Void> initialization;
    private final ConversationStore historyStore = new ConversationStore(
            Path.of(System.getProperty("user.home"), ".codly", "conversations"),
            Path.of(System.getProperty("user.home"), ".codly", "memory", "session"));
    private final java.util.concurrent.ExecutorService historyIo = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Codly-Conversation-History"); thread.setDaemon(true); return thread;
    });
    private final DefaultListModel<ConversationRecord.Summary> historyModel = new DefaultListModel<>();
    private final JList<ConversationRecord.Summary> historyList = new JList<>(historyModel);
    private final JButton refreshHistory = DesktopTheme.button("↻", false);
    private final Timer historySaveTimer = new Timer(500, event -> saveCurrentAsync());
    private String conversationId = java.util.UUID.randomUUID().toString();
    private java.time.Instant conversationCreated = java.time.Instant.now();
    private String conversationTitle = "新的对话";
    private ConversationRecord loadedRecord;
    private java.util.concurrent.Future<?> lastHistorySave = java.util.concurrent.CompletableFuture.completedFuture(null);

    DesktopWindow(Path workspace) {
        super("Codly");
        this.workspace = workspace;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(900, 660));
        setSize(1180, 840);
        setLocationRelativeTo(null);
        buildLayout();

        streamTimer = new Timer(50, event -> flushText());
        streamTimer.start();
        send.addActionListener(event -> sendMessage());
        settings.addActionListener(event -> openSettings());
        stop.addActionListener(event -> cancel());
        models.addActionListener(event -> {
            String model = (String) models.getSelectedItem();
            if (!busy && session != null && model != null) {
                session.setModel(model);
                models.setToolTipText("模型：" + model);
            }
        });
        thinkingDepth.addActionListener(event -> {
            ThinkingDepth depth = (ThinkingDepth) thinkingDepth.getSelectedItem();
            if (depth != null) {
                thinkingDepth.setToolTipText(depthDescription(depth));
                if (!busy && session != null && session.supportsThinkingDepth()) session.setThinkingDepth(depth);
            }
        });
        newChat.addActionListener(event -> newConversation());
        historySaveTimer.setRepeats(false);
        historyList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && historyList.getSelectedValue() != null) openHistory(historyList.getSelectedValue());
        });
        refreshHistory.addActionListener(event -> refreshHistory());
        newChatDirectory.setName("chat.chooseWorkspace");
        newChatDirectory.setToolTipText("选择其他目录并开始新对话");
        newChatDirectory.addActionListener(event -> chooseWorkspace());
        currentChat.addActionListener(event -> { transcript.scrollToLatest(); input.requestFocusInWindow(); });
        int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        bindShortcut(java.awt.event.KeyEvent.VK_COMMA, menu, "settings", () -> openSettings());
        bindShortcut(java.awt.event.KeyEvent.VK_N, menu, "new-chat", this::newConversation);
        bindShortcut(java.awt.event.KeyEvent.VK_ESCAPE, 0, "stop", () -> { if (currentTurn != null) cancel(); });
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { closeWindow(); }
        });
        setBusy(true);
        refreshHistory();
        initialize();
    }

    private void buildLayout() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(DesktopTheme.BACKGROUND);
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBackground(DesktopTheme.SIDEBAR);
        sidebar.setBorder(DesktopTheme.padding(20, 16));
        sidebar.setPreferredSize(new Dimension(244, 600));
        sidebar.add(DesktopTheme.label("Codly", 22, DesktopTheme.TEXT, true));
        sidebar.add(Box.createVerticalStrut(5));
        sidebar.add(DesktopTheme.label("你的 AI 编程工作台", 12, DesktopTheme.MUTED, false));
        sidebar.add(Box.createVerticalStrut(20));
        newChat.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        JPanel newChatActions = new JPanel(new BorderLayout(4, 0));
        newChatActions.setOpaque(false);
        newChatActions.setAlignmentX(Component.LEFT_ALIGNMENT);
        newChatActions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        newChatActions.add(newChat, BorderLayout.CENTER);
        newChatActions.add(newChatDirectory, BorderLayout.EAST);
        sidebar.add(newChatActions);
        sidebar.add(Box.createVerticalStrut(18));
        sidebar.add(DesktopTheme.label("工作区", 12, DesktopTheme.MUTED, true));
        sidebar.add(Box.createVerticalStrut(8));
        updateWorkspaceLabels();
        sidebar.add(projectName);
        sidebar.add(Box.createVerticalStrut(4));
        sidebar.add(projectPath);
        sidebar.add(Box.createVerticalStrut(18));
        currentChat.setHorizontalAlignment(SwingConstants.LEFT);
        currentChat.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE_CLASS, "current");
        currentChat.setFont(DesktopTheme.font(13, true));
        currentChat.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        sidebar.add(currentChat);
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(rounds);
        sidebar.add(Box.createVerticalStrut(12));
        JPanel historyHeading = new JPanel(new BorderLayout());
        historyHeading.setOpaque(false);
        historyHeading.setAlignmentX(Component.LEFT_ALIGNMENT);
        historyHeading.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        historyHeading.add(DesktopTheme.label("历史对话", 12, DesktopTheme.MUTED, true), BorderLayout.WEST);
        refreshHistory.setMargin(new Insets(1, 6, 1, 6));
        refreshHistory.setToolTipText("重新加载本地历史记录");
        historyHeading.add(refreshHistory, BorderLayout.EAST);
        sidebar.add(historyHeading);
        historyList.setName("conversation.history");
        historyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        historyList.setBackground(DesktopTheme.SIDEBAR);
        historyList.setFixedCellHeight(58);
        historyList.setFixedCellWidth(194);
        historyList.setCellRenderer((list, value, index, selected, focus) -> {
            JPanel row = new JPanel(new BorderLayout(0, 4));
            row.setBackground(selected ? DesktopTheme.TINT : DesktopTheme.SIDEBAR);
            row.setBorder(DesktopTheme.padding(8, 8));
            JLabel name = DesktopTheme.label(value.title(), 13, DesktopTheme.TEXT, selected);
            name.putClientProperty("html.disable", true);
            row.add(name, BorderLayout.NORTH);
            String project = value.workspace() == null || value.workspace().isBlank() ? "旧版记录" : Path.of(value.workspace()).getFileName() == null ? value.workspace() : Path.of(value.workspace()).getFileName().toString();
            row.add(DesktopTheme.label(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault()).format(value.updatedAt()) + " · " + shorten(project, 9), 11, DesktopTheme.MUTED, false), BorderLayout.SOUTH);
            row.setToolTipText(value.title() + " · " + (value.workspace() == null ? "" : value.workspace()));
            return row;
        });
        JScrollPane historyScroll = DesktopTheme.scroll(historyList, DesktopTheme.SIDEBAR);
        historyScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        historyScroll.setPreferredSize(new Dimension(180, 220));
        historyScroll.setMinimumSize(new Dimension(0, 65));
        sidebar.add(historyScroll);
        sidebar.add(Box.createVerticalStrut(12));
        DesktopTheme.button(settings, false);
        settings.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        sidebar.add(settings);
        sidebar.add(Box.createVerticalStrut(12));
        sidebar.add(DesktopTheme.label("本地项目 · 按所选策略批准", 11, DesktopTheme.MUTED, false));
        root.add(sidebar, BorderLayout.WEST);

        JPanel main = new JPanel(new BorderLayout());
        main.setBackground(DesktopTheme.BACKGROUND);
        JPanel header = new JPanel(new BorderLayout(14, 0));
        header.setBackground(DesktopTheme.BACKGROUND);
        header.setBorder(DesktopTheme.padding(10, 24));
        header.setPreferredSize(new Dimension(340, 56));
        JButton toggle = DesktopTheme.button("☰", false);
        toggle.setToolTipText("展开或收起侧栏");
        toggle.addActionListener(event -> { sidebar.setVisible(!sidebar.isVisible()); root.revalidate(); });
        JPanel title = new JPanel(new BorderLayout(12, 0));
        title.setOpaque(false);
        title.add(toggle, BorderLayout.WEST);
        title.add(chatTitle, BorderLayout.CENTER);
        header.add(title, BorderLayout.CENTER);
        conversationTabs.setName("conversation.tabs");
        conversationTabs.setFont(DesktopTheme.font(14, false));
        conversationTabs.putClientProperty(com.formdev.flatlaf.FlatClientProperties.TABBED_PANE_LEADING_COMPONENT, header);
        conversationTabs.putClientProperty(com.formdev.flatlaf.FlatClientProperties.TABBED_PANE_TAB_HEIGHT, 56);
        conversationTabs.putClientProperty(com.formdev.flatlaf.FlatClientProperties.TABBED_PANE_MINIMUM_TAB_WIDTH, 84);
        conversationTabs.putClientProperty(com.formdev.flatlaf.FlatClientProperties.TABBED_PANE_MAXIMUM_TAB_WIDTH, 84);
        conversationTabs.putClientProperty(com.formdev.flatlaf.FlatClientProperties.TABBED_PANE_TAB_TYPE,
                com.formdev.flatlaf.FlatClientProperties.TABBED_PANE_TAB_TYPE_UNDERLINED);
        conversationTabs.setBackground(DesktopTheme.BACKGROUND);
        conversationTabs.addTab("对话", transcript);
        conversationTabs.addTab("轨迹", trace);
        main.add(conversationTabs, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 6)) {
            @Override public void doLayout() {
                int side = DesktopTheme.contentInset(getWidth());
                if (getInsets().left != side) setBorder(BorderFactory.createEmptyBorder(0, side, 12, side));
                super.doLayout();
            }
        };
        bottom.setBackground(DesktopTheme.BACKGROUND);
        bottom.setBorder(BorderFactory.createEmptyBorder(0, DesktopTheme.CONTENT_GUTTER, 12, DesktopTheme.CONTENT_GUTTER));
        JPanel composer = DesktopTheme.rounded(DesktopTheme.SURFACE, DesktopTheme.LINE, 20);
        composer.setLayout(new BorderLayout(0, 6));
        composer.setBorder(DesktopTheme.padding(8, 12));
        composer.add(DesktopTheme.scroll(input, DesktopTheme.SURFACE), BorderLayout.CENTER);
        JPanel controls = new JPanel(new BorderLayout(12, 0));
        controls.setOpaque(false);
        JPanel policyControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        policyControls.setOpaque(false);
        approvalPolicies.setName("approval.policy");
        approvalPolicies.setFont(DesktopTheme.font(13, false));
        approvalPolicies.setSelectedItem(ApprovalPolicy.ASSISTED);
        approvalPolicies.setToolTipText(ApprovalPolicy.ASSISTED.description());
        approvalPolicies.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                     boolean selected, boolean focused) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focused);
                if (value instanceof ApprovalPolicy policy) label.setToolTipText(policy.description());
                return label;
            }
        });
        approvalPolicies.addActionListener(event -> {
            ApprovalPolicy policy = (ApprovalPolicy) approvalPolicies.getSelectedItem();
            if (policy == null) return;
            approvalPolicies.setToolTipText(policy.description());
            approvalPolicies.setForeground(policy == ApprovalPolicy.FULL ? DesktopTheme.AMBER : DesktopTheme.TEXT);
            if (session != null && !busy && !closing) session.setApprovalPolicy(policy);
        });
        policyControls.add(approvalPolicies);
        controls.add(policyControls, BorderLayout.WEST);
        input.setToolTipText("Enter 发送 · Shift+Enter 换行");
        models.setName("chat.model");
        models.getAccessibleContext().setAccessibleName("模型");
        models.setFont(DesktopTheme.font(13, false));
        models.setPreferredSize(new Dimension(172, 30));
        models.setMaximumRowCount(12);
        thinkingDepth.setName("chat.thinkingDepth");
        thinkingDepth.getAccessibleContext().setAccessibleName("思考深度");
        thinkingDepth.setFont(DesktopTheme.font(13, false));
        thinkingDepth.setPreferredSize(new Dimension(116, 30));
        thinkingDepth.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focused) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focused);
                if (value instanceof ThinkingDepth depth) label.setToolTipText(depthDescription(depth));
                return label;
            }
        });
        DesktopTheme.button(send, true);
        DesktopTheme.button(stop, false);
        actionHost.setOpaque(false);
        actionHost.add(send, "send");
        actionHost.add(stop, "stop");
        JPanel modelControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        modelControls.setOpaque(false);
        modelControls.add(models);
        modelControls.add(thinkingDepth);
        modelControls.add(actionHost);
        controls.add(modelControls, BorderLayout.EAST);
        composer.add(controls, BorderLayout.SOUTH);
        bottom.add(composer, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(20, 0));
        footer.setOpaque(false);
        JPanel state = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        state.setOpaque(false);
        status.setPreferredSize(new Dimension(420, 18));
        state.add(stateDot);
        state.add(status);
        footer.add(state, BorderLayout.CENTER);
        footer.add(usage, BorderLayout.EAST);
        bottom.add(footer, BorderLayout.SOUTH);
        main.add(bottom, BorderLayout.SOUTH);
        root.add(main, BorderLayout.CENTER);
        setContentPane(root);
    }

    private void bindShortcut(int key, int modifiers, String name, Runnable action) {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, modifiers), name);
        getRootPane().getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { action.run(); }
        });
    }

    private ConversationRecord snapshotCurrent() {
        var turns = transcript.snapshotTurns();
        if (turns.isEmpty()) return null;
        return new ConversationRecord(conversationId, conversationTitle, workspace.toString(),
                session != null ? session.getProviderId() : loadedRecord.provider(),
                session != null ? session.getModel() : loadedRecord.model(),
                session != null ? session.getThinkingDepth() : loadedRecord.thinkingDepth(), conversationCreated, java.time.Instant.now(),
                session != null ? session.snapshotMessages() : loadedRecord.messages(), turns, trace.snapshot(),
                session != null ? session.getSessionReference() : loadedRecord.sessionFile());
    }

    private java.util.concurrent.Future<?> queueHistorySave(ConversationRecord record) {
        if (record == null) return java.util.concurrent.CompletableFuture.completedFuture(null);
        lastHistorySave = historyIo.submit(() -> {
            try {
                historyStore.save(record);
                var records = historyStore.list();
                SwingUtilities.invokeLater(() -> { if (!closing) renderHistory(records); });
                return null;
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> { if (!closing) status.setText("历史保存失败：" + errorMessage(error)); });
                throw error;
            }
        });
        return lastHistorySave;
    }

    private void saveCurrentAsync() {
        if (busy || closing) return;
        queueHistorySave(snapshotCurrent());
    }

    private void refreshHistory() {
        if (closing) return;
        historyIo.submit(() -> {
            try {
                var records = historyStore.list();
                SwingUtilities.invokeLater(() -> { if (!closing) renderHistory(records); });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> { if (!closing) status.setText("历史加载失败：" + errorMessage(error)); });
            }
        });
    }

    private void renderHistory(List<ConversationRecord.Summary> records) {
        historyModel.clear();
        for (var record : records) if (!record.id().equals(conversationId)) historyModel.addElement(record);
    }

    private void openHistory(ConversationRecord.Summary summary) {
        if (busy || closing || summary.id().equals(conversationId)) return;
        var saved = queueHistorySave(snapshotCurrent());
        var loaded = historyIo.submit(() -> historyStore.load(summary.id()));
        setBusy(true);
        new SwingWorker<ConversationRecord, Void>() {
            @Override protected ConversationRecord doInBackground() throws Exception {
                saved.get();
                return loaded.get();
            }
            @Override protected void done() {
                try {
                    ConversationRecord record = get();
                    if (closing) { shutdown(); return; }
                    Path target = historyWorkspace(record);
                    if (target == null) {
                        status.setText("未切换历史对话，请先选择该对话的工作区。");
                        historyList.clearSelection();
                        setBusy(false);
                        return;
                    }
                    restoreHistory(record, target);
                } catch (Exception error) {
                    status.setText("打开历史失败：" + errorMessage(error));
                    historyList.clearSelection();
                    setBusy(false);
                    if (closing) shutdown();
                }
            }
        }.execute();
    }

    private Path historyWorkspace(ConversationRecord record) throws java.io.IOException {
        String directory = record.workspace();
        if (directory != null && !directory.isBlank() && java.nio.file.Files.isDirectory(Path.of(directory))) {
            return Path.of(directory).toRealPath();
        }
        JFileChooser chooser = new JFileChooser(workspace.toFile());
        chooser.setName("history.chooseWorkspace");
        chooser.setDialogTitle(directory == null || directory.isBlank()
                ? "为历史对话选择工作区（仅首次需要）" : "原工作区不可访问，请重新选择");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (directory != null && !directory.isBlank()) chooser.setToolTipText("原工作区：" + directory);
        if (chooser.showDialog(this, "使用此工作区") != JFileChooser.APPROVE_OPTION) return null;
        Path target = chooser.getSelectedFile().toPath().toRealPath();
        if (!java.nio.file.Files.isDirectory(target)) throw new java.io.IOException("工作区必须是目录");
        return target;
    }

    private void restoreHistory(ConversationRecord record, Path target) {
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                if (session != null) session.restoreConversation(record, target);
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    loadedRecord = record;
                    conversationId = record.id();
                    conversationCreated = record.createdAt();
                    workspace = target;
                    updateWorkspaceLabels();
                    traceGeneration++;
                    trace.restore(record.trace());
                    transcript.restore(record.turns());
                    conversationTabs.setSelectedIndex(0);
                    updateTitle(record.title());
                    input.setText("");
                    turnCount = record.turns().size();
                    rounds.setText("当前对话 · " + turnCount + " 轮");
                    usage.setText("本地会话");
                    if (session != null) updateModels();
                    else {
                        models.removeAllItems();
                        if (record.model() != null && !record.model().isBlank()) models.addItem(record.model());
                        thinkingDepth.setSelectedItem(record.thinkingDepth());
                    }
                    status.setText((session == null ? "已打开历史对话，配置模型后可继续。" : "已恢复对话，可继续交流。")
                            + (session != null && (!session.getProviderId().equals(record.provider()) || !session.getModel().equals(record.model())) ? " 模型沿用当前设置。" : ""));
                    refreshHistory();
                } catch (Exception error) {
                    status.setText("打开历史失败：" + errorMessage(error));
                    historyList.clearSelection();
                }
                setBusy(false);
                if (closing) { shutdown(); return; }
                input.requestFocusInWindow();
            }
        }.execute();
    }

    private void updateTitle(String title) {
        conversationTitle = title;
        setTitle("Codly — " + title);
        chatTitle.setText(shorten(title, 26));
        chatTitle.setToolTipText(title);
        currentChat.setText(shorten(title, 18));
        currentChat.setToolTipText(title);
        historySaveTimer.restart();
    }

    private void newConversation() {
        newConversation(workspace);
    }

    private void chooseWorkspace() {
        if (busy || closing) return;
        JFileChooser chooser = new JFileChooser(workspace.toFile());
        chooser.setDialogTitle("选择新对话的工作目录");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) newConversation(chooser.getSelectedFile().toPath());
    }

    private void updateWorkspaceLabels() {
        projectName.setName("workspace.name");
        projectPath.setName("workspace.path");
        String project = workspace.getFileName() == null ? workspace.toString() : workspace.getFileName().toString();
        projectName.setText(shorten(project, 22));
        projectName.setToolTipText(workspace.toString());
        projectPath.setText(shorten(workspace.toString(), 26));
        projectPath.setToolTipText(workspace.toString());
    }

    private void newConversation(Path target) {
        if (busy || closing || session == null) return;
        java.util.concurrent.Future<?> saved = queueHistorySave(snapshotCurrent());
        setBusy(true);
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception { saved.get(); session.newConversation(target); return null; }
            @Override protected void done() {
                try {
                    get();
                    workspace = session.getWorkspace();
                    updateWorkspaceLabels();
                    traceGeneration++;
                    trace.clear();
                    conversationTabs.setSelectedIndex(0);
                    transcript.welcome();
                    conversationId = java.util.UUID.randomUUID().toString();
                    conversationCreated = java.time.Instant.now();
                    loadedRecord = null;
                    updateTitle("新的对话");
                    input.setText("");
                    turnCount = 0;
                    rounds.setText("开始你的下一个任务");
                    usage.setText("本地会话");
                    status.setText("已保存上一段对话，可以开始新的任务。");
                    refreshHistory();
                } catch (Exception e) { status.setText("新建对话失败：" + errorMessage(e)); }
                setBusy(false);
                if (closing) { shutdown(); return; }
                input.requestFocusInWindow();
            }
        }.execute();
    }

    private static String shorten(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
    }

    private void initialize() {
        ConversationRecord restore = loadedRecord;
        initialization = new SwingWorker<>() {
            @Override protected ChatSession doInBackground() {
                Config.getInstance().load();
                ChatSession result = ChatSession.openWithApprovals(workspace, confirmation, title -> SwingUtilities.invokeLater(() -> {
                    if (!closing) updateTitle(title);
                }));
                if (restore != null) result.restoreConversation(restore, workspace);
                return result;
            }

            @Override protected void done() {
                try {
                    session = get();
                    session.setApprovalPolicy((ApprovalPolicy) approvalPolicies.getSelectedItem());
                    if (closing) { shutdown(); return; }
                    updateModels();
                    status.setText("就绪");
                    setBusy(false);
                    input.requestFocusInWindow();
                } catch (Exception e) {
                    if (closing) { disposeWindow(); return; }
                    status.setText("初始化失败，请点击“设置”补齐模型配置。");
                    transcript.notice("点击侧栏底部的“设置”，填入 API Key 和默认模型后即可开始。\n" + errorMessage(e));
                    setBusy(false);
                }
            }
        };
        initialization.execute();
    }

    private void updateModels() {
        models.removeAllItems();
        var available = new java.util.LinkedHashSet<>(LlmClient.getAvailableModels());
        available.add(session.getModel());
        for (String model : available) models.addItem(model);
        models.setSelectedItem(session.getModel());
        models.setToolTipText("模型：" + session.getModel());
        thinkingDepth.setSelectedItem(session.getThinkingDepth());
        thinkingDepth.setToolTipText(depthDescription(session.getThinkingDepth()));
    }

    private static String depthDescription(ThinkingDepth depth) {
        return switch (depth) {
            case OFF -> "关闭思考，直接生成回复；仅对支持切换的模型生效";
            case LOW -> "浅度思考，优先响应速度；实际效果由所选模型决定";
            case HIGH -> "深度思考，适合需要分析的复杂任务";
            case MAX -> "极深思考，给复杂问题更多推理空间，响应可能更慢";
        };
    }

    private void openSettings() {
        if (busy || closing) return;
        setBusy(true);
        SettingsStore store = new SettingsStore(Path.of(Config.getConfigPath()));
        new SwingWorker<SettingsStore.Document, Void>() {
            @Override protected SettingsStore.Document doInBackground() throws Exception { return store.read(); }
            @Override protected void done() {
                if (closing) { if (session == null) disposeWindow(); else shutdown(); return; }
                setBusy(false);
                try {
                    SettingsDialog dialog = new SettingsDialog(DesktopWindow.this, store, get(), () -> applySettings());
                    dialog.setVisible(true);
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(DesktopWindow.this, errorMessage(e),
                            "无法打开设置", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void applySettings() {
        if (closing) return;
        setBusy(true);
        if (session == null) { initialize(); return; }
        status.setText("正在应用设置…");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                session.reloadConfiguration();
                return null;
            }
            @Override protected void done() {
                if (closing) { shutdown(); return; }
                try {
                    get();
                    updateModels();
                    status.setText("设置已应用，MCP 将在下一次对话时连接并发现工具。");
                } catch (Exception e) {
                    status.setText("配置已保存，但应用失败：" + errorMessage(e));
                }
                setBusy(false);
            }
        }.execute();
    }

    private void sendMessage() {
        String message = input.getText().trim();
        if (busy || closing || session == null || message.isBlank()) return;
        session.setApprovalPolicy((ApprovalPolicy) approvalPolicies.getSelectedItem());
        if (message.matches("^/(help|clear|compact|model|memory|skill|kb|quit|exit|sysinfo|gen-skill)(?:\\s.*)?$")
                || message.startsWith("/skill-")) {
            status.setText("桌面版请使用界面控件；斜杠命令请在 CLI 中执行。");
            return;
        }
        transcript.beginTurn(message);
        if (turnCount == 0) updateTitle(ConversationTitle.fallback(message));
        turnCount++;
        rounds.setText("当前对话 · " + turnCount + " 轮");
        usage.setText("正在生成…");
        input.setText("");
        confirmation.beginTurn();
        CancellationToken turn = new CancellationToken();
        currentTurn = turn;
        long generation = traceGeneration;
        setBusy(true);
        status.setText("正在生成…");
        new SwingWorker<String, Update>() {
            @Override protected String doInBackground() {
                return session.send(message, token -> publish(new Update(token, null)), turn,
                        event -> publish(new Update(null, event)), event -> publish(new Update(null, null, event)));
            }

            @Override protected void process(List<Update> chunks) {
                if (closing || generation != traceGeneration) return;
                for (Update update : chunks) {
                    if (update.trace() != null) {
                        TraceEvent event = update.trace();
                        trace.accept(event);
                        historySaveTimer.restart();
                        if (currentTurn == turn && event.kind() == TraceEvent.Kind.APPROVAL && event.phase() == TraceEvent.Phase.START) {
                            transcript.activity("正在等待操作批准…");
                        } else if (currentTurn == turn && event.kind() == TraceEvent.Kind.APPROVAL && event.phase() == TraceEvent.Phase.COMPLETE) {
                            transcript.resumeTool();
                        }
                        continue;
                    }
                    if (update.tool() != null) {
                        flushText();
                        transcript.tool(update.tool());
                        continue;
                    }
                    String token = update.text();
                    if (token.startsWith(AbstractLlmClient.USAGE_PREFIX)) {
                        String[] counts = token.substring(AbstractLlmClient.USAGE_PREFIX.length()).split(":");
                        if (counts.length == 3) {
                            usage.setText("本轮 " + counts[2] + " tokens");
                            usage.setToolTipText("输入 " + counts[0] + " / 输出 " + counts[1]);
                        }
                    } else if (AbstractLlmClient.REASONING_START.equals(token)) {
                        flushText();
                        transcript.beginReasoning();
                    } else if (AbstractLlmClient.REASONING_END.equals(token)) {
                        flushText();
                        transcript.endReasoning();
                    } else pendingText.append(token);
                }
            }

            @Override protected void done() {
                boolean failed = false;
                try {
                    get();
                    if (turn.isCancelled()) status.setText("已停止");
                    if ("正在生成…".equals(status.getText())) status.setText("就绪");
                } catch (Exception e) {
                    failed = true;
                    flushText();
                    transcript.endReasoning();
                    pendingText.append("\n请求失败：").append(errorMessage(e));
                    status.setText("请求失败，可重新发送");
                }
                flushText();
                transcript.finish(failed ? "请求失败" : turn.isCancelled() ? "已停止" : "已完成");
                if ("正在生成…".equals(usage.getText())) usage.setText("本地会话");
                currentTurn = null;
                setBusy(false);
                if (!closing) saveCurrentAsync();
                if (closing) shutdown();
            }
        }.execute();
    }

    private void setBusy(boolean value) {
        busy = value;
        send.setEnabled(!value && session != null && !closing);
        models.setEnabled(!value && session != null && !closing);
        thinkingDepth.setEnabled(!value && session != null && session.supportsThinkingDepth() && !closing);
        approvalPolicies.setEnabled(!value && session != null && !closing);
        settings.setEnabled(!value && !closing);
        stop.setEnabled(value && currentTurn != null && !closing);
        newChat.setEnabled(!value && session != null && !closing);
        newChatDirectory.setEnabled(!value && session != null && !closing);
        historyList.setEnabled(!value && !closing);
        refreshHistory.setEnabled(!value && !closing);
        actionLayout.show(actionHost, currentTurn != null && value ? "stop" : "send");
        stateDot.setForeground(value ? DesktopTheme.ACCENT : session == null ? DesktopTheme.AMBER : DesktopTheme.GREEN);
        status.setToolTipText(status.getText());
    }

    private void cancel() {
        confirmation.cancel();
        if (currentTurn != null) currentTurn.cancel();
        stop.setEnabled(false);
        status.setText("已请求停止，等待当前操作结束…");
    }

    private void closeWindow() {
        if (closing) return;
        closing = true;
        cancel();
        send.setEnabled(false);
        models.setEnabled(false);
        thinkingDepth.setEnabled(false);
        settings.setEnabled(false);
        newChat.setEnabled(false);
        newChatDirectory.setEnabled(false);
        historyList.setEnabled(false);
        refreshHistory.setEnabled(false);
        status.setText("正在保存会话并退出…");
        if (session != null && !busy) shutdown();
        else if (session == null && initialization.isDone() && !busy) shutdown();
        // 初始化或请求正在执行时，其 done 回调负责关闭服务。
    }

    private void shutdown() {
        queueHistorySave(snapshotCurrent());
        java.util.concurrent.Future<?> saved = lastHistorySave;
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                try { saved.get(); }
                finally { if (session != null) session.close(); }
                return null;
            }

            @Override protected void done() {
                try { get(); }
                catch (Exception e) { System.err.println("保存会话失败：" + errorMessage(e)); }
                disposeWindow();
            }
        }.execute();
    }

    private void disposeWindow() {
        historySaveTimer.stop();
        historyIo.shutdown();
        streamTimer.stop();
        dispose();
    }

    private void flushText() {
        if (pendingText.length() == 0) return;
        append(pendingText.toString());
        pendingText.setLength(0);
    }

    private void append(String text) { transcript.append(text); }

    private record Update(String text, ToolEvent tool, TraceEvent trace) {
        Update(String text, ToolEvent tool) { this(text, tool, null); }
    }

    private static String errorMessage(Exception error) {
        Throwable cause = error instanceof ExecutionException ? error.getCause() : error;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
