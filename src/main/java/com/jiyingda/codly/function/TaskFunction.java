package com.jiyingda.codly.function;

import com.alibaba.fastjson.*;
import com.jiyingda.codly.core.ConversationContext;
import com.jiyingda.codly.data.Parameters;
import java.util.*;

public final class TaskFunction implements FunctionCallApi {
    private final String name;
    public TaskFunction(String name){this.name=name;}
    public String getName(){return name;}
    public String getDescription(){return switch(name){case "task_output"->"分页读取当前工作区任务的完整日志，offset/nextOffset 按字节计数";case "task_stop"->"停止当前工作区由 Codly 管理的后台任务";default->"查看当前工作区任务状态；不填 id 时列出任务";};}
    public Parameters getParameters(){Parameters p=Parameters.create().addProperty("id","string","任务 ID").addProperty("offset","integer","日志起始字节偏移，默认 0").addProperty("limit","integer","最多读取字节数，默认 16384，上限 65536");if(!name.equals("task_status"))p.addRequired("id");return p;}
    public boolean requiresConfirmation(){return name.equals("task_stop");}
    public String execute(String arguments,ConversationContext context){
        try{
            JSONObject args=JSON.parseObject(arguments);var tasks=context.tasks();String id=args.getString("id");String workspace=context.getStartupPath().toRealPath().toString();
            if(name.equals("task_status")&&(id==null||id.isBlank()))return JSON.toJSONString(tasks.list().stream().filter(job->workspace.equals(job.get("workspace"))).toList());
            var status=tasks.status(id);if(!workspace.equals(status.get("workspace")))return "执行失败：任务不属于当前工作区";
            return JSON.toJSONString(switch(name){case "task_stop"->tasks.stop(id);case "task_output"->tasks.output(id,args.getLongValue("offset"),args.containsKey("limit")?args.getIntValue("limit"):16384);default->status;});
        }catch(Exception error){return "执行失败："+error.getMessage();}
    }
}
