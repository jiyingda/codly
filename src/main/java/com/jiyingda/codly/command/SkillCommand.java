package com.jiyingda.codly.command;

import com.jiyingda.codly.skill.Skill;
import com.jiyingda.codly.skill.SkillRegistry;
import com.jiyingda.codly.skill.SkillSelector;
import picocli.CommandLine.Command;

import java.util.List;

@Command(name = "/skill", description = "交互式选择并激活 skill")
public class SkillCommand implements Runnable, CliCommand {

    @Override
    public void run() {}

    @Override
    public boolean execute(CommandContext ctx) {
        List<Skill> skills = SkillRegistry.getInstance().all();
        if (skills.isEmpty()) {
            System.out.println("当前没有可用的 skill。请在 ~/.codly/skills/<name>/SKILL.md 下创建。");
            return false;
        }
        Skill chosen = SkillSelector.select(skills, ctx.getTerminal());
        if (chosen != null) {
            ctx.activateSkill(chosen);
        }
        return false;
    }
}
