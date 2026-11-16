package com.jiyingda.codly.cli;

/** 斜杠命令使用的 CLI 交互能力，不向共享核心引入终端依赖。 */
public interface CliActions {
    void resume(String selector);
    void permissions(String mode);
    void trace(String selection);
    default void context(){throw new UnsupportedOperationException("尚未初始化上下文入口");}
    default void mcp(String action){throw new UnsupportedOperationException("尚未初始化 MCP 入口");}
    default void tasks(java.util.List<String> args){throw new UnsupportedOperationException("尚未初始化任务入口");}
    default void editor(){throw new UnsupportedOperationException("尚未初始化编辑器入口");}
    default void draft(){throw new UnsupportedOperationException("尚未初始化草稿入口");}
}
