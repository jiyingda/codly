package com.jiyingda.codly.command;

import com.jiyingda.codly.memory.MemoryManager;
import com.jiyingda.codly.memory.MemoryManager.MemoryEntry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.List;

@Command(name = "/memory", description = "管理长期记忆")
public class MemoryCommand implements Runnable, CliCommand {

    @Parameters(index = "0", defaultValue = "", description = "子命令：clear 清空所有记忆，delete <key> 删除指定记忆")
    private String action = "";

    @Parameters(index = "1", defaultValue = "", description = "delete 子命令的 key 参数")
    private String actionArg = "";

    @Override
    public void run() {}

    @Override
    public boolean execute(CommandContext ctx) {
        MemoryManager manager = MemoryManager.getInstance();

        switch (action.toLowerCase()) {
            case "clear" -> {
                manager.clearAllMemory();
                System.out.println("所有长期记忆已清空");
            }
            case "delete" -> {
                if (actionArg.isBlank()) {
                    System.out.println("用法：/memory delete <key>");
                } else {
                    String result = manager.removeMemory(actionArg);
                    System.out.println(result);
                }
            }
            default -> listMemories(manager);
        }

        return false;
    }

    private void listMemories(MemoryManager manager) {
        List<MemoryEntry> entries = manager.getAllMemory();
        if (entries.isEmpty()) {
            System.out.println("暂无长期记忆");
            return;
        }
        System.out.println("长期记忆（共 " + entries.size() + " 条）：");
        for (MemoryEntry entry : entries) {
            System.out.println("  [" + entry.key() + "] " + entry.value());
            System.out.println("    创建: " + entry.createdAt() + "  更新: " + entry.updatedAt());
        }
    }
}
