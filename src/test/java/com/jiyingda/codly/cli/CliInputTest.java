package com.jiyingda.codly.cli;

import com.jiyingda.codly.context.ProjectContext;
import com.jiyingda.codly.util.CommandWords;
import org.jline.reader.*;
import org.jline.terminal.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class CliInputTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 @Test public void newlineAndBracketedPasteAreOneSubmissionAndPersistWithProjectHistory()throws Exception {
  Path a=temp.newFolder("a").toPath(),b=temp.newFolder("b").toPath(),storage=temp.newFolder("storage").toPath();
  String keys="one\ntwo\r\033[200~alpha\nbeta\n\033[201~\r";
  try(var fixture=new Fixture(keys,storage,a)) {
   assertEquals("one\ntwo",fixture.input.read());assertEquals("alpha\nbeta\n",fixture.input.read());
   fixture.input.workspace(b);assertEquals(0,fixture.reader.getHistory().size());fixture.input.workspace(a);assertEquals(2,fixture.reader.getHistory().size());
   fixture.input.saveDraft("draft\nbody");
  }
  try(var fixture=new Fixture("\030\022\r",storage,a)){assertEquals("draft\nbody",fixture.input.read());assertEquals("",fixture.input.loadDraft());}
  assertTrue(Files.list(storage.resolve("input-history")).allMatch(p->{try{return !Files.getPosixFilePermissions(p).contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ);}catch(IOException e){return false;}}));
 }
 @Test public void approvalAndMenuInputDoNotPollutePromptHistory()throws Exception {
  Path root=temp.newFolder("p").toPath();try(var fixture=new Fixture("a\ractual task\r",temp.newFolder("storage").toPath(),root)){
   assertEquals("a",fixture.input.auxiliary("approve > "));assertEquals(0,fixture.reader.getHistory().size());assertEquals("actual task",fixture.input.read());assertEquals(1,fixture.reader.getHistory().size());
  }
 }
 @Test public void fileCompletionHandlesQuotedSpacePathsAndCommandsHandleQuotedArguments()throws Exception {
  Path root=temp.newFolder("p").toPath();Files.createDirectories(root.resolve("src"));Files.writeString(root.resolve("src/file name.java"),"content");
  try(var fixture=new Fixture("@src/f\t\r",temp.newFolder("storage").toPath(),root)){
   var candidates=new ArrayList<Candidate>();CliInput.completer(()->new ProjectContext(root,root.resolve("config"))).complete(fixture.reader,fixture.reader.getParser().parse("@src/f",6,Parser.ParseContext.COMPLETE),candidates);assertEquals(candidates.toString(),1,candidates.size());
   String line=fixture.input.read();assertTrue("补全结果："+line,line.contains("file"));assertEquals(1,new ProjectContext(root,root.resolve("config")).prepare(line).attachments().size());
  }
  assertEquals(List.of("/kb","scaffold","demo","project with spaces"),CommandWords.parse("/kb scaffold demo \"project with spaces\""));
  assertEquals(List.of("/memory","delete","key with spaces"),CommandWords.parse("/memory delete key\\ with\\ spaces"));
  assertThrows(IllegalArgumentException.class,()->CommandWords.parse("/kb \"unterminated"));
 }
 static class Fixture implements AutoCloseable {
  Terminal terminal;LineReader reader;CliInput input;
  Fixture(String keys,Path storage,Path workspace)throws Exception {
   terminal=TerminalBuilder.builder().system(false).type("xterm").attributes(new Attributes()).encoding(StandardCharsets.UTF_8).size(new Size(120,40)).streams(new ByteArrayInputStream(keys.getBytes(StandardCharsets.UTF_8)),new ByteArrayOutputStream()).build();
   ProjectContext project=new ProjectContext(workspace,storage);var commandReader=new com.jiyingda.codly.command.CommandLineReader(terminal);commandReader.setParser(CliInput.parser());commandReader.setCompleter(CliInput.completer(()->project));reader=commandReader;new com.jiyingda.codly.command.CommandCompleter().install(reader);input=new CliInput(reader,terminal,storage,()->project);input.workspace(workspace);
  }
  public void close()throws Exception{input.close();terminal.close();}
 }
}
