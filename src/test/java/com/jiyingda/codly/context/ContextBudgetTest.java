package com.jiyingda.codly.context;

import com.alibaba.fastjson.*;
import com.jiyingda.codly.core.*;
import com.jiyingda.codly.data.Message;
import com.jiyingda.codly.llm.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.Assert.*;

public class ContextBudgetTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 @Test public void automaticCompactionPreservesRecentConversationAndFirstGoal()throws Exception {
  Provider provider=new Provider();ConversationContext context=context(provider);populate(context,12000);
  try(ChatSession session=new ChatSession(context,new Memory(),()->"BASE",title->{})) {
   session.send("继续",null);
   assertEquals(1,provider.summaries.size());assertTrue(provider.summaries.get(0).get(1).getContent().contains("FIRST_GOAL"));
   assertEquals(5,context.getMemory().size());assertTrue(context.getMemory().get(0).getContent().contains("目标与完成情况"));
   assertEquals("RECENT_QUESTION",context.getMemory().get(1).getContent());assertEquals("RECENT_ANSWER",context.getMemory().get(2).getContent());
   assertTrue(context.budget().used()<context.budget().limit("fake"));
  }
 }
 @Test public void failedCompactionCanProceedWhenUnderHardLimitButNeverDestroysHistory()throws Exception {
  Provider provider=new Provider();provider.summary=null;ConversationContext context=context(provider);populate(context,9300);
  String original=context.getMemory().get(0).getContent();
  try(ChatSession session=new ChatSession(context,new Memory(),()->"BASE",title->{})){
   session.send("继续",null);assertEquals(1,provider.summaries.size());assertEquals(original,context.getMemory().get(0).getContent());assertEquals(1,provider.chats);
  }
 }
 @Test public void oversizeInputReturnsLimitWithoutCallingModelOrErasingHistory()throws Exception {
  Provider provider=new Provider();ConversationContext context=context(provider);context.getMemory().add(Message.fromUser("keep me"));
  try(ChatSession session=new ChatSession(context,new Memory(),()->"BASE",title->{})) {
   assertThrows(ContextLimitException.class,()->session.send("x".repeat(30000),null));assertEquals("keep me",context.getMemory().get(0).getContent());assertEquals(0,provider.chats);
  }
 }
 @Test public void filesAndScopedRulesReachModelWhileTitleUsesOriginalUserText()throws Exception {
  Provider provider=new Provider();ConversationContext context=context(provider);Path root=context.getStartupPath();Files.writeString(root.resolve("AGENTS.md"),"USE_PROJECT_RULES");Files.writeString(root.resolve("code.java"),"first\nsecond\nthird\n");
  try(ChatSession session=new ChatSession(context,new Memory(),()->"BASE",title->{})){
   List<TraceEvent> trace=new ArrayList<>();String input="解释 @code.java#2";session.send(input,null,new CancellationToken(),null,trace::add);
   assertEquals(input,provider.titleInput);assertTrue(context.getMemory().get(0).getContent().contains("second\n"));assertFalse(context.getMemory().get(0).getContent().contains("first\n"));
   assertTrue(context.getSystemPrompt().getContent().contains("USE_PROJECT_RULES"));assertTrue(trace.stream().anyMatch(event->event.title().equals("附加文件")));assertTrue(session.contextInfo().contains("AGENTS.md"));
  }
 }
 @Test public void displayedBudgetReflectsCurrentHistoryAndNewConversation()throws Exception {
  Provider provider=new Provider();ConversationContext context=context(provider);
  try(ChatSession session=new ChatSession(context,new Memory(),()->"BASE",title->{})){
   context.getMemory().add(Message.fromUser("x".repeat(3000)));session.contextInfo();int used=context.budget().used();assertTrue(used>=1000);
   session.newConversation();session.contextInfo();assertTrue(context.budget().used()<used/2);
  }
 }
 @Test public void modelSpecificCapacityAndValidationAreExplicit() {
  ContextBudget budget=new ContextBudget();budget.configure(JSON.parseObject("{\"models\":{\"large\":100000},\"windowTokens\":8000,\"reserveTokens\":1000}"));
  assertEquals(100000,budget.capacity("large"));assertEquals(8000,budget.capacity("other"));assertTrue(budget.describe("large").contains("配置容量"));
  for(String json:List.of("{\"context\":{\"windowTokens\":100}}","{\"execution\":{\"timeoutSeconds\":0}}","{\"context\":{\"autoCompact\":\"yes\"}}"))assertThrows(IllegalArgumentException.class,()->com.jiyingda.codly.config.SettingsStore.validateRuntime(JSON.parseObject(json)));
 }
 ConversationContext context(Provider provider)throws Exception {Path root=temp.newFolder().toPath();ConversationContext context=new ConversationContext(new ArrayList<>(),provider,root);context.configureRuntime(temp.getRoot().toPath().resolve("config"),JSON.parseObject("{\"windowTokens\":4096,\"reserveTokens\":512,\"keepRecentMessages\":2}"),new JSONObject());return context;}
 void populate(ConversationContext context,int size){context.getMemory().add(Message.fromUser("FIRST_GOAL "+"x".repeat(size)));context.getMemory().add(Message.formAssistant("completed"));context.getMemory().add(Message.fromUser("another task"));context.getMemory().add(Message.formAssistant("done"));context.getMemory().add(Message.fromUser("RECENT_QUESTION"));context.getMemory().add(Message.formAssistant("RECENT_ANSWER"));}
 static class Provider implements LlmProvider {
  List<List<Message>> summaries=new ArrayList<>();String summary="目标与完成情况";String titleInput;int chats;
  public String getModel(){return "fake";}public void setModel(String model){}public List<String> getAvailableModels(){return List.of("fake");}
  public String chat(ConversationContext context,Consumer<String> stream){chats++;if(stream!=null)stream.accept("reply");return "reply";}
  public String chat(ConversationContext context,List<Message> messages,Consumer<String> stream){return chat(context,stream);}
  public String complete(List<Message> messages){summaries.add(messages);return summary;}
  public void generateTitleAsync(String input,Consumer<String> callback){titleInput=input;}
 }
 static class Memory implements SessionMemory {
  public void initializeWithSession(String title){}public boolean appendMessage(Message message){return false;}public void appendLtmRound(String user,String reply){}
  public String toLongTermPromptSection(){return "";}public void flushAndExtractLtmAsync(ConversationContext ctx){}public void flushAndExtractLtmSync(ConversationContext ctx){}
  public void flushNow(){}public void clearSession(){}public void shutdown(){}
 }
}
