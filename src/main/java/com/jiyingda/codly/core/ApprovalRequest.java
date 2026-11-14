package com.jiyingda.codly.core;

/** 批准内容与可记忆范围。范围由执行层生成，界面不自行推断命令权限。 */
public record ApprovalRequest(String summary, ExecutionPermission permission, ApprovalScope scope) {}
