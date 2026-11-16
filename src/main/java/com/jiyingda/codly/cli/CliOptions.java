package com.jiyingda.codly.cli;

import picocli.CommandLine;
import java.nio.file.Path;
import java.util.*;
import com.jiyingda.codly.core.ApprovalPolicy;
import com.jiyingda.codly.llm.ThinkingDepth;

@CommandLine.Command(name="codly",version="codly 1.0.0",mixinStandardHelpOptions=true,description="交互式编程助手；resume [ID] 恢复历史；-p 以非交互方式执行")
public final class CliOptions {
    @CommandLine.Option(names="--continue",description="继续当前工作区最近的对话")public boolean continueLast;
    @CommandLine.Option(names={"-p","--prompt"},description="非交互提示词，可同时读取 stdin")public String prompt;
    @CommandLine.Option(names="--cwd",description="工作区目录")public Path cwd;
    @CommandLine.Option(names="--model",description="模型名称")public String model;
    @CommandLine.Option(names="--thinking",description="思考深度：off / low / high / max")public String thinking;
    @CommandLine.Option(names={"--permissions","--approval"},description="批准策略：ask / assisted / full")public String permissions;
    @CommandLine.Option(names="--output-format",description="非交互输出：text / json / jsonl（stream-json 为 jsonl 别名）")public String output="text";
    @CommandLine.Parameters(arity="0..*",description="初始提示词，或 resume [ID]")public List<String> positional=new ArrayList<>();
    public String command,conversation;
    public void validate(){
        if(!positional.isEmpty()) {
            if(positional.get(0).equals("resume")){command="resume";if(positional.size()>2)throw new IllegalArgumentException("resume 仅接受一个对话 ID");conversation=positional.size()==2?positional.get(1):null;}
            else if(prompt!=null)throw new IllegalArgumentException("-p 与位置提示词不能重复指定");
        }
        if(command!=null&&!command.equals("resume"))throw new IllegalArgumentException("未知命令："+command);
        if(continueLast&&command!=null)throw new IllegalArgumentException("--continue 不能与 resume 同时使用");
        if(!List.of("text","json","jsonl","stream-json").contains(output))throw new IllegalArgumentException("输出格式必须为 text、json 或 jsonl");
        if(thinking!=null)depth(thinking);if(permissions!=null)policy(permissions);
    }
    public String initialPrompt(){return prompt!=null?prompt:command==null&&!positional.isEmpty()?String.join(" ",positional):null;}
    public boolean batch(boolean tty){return prompt!=null||!tty||!output.equals("text");}
    public void apply(com.jiyingda.codly.core.ChatSession session){if(model!=null)session.setModel(model);if(thinking!=null){if(!session.supportsThinkingDepth())throw new IllegalArgumentException("当前模型不支持思考深度");session.setThinkingDepth(depth(thinking));}if(permissions!=null)session.setApprovalPolicy(policy(permissions));}
    public static ThinkingDepth depth(String value){return switch(value.toLowerCase(Locale.ROOT)){case "off","none"->ThinkingDepth.OFF;case "low"->ThinkingDepth.LOW;case "high"->ThinkingDepth.HIGH;case "max"->ThinkingDepth.MAX;default->throw new IllegalArgumentException("思考深度必须为 off / low / high / max");};}
    public static ApprovalPolicy policy(String value){return switch(value.toLowerCase(Locale.ROOT)){case "ask","1","请求批准"->ApprovalPolicy.ASK;case "assisted","2","帮我批准"->ApprovalPolicy.ASSISTED;case "full","3","完全授权"->ApprovalPolicy.FULL;default->throw new IllegalArgumentException("批准策略必须为 ask / assisted / full");};}
}
