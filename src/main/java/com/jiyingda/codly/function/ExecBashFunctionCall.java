package com.jiyingda.codly.function;

import com.alibaba.fastjson.*;
import com.jiyingda.codly.core.ConversationContext;
import com.jiyingda.codly.data.Parameters;

/** Bash 进程及日志通过会话任务管理器运行，参数由权限层按原始命令批准。 */
public class ExecBashFunctionCall implements FunctionCallApi {
    public String getName(){return "Bash";}
    public String getDescription(){return "在当前工作区执行 bash 命令。timeoutSeconds 可配置（前台默认 120 秒）；background=true 启动后台构建或服务并立即返回任务 ID；yieldSeconds>0 可先等待若干秒，未结束则转为后台。完整输出保存在日志文件，返回尾部摘要；用 task_status/task_output/task_stop 管理任务。后台任务随 Codly 正常退出而停止，不要用 nohup、setsid 或自行脱离的 daemon。";}
    public Parameters getParameters(){return Parameters.create().addProperty("command","string","bash 命令")
            .addProperty("timeoutSeconds","integer","超时秒数，1..604800；前台默认 120 秒，后台默认 86400 秒")
            .addProperty("background","boolean","是否立即转为后台任务")
            .addProperty("yieldSeconds","integer","前台等待秒数，0 表示等待到完成或超时") .addRequired("command");}
    public boolean requiresConfirmation(){return true;}
    public String confirmationSummary(String arguments){try{return "执行 bash 命令: "+JSON.parseObject(arguments).getString("command");}catch(RuntimeException e){return "执行 bash 命令: "+arguments;}}
    public String execute(String arguments,ConversationContext context){
        try{
            JSONObject args=JSON.parseObject(arguments);String command=args.getString("command");
            if(command==null||command.isBlank())return "未提供 command 参数";
            if(context.cancellation().isCancelled())return "命令已取消";
            boolean background=args.getBooleanValue("background");JSONObject config=context.executionOptions();
            int timeout=args.containsKey("timeoutSeconds")?args.getIntValue("timeoutSeconds"):config.containsKey(background?"backgroundTimeoutSeconds":"timeoutSeconds")?config.getIntValue(background?"backgroundTimeoutSeconds":"timeoutSeconds"):background?86400:120;
            int yield=args.getIntValue("yieldSeconds");
            if(timeout<1||timeout>604800||yield<0||yield>604800)return "执行失败：timeoutSeconds 必须为 1..604800，yieldSeconds 为 0..604800";
            int limit=config.containsKey("outputLimit")?config.getIntValue("outputLimit"):30000;
            var tasks=context.tasks();String id=tasks.start(command,context.getStartupPath(),timeout);
            var status=background?tasks.status(id):tasks.await(id,yield,context.cancellation(),context.processOutputSink());
            String state=(String)status.get("state");
            if("CANCELLED".equals(state))return "命令已取消";
            String prefix=switch(state){case "COMPLETED"->"执行成功";case "RUNNING"->"后台任务运行中";case "CANCELLED"->"命令已取消";case "TIMED_OUT"->"命令执行超时（超过 "+timeout+" 秒）";default->"执行失败，退出码 "+status.get("exitCode");};
            return prefix+"\n任务 ID："+id+"\n完整输出："+status.get("logFile")+"\n"+tasks.tail(id,limit);
        }catch(Exception error){return "执行 execBash 失败："+error.getMessage();}
    }
}
