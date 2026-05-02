package com.jiyingda.codly.command;

import com.jiyingda.codly.systeminfo.SystemInfoManager;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * /sysinfo 命令：显示当前系统与项目环境信息。
 * 加 --refresh 参数可强制重新采集。
 */
@Command(name = "/sysinfo", description = "显示系统与项目环境信息")
public class SysInfoCommand implements Runnable, CliCommand {

    @Parameters(index = "0", defaultValue = "", description = "可选参数：refresh（强制重新采集）")
    private String arg = "";

    @Override
    public void run() {}

    @Override
    public boolean execute(CommandContext ctx) {
        SystemInfoManager mgr = SystemInfoManager.getInstance();
        if ("refresh".equalsIgnoreCase(arg.trim())) {
            mgr.refresh();
            System.out.println("系统信息已重新采集。");
        }
        System.out.println(mgr.formatAsText());
        return false;
    }
}

