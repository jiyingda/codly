package com.jiyingda.codly.desktop;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.jiyingda.codly.config.Config;
import com.jiyingda.codly.config.SettingsStore;
import com.jiyingda.codly.skill.Skill;
import com.jiyingda.codly.skill.SkillLoader;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.*;

/** 设置界面只编辑配置文档；校验、持久化和会话应用由独立服务处理。 */
final class SettingsDialog extends JDialog {
    private final SettingsStore store;
    private final SettingsStore.Document original;
    private final Runnable onSaved;
    private final JComboBox<String> provider = new JComboBox<>(new String[]{"qwen", "deepseek"});
    private final JCheckBox thinking = new JCheckBox("启用思考");
    private final Map<String, JTextField> fields = new LinkedHashMap<>();
    private final JTextArea directories = new JTextArea(3, 45);
    private final JTextArea mcp = new JTextArea(16, 60);
    private final JTextPane preview = new JTextPane();
    private final JLabel feedback = new JLabel(" ");
    private final JButton save = new JButton("保存并应用");
    private final JButton refresh = new JButton("刷新 Skills");
    private final JButton cancel = new JButton("取消");
    private final DefaultTableModel skills = new DefaultTableModel(new String[]{"默认激活", "名称", "说明"}, 0) {
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int row, int column) { return column == 0; }
    };
    private final JTable skillTable = new JTable(skills);
    private final Map<String, Skill> scanned = new HashMap<>();
    private final Set<String> initiallyEnabled = new LinkedHashSet<>();
    private boolean saving;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel skillSummary = DesktopTheme.label("正在扫描…", 11, DesktopTheme.MUTED, false);

    SettingsDialog(JFrame owner, SettingsStore store, SettingsStore.Document document, Runnable onSaved) {
        super(owner, "Codly 设置", true);
        this.store = store;
        this.original = document;
        this.onSaved = onSaved;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(820, 620));
        setSize(960, 780);
        setLocationRelativeTo(owner);
        DesktopTheme.tabs(tabs);
        tabs.addTab("模型", modelPanel(document.values()));
        tabs.addTab("Skills", skillsPanel(document.values()));
        tabs.addTab("MCP", mcpPanel(document.values()));
        DesktopTheme.button(save, true);
        DesktopTheme.button(cancel, false);
        DesktopTheme.button(refresh, false);
        feedback.setFont(DesktopTheme.font(11, false));
        feedback.setForeground(DesktopTheme.MUTED);
        feedback.setText("修改后保存，即可应用到当前会话。");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.setOpaque(false);
        actions.add(cancel);
        actions.add(save);
        JPanel footer = new JPanel(new BorderLayout(0, 8));
        footer.setOpaque(false);
        footer.add(feedback, BorderLayout.CENTER);
        footer.add(actions, BorderLayout.SOUTH);
        JPanel content = new JPanel(new BorderLayout(0, 20));
        content.setBackground(DesktopTheme.BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(22, 20, 16, 20));
        JPanel heading = new JPanel(new BorderLayout(0, 8));
        heading.setOpaque(false);
        heading.add(DesktopTheme.label("设置工作台", 23, DesktopTheme.TEXT, true), BorderLayout.NORTH);
        JLabel location = DesktopTheme.label("配置保存在本机 · " + Config.getConfigPath(), 11, DesktopTheme.MUTED, false);
        location.setToolTipText(Config.getConfigPath());
        heading.add(location, BorderLayout.CENTER);
        content.add(heading, BorderLayout.NORTH);
        content.add(tabs, BorderLayout.CENTER);
        content.add(footer, BorderLayout.SOUTH);
        setContentPane(content);
        save.addActionListener(event -> save());
        cancel.addActionListener(event -> dispose());
        getRootPane().registerKeyboardAction(event -> { if (!saving) dispose(); },
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        reloadSkills();
    }

    void selectSection(int section) { tabs.setSelectedIndex(Math.max(0, Math.min(2, section))); }

    private JComponent modelPanel(JSONObject values) {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(DesktopTheme.BACKGROUND);
        content.setBorder(DesktopTheme.padding(0, 4));
        JPanel general = new JPanel(new GridBagLayout());
        general.setOpaque(false);
        provider.setName("provider");
        provider.setFont(DesktopTheme.font(13, false));
        provider.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focused) {
                return super.getListCellRendererComponent(list, "deepseek".equals(value) ? "DeepSeek" : "通义千问 / Qwen", index, selected, focused);
            }
        });
        provider.setSelectedItem(values.getString("provider") == null ? "qwen" : values.getString("provider").trim().toLowerCase());
        thinking.setOpaque(false);
        thinking.setSelected(values.getBooleanValue("enableThinking"));
        row(general, 0, "当前服务商", provider);
        row(general, 1, "思考模式", thinking);
        content.add(section("模型服务", "选择当前会话使用的模型服务商。", general));
        content.add(Box.createVerticalStrut(14));
        CardLayout layout = new CardLayout();
        JPanel cards = new JPanel(layout) {
            @Override public Dimension getPreferredSize() {
                for (Component component : getComponents()) if (component.isVisible()) return component.getPreferredSize();
                return super.getPreferredSize();
            }
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        cards.setOpaque(false);
        cards.setAlignmentX(LEFT_ALIGNMENT);
        JPanel qwen = new JPanel(new GridBagLayout());
        qwen.setOpaque(false);
        row(qwen, 0, "API Key", field("qwen.key", values.getString("apiKey"), true));
        row(qwen, 1, "API URL", field("qwen.url", values.getString("apiUrl"), false));
        row(qwen, 2, "默认模型", field("qwen.model", values.getString("defaultModel"), false));
        row(qwen, 3, "可选模型", field("qwen.models", join(values.getJSONArray("availableModels")), false));
        fields.get("qwen.models").setToolTipText("多个模型用逗号分隔");
        cards.add(section("通义千问", "API URL 留空使用默认地址；可选模型用逗号分隔。", qwen), "qwen");
        JSONObject deepseek = values.getJSONObject("deepseek");
        if (deepseek == null) deepseek = new JSONObject();
        JPanel deep = new JPanel(new GridBagLayout());
        deep.setOpaque(false);
        row(deep, 0, "API Key", field("deepseek.key", deepseek.getString("apiKey"), true));
        row(deep, 1, "API URL", field("deepseek.url", deepseek.getString("apiUrl"), false));
        row(deep, 2, "默认模型", field("deepseek.model", deepseek.getString("model"), false));
        row(deep, 3, "可选模型", field("deepseek.models", join(deepseek.getJSONArray("availableModels")), false));
        row(deep, 4, "思考强度", field("deepseek.effort", deepseek.getString("reasoningEffort"), false));
        fields.get("deepseek.effort").setToolTipText("none / low / high / max，留空使用 high");
        cards.add(section("DeepSeek", "默认模型留空使用 deepseek-flash；思考强度默认为 high。", deep), "deepseek");
        layout.show(cards, (String) provider.getSelectedItem());
        provider.addActionListener(event -> { layout.show(cards, (String) provider.getSelectedItem()); content.revalidate(); });
        content.add(cards);
        content.add(Box.createVerticalStrut(14));
        JPanel search = new JPanel(new GridBagLayout());
        search.setOpaque(false);
        row(search, 0, "API Key", field("iqs.key", values.getString("iqsApiKey"), true));
        content.add(section("联网搜索 · 可选", "配置通义搜索密钥，让 Codly 获取互联网信息。", search));
        content.add(Box.createVerticalGlue());
        for (Component component : content.getComponents()) {
            if (component instanceof JComponent child) child.setAlignmentX(LEFT_ALIGNMENT);
        }
        return DesktopTheme.scroll(content, DesktopTheme.BACKGROUND);
    }

    private static JPanel section(String title, String description, JComponent body) {
        JPanel card = DesktopTheme.rounded(DesktopTheme.SURFACE, DesktopTheme.LINE, 16);
        card.setLayout(new BorderLayout(0, 12));
        card.setBorder(DesktopTheme.padding(16, 18));
        JPanel heading = new JPanel(new BorderLayout(0, 6));
        heading.setOpaque(false);
        heading.add(DesktopTheme.label(title, 14, DesktopTheme.TEXT, true), BorderLayout.NORTH);
        heading.add(DesktopTheme.label(description, 11, DesktopTheme.MUTED, false), BorderLayout.CENTER);
        card.add(heading, BorderLayout.NORTH);
        card.add(body, BorderLayout.CENTER);
        card.setAlignmentX(LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
        return card;
    }

    private JComponent skillsPanel(JSONObject values) {
        directories.setName("skills.directories");
        skillTable.setName("skills.enabled");
        JSONObject config = values.getJSONObject("skills");
        if (config != null) {
            directories.setText(lines(config.getJSONArray("directories")));
            if (config.getJSONArray("enabled") != null) initiallyEnabled.addAll(config.getJSONArray("enabled").toJavaList(String.class));
        }
        DesktopTheme.field(directories);
        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.setOpaque(false);
        JLabel root = DesktopTheme.label("默认来源  " + SkillLoader.skillsRoot(), 11, DesktopTheme.MUTED, false);
        root.setToolTipText(SkillLoader.skillsRoot().toString());
        top.add(root, BorderLayout.NORTH);
        JPanel source = new JPanel(new BorderLayout(0, 6));
        source.setOpaque(false);
        source.add(DesktopTheme.label("额外目录 · 每行一个绝对路径，支持 ~/", 12, DesktopTheme.TEXT, true), BorderLayout.NORTH);
        source.add(DesktopTheme.scroll(directories, DesktopTheme.SURFACE), BorderLayout.CENTER);
        top.add(source, BorderLayout.CENTER);
        refresh.addActionListener(event -> reloadSkills());
        JPanel scan = new JPanel(new BorderLayout());
        scan.setOpaque(false);
        scan.add(skillSummary, BorderLayout.WEST);
        scan.add(refresh, BorderLayout.EAST);
        top.add(scan, BorderLayout.SOUTH);
        skillTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        skillTable.setRowHeight(32);
        skillTable.setFont(DesktopTheme.font(12, false));
        skillTable.setShowGrid(false);
        skillTable.setIntercellSpacing(new Dimension(0, 0));
        skillTable.setSelectionBackground(DesktopTheme.TINT);
        skillTable.setSelectionForeground(DesktopTheme.TEXT);
        skillTable.getTableHeader().setFont(DesktopTheme.font(11, true));
        skillTable.getTableHeader().setBackground(DesktopTheme.BACKGROUND);
        skillTable.getTableHeader().setPreferredSize(new Dimension(0, 32));
        skillTable.getColumnModel().getColumn(0).setMaxWidth(85);
        skillTable.getSelectionModel().addListSelectionListener(event -> {
            int selected = skillTable.getSelectedRow();
            if (selected < 0) return;
            Skill skill = scanned.get((String) skills.getValueAt(selected, 1));
            preview.setText(skill == null ? "未找到此 Skill，请检查目录后刷新。" :
                    skill.getDir().resolve("SKILL.md") + "\n\n" + skill.toFullPromptSection());
            preview.setCaretPosition(0);
        });
        preview.setEditable(false);
        preview.setText("选择一个 Skill 查看说明与完整内容。");
        DesktopTheme.field(preview);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, DesktopTheme.scroll(skillTable, DesktopTheme.SURFACE), DesktopTheme.scroll(preview, DesktopTheme.SURFACE));
        split.setResizeWeight(0.52);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(8);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(DesktopTheme.BACKGROUND);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        panel.add(top, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        panel.add(DesktopTheme.label("勾选后保存即可激活 · 同名 Skill 优先使用默认目录", 11, DesktopTheme.MUTED, false), BorderLayout.SOUTH);
        return panel;
    }

    private JComponent mcpPanel(JSONObject values) {
        mcp.setName("mcpServers");
        Object storedServers = values.get("mcpServers");
        mcp.setText(storedServers == null ? "{}" : JSON.toJSONString(storedServers, true));
        DesktopTheme.field(mcp);
        mcp.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        mcp.setBackground(DesktopTheme.CODE);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(DesktopTheme.BACKGROUND);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        JPanel heading = new JPanel(new BorderLayout(0, 8));
        heading.setOpaque(false);
        heading.add(DesktopTheme.label("MCP 服务器", 16, DesktopTheme.TEXT, true), BorderLayout.NORTH);
        heading.add(DesktopTheme.label("支持 HTTP / Streamable HTTP；保存后在下次对话自动连接，可先测试连接。", 12, DesktopTheme.MUTED, false), BorderLayout.CENTER);
        panel.add(heading, BorderLayout.NORTH);
        panel.add(DesktopTheme.scroll(mcp, DesktopTheme.CODE), BorderLayout.CENTER);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.setOpaque(false);
        JButton validate = DesktopTheme.button("校验 MCP", false);
        validate.addActionListener(event -> {
            try { SettingsStore.parseMcp(mcp.getText()); feedback.setText("MCP 配置格式有效。"); }
            catch (IllegalArgumentException e) { feedback.setText(e.getMessage()); }
        });
        controls.add(validate);
        JButton format = DesktopTheme.button("格式化 JSON", false);
        format.addActionListener(event -> {
            try { mcp.setText(JSON.toJSONString(SettingsStore.parseMcp(mcp.getText()), true)); }
            catch (IllegalArgumentException e) { feedback.setText(e.getMessage()); }
        });
        controls.add(format);
        JButton test = DesktopTheme.button("测试连接", false);
        test.setName("mcp.testConnection");
        test.addActionListener(event -> {
            final JSONObject servers;
            try { servers = SettingsStore.parseMcp(mcp.getText()); }
            catch (IllegalArgumentException error) { feedback.setText(error.getMessage()); return; }
            test.setEnabled(false);
            feedback.setText("正在连接 MCP 并发现工具…");
            new SwingWorker<String, Void>() {
                @Override protected String doInBackground() {
                    try (var registry = new com.jiyingda.codly.mcp.McpRegistry(servers)) {
                        registry.load(new com.jiyingda.codly.core.CancellationToken(), null);
                        return registry.status();
                    }
                }
                @Override protected void done() {
                    test.setEnabled(true);
                    if (!isDisplayable()) return;
                    try {
                        JTextArea result = new JTextArea(get(), 8, 55);
                        result.setEditable(false); result.setLineWrap(true); result.setWrapStyleWord(true);
                        DesktopTheme.field(result);
                        JOptionPane.showMessageDialog(SettingsDialog.this, new JScrollPane(result), "MCP 连接结果", JOptionPane.INFORMATION_MESSAGE);
                        feedback.setText("MCP 连接测试完成；保存后应用配置。");
                    } catch (Exception error) { feedback.setText("MCP 连接测试失败。"); }
                }
            }.execute();
        });
        controls.add(test);
        panel.add(controls, BorderLayout.SOUTH);
        return panel;
    }

    private void reloadSkills() {
        Set<String> enabled = skills.getRowCount() == 0 ? new LinkedHashSet<>(initiallyEnabled) : selectedSkills();
        List<String> paths = parseLines(directories.getText());
        refresh.setEnabled(false);
        save.setEnabled(false);
        new SwingWorker<List<Skill>, Void>() {
            @Override protected List<Skill> doInBackground() { return SkillLoader.loadAll(SkillLoader.roots(paths)); }
            @Override protected void done() {
                try {
                    List<Skill> available = get();
                    scanned.clear();
                    skills.setRowCount(0);
                    for (Skill skill : available) {
                        scanned.put(skill.getName(), skill);
                        skills.addRow(new Object[]{enabled.contains(skill.getName()), skill.getName(), skill.getDescription()});
                    }
                    for (String missing : enabled) {
                        if (!scanned.containsKey(missing)) skills.addRow(new Object[]{true, missing, "未找到，请检查目录"});
                    }
                    skillSummary.setText("已扫描 " + available.size() + " 个 Skill");
                } catch (Exception e) { skillSummary.setText("无法扫描，请检查目录。"); }
                refresh.setEnabled(true);
                save.setEnabled(true);
            }
        }.execute();
    }

    private JTextField field(String name, String value, boolean secret) {
        JTextField field = secret ? new JPasswordField(30) : new JTextField(30);
        DesktopTheme.field(field);
        field.setName(name);
        field.setText(value == null ? "" : value);
        fields.put(name, field);
        return field;
    }

    private static void row(JPanel panel, int row, String label, JComponent field) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(7, 8, 7, 8);
        panel.add(DesktopTheme.label(label, 12, DesktopTheme.MUTED, false), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, constraints);
    }

    private Set<String> selectedSkills() {
        if (skillTable.isEditing()) skillTable.getCellEditor().stopCellEditing();
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < skills.getRowCount(); i++) {
            if (Boolean.TRUE.equals(skills.getValueAt(i, 0))) names.add((String) skills.getValueAt(i, 1));
        }
        return names;
    }

    private JSONObject collect() {
        JSONObject values = JSON.parseObject(JSON.toJSONString(original.values()));
        values.put("provider", provider.getSelectedItem());
        values.put("enableThinking", thinking.isSelected());
        put(values, "apiKey", "qwen.key");
        put(values, "apiUrl", "qwen.url");
        put(values, "defaultModel", "qwen.model");
        values.put("availableModels", new JSONArray(parseModels(text("qwen.models"))));
        put(values, "iqsApiKey", "iqs.key");
        JSONObject deepseek = values.getJSONObject("deepseek");
        if (deepseek == null) deepseek = new JSONObject(true);
        put(deepseek, "apiKey", "deepseek.key");
        put(deepseek, "apiUrl", "deepseek.url");
        put(deepseek, "model", "deepseek.model");
        put(deepseek, "reasoningEffort", "deepseek.effort");
        deepseek.put("availableModels", new JSONArray(parseModels(text("deepseek.models"))));
        values.put("deepseek", deepseek);
        JSONObject skillConfig = values.getJSONObject("skills");
        if (skillConfig == null) skillConfig = new JSONObject(true);
        skillConfig.put("directories", new JSONArray(parseLines(directories.getText())));
        skillConfig.put("enabled", new JSONArray(new ArrayList<>(selectedSkills())));
        values.put("skills", skillConfig);
        values.put("mcpServers", SettingsStore.parseMcp(mcp.getText()));
        SettingsStore.validate(values);
        return values;
    }

    private void put(JSONObject values, String key, String field) { values.put(key, text(field)); }

    private String text(String name) {
        JTextField field = fields.get(name);
        if (field instanceof JPasswordField password) {
            char[] chars = password.getPassword();
            try { return new String(chars).trim(); }
            finally { Arrays.fill(chars, '\0'); }
        }
        return field.getText().trim();
    }

    private void save() {
        JSONObject values;
        try { values = collect(); }
        catch (IllegalArgumentException e) { feedback.setText(e.getMessage()); return; }
        saving = true;
        save.setEnabled(false);
        cancel.setEnabled(false);
        refresh.setEnabled(false);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        feedback.setText("正在保存…");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                store.save(new SettingsStore.Document(values, original.original()));
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    dispose();
                    onSaved.run();
                } catch (Exception e) {
                    saving = false;
                    save.setEnabled(true);
                    cancel.setEnabled(true);
                    refresh.setEnabled(true);
                    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    feedback.setText(cause.getMessage());
                }
            }
        }.execute();
    }

    private static List<String> parseModels(String text) {
        return Arrays.stream(text.split("[,\\n]")).map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
    }

    private static List<String> parseLines(String text) {
        return text.lines().map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
    }

    private static String join(JSONArray array) { return array == null ? "" : String.join(", ", array.toJavaList(String.class)); }
    private static String lines(JSONArray array) { return array == null ? "" : String.join("\n", array.toJavaList(String.class)); }
}
