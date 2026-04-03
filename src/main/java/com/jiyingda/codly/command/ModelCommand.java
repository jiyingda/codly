package com.jiyingda.codly.command;

import com.jiyingda.codly.llm.LlmClient;
import com.jiyingda.codly.llm.ModelSelector;
import picocli.CommandLine.Command;

@Command(name = "/model", description = "列出并切换当前使用的模型")
public class ModelCommand implements Runnable, CliCommand {

    @Override
    public void run() {}

    @Override
    public boolean execute(CommandContext ctx) {
        String chosen = ModelSelector.select(LlmClient.AVAILABLE_MODELS, ctx.getLlmClient().getModel());
        if (chosen != null) {
            ctx.getLlmClient().setModel(chosen);
        }
        return false;
    }
}
