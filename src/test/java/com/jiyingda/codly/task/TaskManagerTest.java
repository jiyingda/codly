package com.jiyingda.codly.task;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import com.jiyingda.codly.core.CancellationToken;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class TaskManagerTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 @Test public void fullOutputIsSavedTailIsExplicitAndUtf8PagesAdvanceWithoutCorruption()throws Exception {
  Path root=temp.getRoot().toPath();try(var tasks=new TaskManager(root.resolve("logs"),false)) {
   String id=tasks.start("printf 'BEGIN\\n'; i=0; while [ $i -lt 10000 ]; do printf '\\344\\270\\255\\346\\226\\207\\350\\241\\214\\n'; i=$((i+1)); done; printf 'END'",root,10);
   List<String> updates=new ArrayList<>();var status=tasks.await(id,0,new CancellationToken(),updates::add);assertEquals("COMPLETED",status.get("state"));assertTrue(Files.size(Path.of((String)status.get("logFile")))>60000);
   String tail=tasks.tail(id,100);assertTrue(tail.contains("已截断"));assertTrue(tail.endsWith("END"));assertFalse(tail.contains("BEGIN"));
   long offset=0;StringBuilder text=new StringBuilder();while(true){var page=tasks.output(id,offset,4096);text.append(page.get("output"));long next=((Number)page.get("nextOffset")).longValue();assertTrue(next>offset);offset=next;if(!(boolean)page.get("hasMore"))break;}
   assertTrue(text.toString().contains("中文行"));assertFalse(text.toString().contains("�"));assertEquals(Files.readString(Path.of((String)status.get("logFile"))),text.toString());
   assertThrows(IllegalArgumentException.class,()->tasks.output(id,-1,1024));assertThrows(IllegalArgumentException.class,()->tasks.status("../escape"));
  }
 }
 @Test public void yieldReturnsRunningTaskAndStopAndTimeoutAreExplicit()throws Exception {
  Path root=temp.getRoot().toPath();try(var tasks=new TaskManager(root.resolve("logs"),false)){
   String id=tasks.start("printf ready; sleep 20",root,30);assertEquals("RUNNING",tasks.await(id,1,new CancellationToken(),null).get("state"));assertEquals("STOPPED",tasks.stop(id).get("state"));
   String timeout=tasks.start("sleep 20",root,1);assertEquals("TIMED_OUT",tasks.await(timeout,0,new CancellationToken(),null).get("state"));
  }
 }
 @Test public void cancellationAndNormalCloseStopChildrenAndLogsSurviveRestart()throws Exception {
  Path root=temp.getRoot().toPath(),logs=root.resolve("logs");String id;CancellationToken token=new CancellationToken();
  try(var tasks=new TaskManager(logs,false)) {
   id=tasks.start("printf start; sleep 20",root,30);ExecutorService executor=Executors.newSingleThreadExecutor();
   try{Future<?> task=executor.submit(()->{try{tasks.await(id,0,token,null);}catch(Exception e){throw new RuntimeException(e);}});token.cancel();task.get(2,TimeUnit.SECONDS);assertEquals("CANCELLED",tasks.status(id).get("state"));}
   finally{executor.shutdownNow();}
   tasks.start("sleep 20",root,30);
  }
  try(var restored=new TaskManager(logs,false)){assertEquals(2,restored.list().size());assertEquals("CANCELLED",restored.status(id).get("state"));assertTrue(restored.list().stream().anyMatch(job->job.get("state").equals("STOPPED")));}
 }
}
