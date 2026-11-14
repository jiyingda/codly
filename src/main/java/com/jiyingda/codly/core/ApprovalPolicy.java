package com.jiyingda.codly.core;

import java.nio.file.Path;

/** 用户显式选择的执行确认策略，与界面和模型提供方无关。 */
public enum ApprovalPolicy {
    ASK("请求批准", "只读查询自动执行；文件修改、构建测试及敏感操作请求确认"),
    ASSISTED("帮我批准", "自动批准项目脚本、文件整理、构建和临时文件操作；删除、发布等仍需确认"),
    FULL("完全授权", "自动批准所有需确认的操作，包括系统命令；现有文件访问限制仍生效");

    private final String label;
    private final String description;

    ApprovalPolicy(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String description() { return description; }
    @Override public String toString() { return label; }

    public String promptSection() {
        return "\n\n## Approval Policy\n当前用户选择：" + label + "。" + description
                + "。执行批准由工具层处理，直接调用完成任务所需的工具，不要先要求用户在聊天中回复确认。"
                + "工具拒绝后应停止该操作，不得通过更换工具或包装命令绕过拒绝。"
                + "授权策略只决定执行确认，不扩大用户请求的任务范围。";
    }

    boolean approves(String tool, String arguments, Path workspace) {
        if (this == FULL) return true;
        ExecutionPermission permission = ExecutionPermission.classify(tool, arguments, workspace);
        return permission == ExecutionPermission.READ_ONLY
                || this == ASSISTED && permission == ExecutionPermission.PROJECT;
    }
}
