package com.jiyingda.codly.cli;

import com.alibaba.fastjson.*;
import com.jiyingda.codly.config.*;
import com.jiyingda.codly.core.*;
import com.jiyingda.codly.data.Message;
import com.jiyingda.codly.llm.*;
import com.jiyingda.codly.memory.ConversationStore;
import com.jiyingda.codly.util.TokenStats;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 无终端 UI 的单次执行；stdout 仅输出所选结果格式，诊断只写 stderr。 */
public final class BatchRunner {
    public static final int SUCCESS=0,FAILURE=1,INPUT_ERROR=2,DENIED=3,LIMIT=4,CANCELLED=130;
    private BatchRunner(){}
    public static int run(CliOptions options,InputStream input,PrintStream output,PrintStream diagnostics,boolean tty) {
        ChatSession session=null;CliHistory history=null;CancellationToken token=new CancellationToken();CountDownLatch finished=new CountDownLatch(1);
        Thread shutdown=new Thread(()->{token.cancel();try{finished.await(4,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}},"Codly-Batch-Cancel");
        Runtime.getRuntime().addShutdownHook(shutdown);
        StringBuilder answer=new StringBuilder();TokenStats stats=new TokenStats();stats.markStart();AtomicBoolean reasoning=new AtomicBoolean(),denied=new AtomicBoolean();
        int code=SUCCESS;String error=null;Map<String,Object> result=new LinkedHashMap<>();String taskInput=null;boolean attempted=false,recorded=false;
        try {
            String prompt=options.initialPrompt();
            if(!tty){byte[] bytes=input.readNBytes(16*1024*1024+1);if(bytes.length>16*1024*1024)throw new IllegalArgumentException("stdin 超过 16MB");String piped=new String(bytes,StandardCharsets.UTF_8);if(!piped.isBlank())prompt=prompt==null?piped:prompt+"\n\n## 标准输入\n"+piped;}
            if(prompt==null||prompt.isBlank())throw new IllegalArgumentException("请使用 -p、位置提示词或 stdin 提供任务");
            Config config=Config.getInstance();if(config.isNotConfigLoaded())throw new ConfigException(config.getLoadError());
            Path workspace;try{workspace=(options.cwd==null?Path.of(""):options.cwd).toRealPath();}catch(IOException exception){throw new IllegalArgumentException("工作区不可访问："+exception.getMessage());}if(!Files.isDirectory(workspace))throw new IllegalArgumentException("工作区必须是目录");
            ConversationContext context=new ConversationContext(new ArrayList<>(),LlmClient.create(),workspace);
            final CliHistory[] archive={null};
            session=ChatSession.open(context,title->{if(archive[0]!=null)archive[0].rename(title);});
            Path home=Path.of(System.getProperty("user.home"),".codly");var store=new ConversationStore(home.resolve("conversations"),home.resolve("memory/session"));
            history=new CliHistory(store,session);archive[0]=history;
            if(options.continueLast||options.command!=null){
                String selected=options.conversation;
                var records=store.list();
                if(options.continueLast)selected=records.stream().filter(r->{try{return r.workspace()!=null&&!r.workspace().isBlank()&&Path.of(r.workspace()).toRealPath().equals(workspace);}catch(IOException e){return false;}}).findFirst().orElseThrow(()->new IllegalArgumentException("当前工作区没有历史对话")).id();
                if(selected==null)throw new IllegalArgumentException("非交互恢复必须指定对话 ID，或使用 --continue");
                String id=selected;var matches=records.stream().filter(r->r.id().startsWith(id)).toList();if(matches.size()!=1)throw new IllegalArgumentException("对话 ID 不存在或前缀不唯一");
                var record=store.load(matches.get(0).id());if(record.workspace()==null||record.workspace().isBlank())throw new IllegalArgumentException("旧记录需要先在交互模式中绑定工作区");
                Path saved=Path.of(record.workspace()).toRealPath();history.restore(record,saved);
            }
            options.apply(session);
            context.setApprovalHandler(request->{denied.set(true);diagnostics.println("未批准操作："+request.summary());return ApprovalDecision.DENY;});
            result.put("sessionId",history.id());result.put("workspace",session.getWorkspace().toString());result.put("model",session.getModel());
            event(options,output,"init",result);
            taskInput=prompt;attempted=true;
            session.send(prompt,chunk->{
                if(chunk.startsWith(AbstractLlmClient.USAGE_PREFIX)){String[] counts=chunk.substring(AbstractLlmClient.USAGE_PREFIX.length()).split(":");if(counts.length==3)try{stats.setUsage(Integer.parseInt(counts[0]),Integer.parseInt(counts[1]),Integer.parseInt(counts[2]));}catch(NumberFormatException ignored){}return;}
                if(chunk.equals(AbstractLlmClient.REASONING_START)){reasoning.set(true);event(options,output,"thinking",Map.of("active",true));return;}
                if(chunk.equals(AbstractLlmClient.REASONING_END)){reasoning.set(false);return;}
                stats.markFirstToken();stats.recordChunk();if(!reasoning.get()){answer.append(chunk);event(options,output,"message",Map.of("delta",chunk));}
            },token,tool->{
                if(tool.state()==ToolEvent.State.REQUESTED)answer.setLength(0);
                Map<String,Object> fields=new LinkedHashMap<>();fields.put("id",tool.id());fields.put("name",tool.name());fields.put("state",tool.state().name());
                if(tool.state()==ToolEvent.State.REQUESTED)fields.put("arguments",tool.arguments());else fields.put("result",tool.result());
                event(options,output,tool.state()==ToolEvent.State.REQUESTED?"tool_use":tool.state()==ToolEvent.State.OUTPUT?"tool_output":"tool_result",fields);
            },history.traceSink());
            if(token.isCancelled()){code=CANCELLED;error="请求已取消";}else if(denied.get()){code=DENIED;error="操作未获批准；非交互模式不能显示批准提示";}
            history.addTurn(prompt,answer.toString(),code==SUCCESS?"已完成":code==CANCELLED?"已停止":"未批准执行");
            recorded=true;
        } catch(ContextLimitException|TurnLimitException exception){code=LIMIT;error=exception.getMessage();}
        catch(IllegalArgumentException exception){code=INPUT_ERROR;error=exception.getMessage();}
        catch(Exception exception){code=token.isCancelled()?CANCELLED:FAILURE;error=token.isCancelled()?"请求已取消":exception.getMessage();}
        finally {
            stats.markEnd();
            if(history!=null&&attempted&&!recorded)history.addTurn(taskInput,answer.toString(),token.isCancelled()?"已停止":"请求失败");
            try{if(history!=null)history.save();}catch(IOException exception){code=FAILURE;error="保存失败："+exception.getMessage();}
            try{if(session!=null){int running=session.runningTasks();result.put("backgroundTasksStopped",running);if(running>0)diagnostics.println("正常退出：停止 "+running+" 个后台任务，完整日志已保留。");session.close(false);}}catch(RuntimeException exception){code=FAILURE;error="关闭会话失败："+exception.getMessage();}
            try{Runtime.getRuntime().removeShutdownHook(shutdown);}catch(IllegalStateException ignored){}
        }
        if(token.isCancelled()){code=CANCELLED;error="请求已取消";}
        result.put("response",answer.toString());result.put("exitCode",code);result.put("ok",code==SUCCESS);
        Map<String,Object> usage=new LinkedHashMap<>();usage.put("available",stats.hasUsage());if(stats.hasUsage()){usage.put("promptTokens",stats.getPromptTokens());usage.put("completionTokens",stats.getCompletionTokens());usage.put("totalTokens",stats.getTotalTokens());}usage.put("durationMs",stats.getTotalTimeMs());result.put("stats",usage);
        if(error!=null){result.put("error",error);diagnostics.println("执行失败 ["+code+"]："+error);event(options,output,"error",Map.of("message",Objects.toString(error,"未知错误"),"exitCode",code));}
        if(options.output.equals("text")){if(!answer.isEmpty())output.println(answer);}
        else if(options.output.equals("json"))output.println(JSON.toJSONString(result));
        else event(options,output,"result",result);
        output.flush();finished.countDown();return code;
    }
    private static synchronized void event(CliOptions options,PrintStream output,String type,Map<String,?> fields){if(!List.of("jsonl","stream-json").contains(options.output))return;Map<String,Object> event=new LinkedHashMap<>();event.put("type",type);event.putAll(fields);output.println(JSON.toJSONString(event));output.flush();}
}
