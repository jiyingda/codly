package com.jiyingda.codly.cli;

import com.jiyingda.codly.command.*;
import com.jiyingda.codly.core.*;
import com.jiyingda.codly.data.Message;
import com.jiyingda.codly.llm.*;
import com.jiyingda.codly.memory.ConversationStore;
import org.jline.reader.*;
import org.jline.terminal.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.Assert.*;

public class CliControllerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    ConversationStore store() { return new ConversationStore(temp.getRoot().toPath().resolve("conversations"),temp.getRoot().toPath().resolve("legacy")); }
    ConversationRecord record(String id,Path workspace,Instant date) {
        return new ConversationRecord(id,"历史任务"+id,workspace==null?"":workspace.toString(),"fake","saved",ThinkingDepth.MAX,date,date,
                List.of(Message.fromUser("旧问题"),Message.formAssistant("旧回答")),List.of(new ConversationRecord.Turn("旧问题","旧回答","已完成")),List.of(),null);
    }
    class Fixture implements AutoCloseable {
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        final Terminal terminal;
        final Provider provider;
        final CommandContext context;
        final ChatSession session;
        final CliHistory history;
        final CliDisplay display;
        final CliController controller;
        final LineReader reader;
        Fixture(String input,Provider provider)throws Exception {
            this.provider=provider;
            terminal=TerminalBuilder.builder().system(false).dumb(false).type("xterm").encoding(StandardCharsets.UTF_8).size(new Size(100,30))
                    .streams(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),output).build();
            context=new CommandContext(new ArrayList<>(),provider,temp.getRoot().toPath());
            session=new ChatSession(context,new Memory(),()->"test prompt",title->{});
            history=new CliHistory(store(),session);display=new CliDisplay(terminal);
            reader=LineReaderBuilder.builder().terminal(terminal).build();
            controller=new CliController(context,session,history,terminal,reader,display);
        }
        String text(){return output.toString(StandardCharsets.UTF_8);}
        public void close()throws Exception {session.close();display.close();terminal.close();}
    }

    @Test public void resumeRestoresWorkspaceModelDepthAndContinuesTheSameArchive()throws Exception {
        Path workspace=temp.newFolder("saved project").toPath().toRealPath();store().save(record("saved-id",workspace,Instant.now()));
        try(var f=new Fixture("",new Provider())) {
            f.controller.resume("saved-id");assertEquals(workspace,f.session.getWorkspace());
            assertEquals("saved",f.session.getModel());assertEquals(ThinkingDepth.MAX,f.session.getThinkingDepth());
            f.controller.turn("继续");
            var saved=store().load("saved-id");assertEquals(2,saved.turns().size());
            assertEquals("旧问题",saved.messages().get(0).getContent());assertEquals(workspace.toString(),saved.workspace());
            assertEquals("最终回答",saved.turns().get(1).assistant());
        }
    }
    @Test public void unknownWorkspaceRequiresAnExplicitBindingAndCancellationKeepsCurrentChat()throws Exception {
        Path bound=temp.newFolder("bound project").toPath().toRealPath();store().save(record("unknown",null,Instant.now()));
        try(var f=new Fixture("\r"+bound+"\r",new Provider())) {
            f.controller.turn("当前任务");String current=f.history.id();String messages=com.alibaba.fastjson.JSON.toJSONString(f.context.getMemory());
            f.controller.resume("unknown");assertEquals(current,f.history.id());assertEquals(messages,com.alibaba.fastjson.JSON.toJSONString(f.context.getMemory()));
            assertEquals("",store().load("unknown").workspace());
            f.controller.resume("unknown");assertEquals(bound,f.session.getWorkspace());assertEquals(bound.toString(),store().load("unknown").workspace());
        }
    }
    @Test public void continueSelectsTheLatestHistoryInCurrentWorkspace()throws Exception {
        Path here=temp.getRoot().toPath().toRealPath(),other=temp.newFolder("other").toPath().toRealPath();
        store().save(record("here-old",here,Instant.now().minusSeconds(20)));store().save(record("here-new",here,Instant.now().minusSeconds(10)));store().save(record("other-newest",other,Instant.now()));
        try(var f=new Fixture("/quit\r",new Provider())) {
            CliOptions options=new CliOptions();options.continueLast=true;f.controller.run(options);
            assertEquals("here-new",f.history.id());assertEquals(here,f.session.getWorkspace());
        }
    }
    @Test public void terminalApprovalsCanRememberAndNewConversationClearsThem()throws Exception {
        try(var f=new Fixture("a\rn\r",new Provider())) {
            String args="{\"command\":\"unknown\"}";
            assertTrue(f.context.confirm("Bash",args,"unknown"));
            assertTrue(f.context.confirm("Bash",args,"unknown"));
            f.session.newConversation();assertFalse(f.context.confirm("Bash",args,"unknown"));
            assertTrue(f.text().contains("可记忆范围"));
            new CommandDispatcher().dispatch("/permissions full",f.context);
            assertEquals(ApprovalPolicy.FULL,f.context.getApprovalPolicy());assertTrue(f.context.confirm("Bash",args,"unknown"));
            new CommandDispatcher().dispatch("/permissions assisted",f.context);assertEquals(ApprovalPolicy.ASSISTED,f.context.getApprovalPolicy());
        }
    }
    @Test public void conciseOutputHidesThinkingAndFullToolDetailsButTraceRetainsThem()throws Exception {
        try(var f=new Fixture("",new Provider())) {
            f.controller.turn("任务");assertFalse(f.text().contains("完整隐藏思考"));assertFalse(f.text().contains("中间说明"));
            assertTrue(f.text().contains("最终回答"));assertTrue(f.text().contains("Bash"));
            assertFalse(f.text().contains("工具尾部详情"));
            var saved=store().load(f.history.id());assertEquals("最终回答",saved.turns().get(0).assistant());
            assertTrue(saved.trace().stream().anyMatch(e->e.detail().contains("完整隐藏思考")));
            new CommandDispatcher().dispatch("/trace all",f.context);
            assertTrue(f.text().contains("完整隐藏思考"));assertTrue(f.text().contains("工具尾部详情"));
        }
    }
    @Test public void signalCancelsActiveTurnAndAllowsAnotherTurn()throws Exception {
        CountDownLatch started=new CountDownLatch(1),released=new CountDownLatch(1);
        Provider provider=new Provider(){int requests;
            @Override public String chat(ConversationContext context,Consumer<String> stream) {
                if(requests++>0)return super.chat(context,stream);
                Runnable cancel=released::countDown;context.cancellation().register(cancel);started.countDown();
                try{if(!released.await(3,TimeUnit.SECONDS))throw new IllegalStateException("cancel not delivered");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                finally{context.cancellation().unregister(cancel);}return "";
            }
        };
        try(var f=new Fixture("",provider)) {
            ExecutorService pool=Executors.newSingleThreadExecutor();
            try {
                Future<?> turn=pool.submit(()->{try{f.controller.turn("slow");}catch(IOException e){throw new RuntimeException(e);}});
                assertTrue(started.await(2,TimeUnit.SECONDS));f.terminal.raise(Terminal.Signal.INT);turn.get(3,TimeUnit.SECONDS);
                assertEquals("已停止",store().load(f.history.id()).turns().get(0).state());
                assertFalse(f.context.cancellation().isCancelled());
                f.context.setApprovalHandler(request->ApprovalDecision.ALLOW_ONCE);
                assertTrue(f.context.confirm("Bash","{\"command\":\"unknown\"}","取消后的独立操作"));
                f.controller.turn("next");assertEquals(2,store().load(f.history.id()).turns().size());assertTrue(f.text().contains("最终回答"));
            } finally {released.countDown();pool.shutdownNow();}
        }
    }
    @Test public void approvalInterruptCancelsTurnAndIdleInterruptClearsDraftInsteadOfExiting()throws Exception {
        Provider provider=new Provider(){@Override public String chat(ConversationContext context,Consumer<String> stream){context.confirm("Bash","{\"command\":\"unknown\"}","approval");return "";}};
        try(var f=new Fixture("\003",provider)) {f.controller.turn("approve");assertEquals("已停止",store().load(f.history.id()).turns().get(0).state());}
        // ExternalTerminal 会在预加载字节上提前处理 Ctrl+C；用等价 widget 精确验证部分草稿异常。
        // 真实 Ctrl+C 按键另由进程级 PTY 回归覆盖。
        try(var f=new Fixture("draft~after\r/quit\r",new Provider())) {
            f.reader.getWidgets().put("test-interrupt",()->{throw new UserInterruptException(f.reader.getBuffer().toString());});
            f.reader.getKeyMaps().get(LineReader.MAIN).bind(new Reference("test-interrupt"),"~");
            f.controller.run(new CliOptions());assertTrue(f.text().contains("草稿已清空"));
            assertEquals("after",store().load(f.history.id()).turns().get(0).user());
        }
    }
    @Test public void startupArgumentsAndSlashCommandsExposeP0Actions() {
        CliOptions options=new CliOptions();new picocli.CommandLine(options).parseArgs("resume","chat-1");options.validate();assertEquals("chat-1",options.conversation);
        options=new CliOptions();new picocli.CommandLine(options).parseArgs("--continue");options.validate();assertTrue(options.continueLast);
        options.command="resume";CliOptions invalid=options;assertThrows(IllegalArgumentException.class,invalid::validate);
        assertTrue(CommandDispatcher.helpText().contains("/resume"));assertTrue(CommandDispatcher.helpText().contains("/permissions"));assertTrue(CommandDispatcher.helpText().contains("/trace"));
    }
    static class Provider implements LlmProvider {
        String model="fake";ThinkingDepth depth=ThinkingDepth.LOW;
        public String getModel(){return model;}public void setModel(String value){model=value;}public String providerId(){return "fake";}
        public List<String> getAvailableModels(){return List.of("fake","saved");}public boolean supportsThinkingDepth(){return true;}
        public ThinkingDepth getThinkingDepth(){return depth;}public void setThinkingDepth(ThinkingDepth value){depth=value;}
        public String complete(List<Message> messages){return "summary";}public void generateTitleAsync(String text,Consumer<String> callback){}
        public String chat(ConversationContext context,List<Message> messages,Consumer<String> stream){return chat(context,stream);}
        public String chat(ConversationContext context,Consumer<String> stream){
            context.emitTrace(TraceEvent.of("think",TraceEvent.Kind.THINKING,TraceEvent.Phase.START,"思考","完整隐藏思考"));
            if(stream!=null){stream.accept(AbstractLlmClient.REASONING_START);stream.accept("完整隐藏思考");stream.accept(AbstractLlmClient.REASONING_END);stream.accept("中间说明");}
            context.emitTool(new ToolEvent("tool","Bash","{\"command\":\"pwd\"}",null,ToolEvent.State.REQUESTED));
            context.emitTool(new ToolEvent("tool","Bash","{}","result "+"x".repeat(150)+"工具尾部详情",ToolEvent.State.RETURNED));
            if(stream!=null){stream.accept("最终回答");stream.accept(AbstractLlmClient.USAGE_PREFIX+"10:4:14");}return "中间说明最终回答";
        }
    }
    static class Memory implements SessionMemory {
        public void initializeWithSession(String text){}public boolean appendMessage(Message message){return false;}
        public void appendLtmRound(String user,String assistant){}public String toLongTermPromptSection(){return "";}
        public void flushAndExtractLtmAsync(ConversationContext context){}public void flushAndExtractLtmSync(ConversationContext context){}
        public void flushNow(){}public void clearSession(){}public void shutdown(){}
    }
}
