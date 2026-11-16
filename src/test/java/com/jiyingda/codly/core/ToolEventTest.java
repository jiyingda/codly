package com.jiyingda.codly.core;

import org.junit.Test;
import java.util.ArrayList;
import static org.junit.Assert.*;

public class ToolEventTest {
    @Test public void timeoutDenialAndRemoteErrorsAreFailuresInTheTrace() {
        ConversationContext context=new ConversationContext(new ArrayList<>(),null,java.nio.file.Path.of("."));
        var trace=new ArrayList<TraceEvent>();context.setTraceEvents(trace::add);
        for(String result: new String[]{"用户拒绝执行此操作","执行失败，退出码 1","执行 execBash 失败：测试","命令执行超时（超过 30 秒）","MCP 工具执行失败：HTTP 500","{\"isError\":true,\"content\":[]}"}) {
            ToolEvent event=new ToolEvent("test","Bash","{}",result,ToolEvent.State.RETURNED);
            assertTrue(result,event.failed());context.emitTool(event);
            assertEquals(TraceEvent.Phase.FAILED,trace.get(trace.size()-1).phase());
        }
        assertFalse(new ToolEvent("test","Bash","{}","执行成功：result",ToolEvent.State.RETURNED).failed());
        assertFalse(new ToolEvent("test","mcp","{}","{\"isError\":false}",ToolEvent.State.RETURNED).failed());
        assertFalse(new ToolEvent("test","mcp","{}","{incomplete",ToolEvent.State.RETURNED).failed());
    }
}
