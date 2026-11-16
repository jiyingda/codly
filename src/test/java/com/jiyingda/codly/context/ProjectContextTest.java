package com.jiyingda.codly.context;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import static org.junit.Assert.*;

public class ProjectContextTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 @Test public void quotedFilesRangesAndRulesHaveExplicitScopes()throws Exception {
  Path project=temp.newFolder("project").toPath(),global=temp.newFolder("config").toPath();Files.createDirectories(project.resolve("src"));
  Files.writeString(project.resolve("src/file name.java"),"one\ntwo\nthree\nfour\n");Files.writeString(global.resolve("AGENTS.md"),"GLOBAL_RULE");Files.writeString(project.resolve("AGENTS.md"),"ROOT_RULE");Files.writeString(project.resolve("src/AGENTS.md"),"SUB_RULE");
  ProjectContext context=new ProjectContext(project,global);var prepared=context.prepare("解释 @\"src/file name.java\"#2-3");
  assertEquals(java.util.List.of("src/file name.java#2-3"),prepared.attachments());assertTrue(prepared.message().contains("two\nthree\n"));assertFalse(prepared.message().contains("one\n"));
  String rules=context.ruleSection();assertTrue(rules.indexOf("GLOBAL_RULE")<rules.indexOf("ROOT_RULE"));assertTrue(rules.contains("仅适用于："+project.resolve("src")));assertTrue(rules.contains("SUB_RULE"));
 }
 @Test public void ignoresPatternsNegationAndNestedRulesAndRejectsEscapeAndBinary()throws Exception {
  Path project=temp.newFolder("project").toPath();Files.writeString(project.resolve(".gitignore"),"*.tmp\n!keep.tmp\nignored/\n");Files.writeString(project.resolve("drop.tmp"),"secret");Files.writeString(project.resolve("keep.tmp"),"keep");Files.createDirectory(project.resolve("ignored"));Files.writeString(project.resolve("ignored/a.txt"),"secret");
  Files.createDirectory(project.resolve("src"));Files.writeString(project.resolve("src/.gitignore"),"local.txt\n");Files.writeString(project.resolve("src/local.txt"),"secret");Files.writeString(project.resolve("local.txt"),"allowed");
  Files.write(project.resolve("binary.bin"),new byte[]{0,1,2});Path outside=temp.newFile("outside.txt").toPath();Files.createSymbolicLink(project.resolve("link.txt"),outside);
  ProjectContext context=new ProjectContext(project,temp.getRoot().toPath().resolve("config"));var files=context.files().list();assertTrue(files.contains("keep.tmp"));assertTrue(files.contains("local.txt"));assertFalse(files.contains("drop.tmp"));assertFalse(files.contains("src/local.txt"));assertFalse(files.contains("ignored/a.txt"));
  assertThrows(IllegalArgumentException.class,()->context.prepare("@drop.tmp"));assertThrows(IllegalArgumentException.class,()->context.prepare("@binary.bin"));assertThrows(IllegalArgumentException.class,()->context.prepare("@link.txt"));assertThrows(IllegalArgumentException.class,()->context.prepare("@../outside.txt"));
 }
 @Test public void largeTextRequiresRangeAndMalformedOrOutOfBoundsRangesFail()throws Exception {
  Path project=temp.newFolder("large").toPath();Files.writeString(project.resolve("large.txt"),("abcdefghij\n").repeat(10000));ProjectContext context=new ProjectContext(project,temp.getRoot().toPath().resolve("config"));
  assertNotNull(context.needsRange("@large.txt"));assertThrows(ProjectContext.LargeFile.class,()->context.prepare("@large.txt"));assertTrue(context.prepare("@large.txt#2-3").message().contains("abcdefghij\nabcdefghij"));
  assertThrows(IllegalArgumentException.class,()->context.prepare("@large.txt#0-5"));assertThrows(IllegalArgumentException.class,()->context.prepare("@large.txt#9-2"));assertThrows(IllegalArgumentException.class,()->context.prepare("@large.txt#20000"));
 }
 @Test public void nativeGitIgnoreRulesAreUsedIncludingInfoExclude()throws Exception {
  Path project=temp.newFolder("git-project").toPath();Process init=new ProcessBuilder("git","init","-q",project.toString()).start();assertEquals(0,init.waitFor());Files.writeString(project.resolve(".git/info/exclude"),"hidden.txt\n");Files.writeString(project.resolve("hidden.txt"),"hidden");Files.writeString(project.resolve("visible.txt"),"visible");
  var files=new ProjectFiles(project).list();assertFalse(files.contains("hidden.txt"));assertTrue(files.contains("visible.txt"));
 }
}
