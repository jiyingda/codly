package com.jiyingda.codly.cli;

import com.jiyingda.codly.command.CommandCompleter;
import com.jiyingda.codly.context.ProjectContext;
import com.jiyingda.codly.util.*;
import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;

/** 提示词编辑与按项目持久化历史；批准、目录和菜单输入不进入提示词历史。 */
public final class CliInput implements AutoCloseable {
    private final LineReader reader;private final Terminal terminal;private final Path storage;private final Supplier<ProjectContext> project;
    private Path workspace,draftFile;private String pending="";private boolean editing,editorRequested;
    public CliInput(LineReader reader,Terminal terminal,Path storage,Supplier<ProjectContext> project) {
        this.reader=reader;this.terminal=terminal;this.storage=storage;this.project=project;
        reader.option(LineReader.Option.BRACKETED_PASTE,true);reader.option(LineReader.Option.DISABLE_EVENT_EXPANSION,true);
        reader.option(LineReader.Option.HISTORY_IGNORE_SPACE,false);reader.option(LineReader.Option.HISTORY_REDUCE_BLANKS,false);
        reader.option(LineReader.Option.HISTORY_TIMESTAMPED,true);reader.option(LineReader.Option.HISTORY_IGNORE_DUPS,true);
        reader.getWidgets().put("codly-newline",()->{reader.getBuffer().write('\n');reader.callWidget(LineReader.REDISPLAY);return true;});
        reader.getWidgets().put("codly-editor",()->{if(!editing)return false;editorRequested=true;reader.callWidget(LineReader.ACCEPT_LINE);return true;});
        reader.getWidgets().put("codly-draft",()->{if(!editing)return false;try{reader.getBuffer().clear();reader.getBuffer().write(loadDraft());reader.callWidget(LineReader.REDISPLAY);}catch(IOException e){reader.printAbove("恢复草稿失败："+e.getMessage());}return true;});
        var keys=reader.getKeyMaps().get(LineReader.MAIN);
        keys.bind(new Reference("codly-newline"),"\n","\033\r","\033[13;2u","\033[27;2;13~");
        keys.bind(new Reference("codly-editor"),"\030\005");keys.bind(new Reference("codly-draft"),"\030\022");
    }
    public static Parser parser(){return new DefaultParser().eofOnEscapedNewLine(true).eofOnUnclosedQuote(false);}
    public static Completer completer(Supplier<ProjectContext> project) {
        CommandCompleter commands=new CommandCompleter();
        return (reader,line,candidates)->{
            commands.complete(reader,line,candidates);
            if(!line.word().startsWith("@"))return;
            ProjectContext context=project.get();if(context==null)return;
            String prefix=line.word().substring(1).replace("\"","").replace("'","");if(prefix.contains("#"))return;
            try{for(String path:context.files().list())if(path.startsWith(prefix)){String value="@"+path;candidates.add(new Candidate(value,path,"文件","附加文件；#起始行-结束行 可限制范围",null,null,true));if(candidates.size()>=200)break;}}
            catch(IOException ignored){}
        };
    }
    public void workspace(Path target)throws IOException {
        target=target.toRealPath();if(target.equals(workspace))return;
        if(workspace!=null)reader.getHistory().save();
        String key=PrivateFiles.key(target);Path path=storage.resolve("input-history").resolve(key+".history");PrivateFiles.file(path);
        reader.setVariable(LineReader.HISTORY_FILE,path);reader.getHistory().load();workspace=target;
        draftFile=storage.resolve("drafts").resolve(key+".txt");pending="";
    }
    public String read()throws IOException {
        while(true){
            String value;
            try{editing=true;value=reader.readLine("> ",null,pending);pending="";}
            catch(UserInterruptException error){if(error.getPartialLine()!=null&&!error.getPartialLine().isBlank())saveDraft(error.getPartialLine());throw error;}
            finally{editing=false;}
            if(editorRequested){editorRequested=false;saveDraft(value);pending=edit(value);continue;}
            reader.getHistory().save();if(value.equals(loadDraft()))saveDraft("");return value;
        }
    }
    public String auxiliary(String prompt){Object previous=reader.getVariable(LineReader.DISABLE_HISTORY);reader.setVariable(LineReader.DISABLE_HISTORY,true);try{return reader.readLine(prompt);}finally{reader.setVariable(LineReader.DISABLE_HISTORY,previous);}}
    public void editor()throws IOException {pending=edit(loadDraft());}
    public void draft()throws IOException {pending=loadDraft();}
    public String loadDraft()throws IOException {return draftFile!=null&&Files.isRegularFile(draftFile,LinkOption.NOFOLLOW_LINKS)?Files.readString(draftFile):"";}
    public void saveDraft(String value)throws IOException {if(draftFile!=null)PrivateFiles.write(draftFile,value);}
    private String edit(String value)throws IOException {
        String editor=System.getenv("VISUAL");if(editor==null||editor.isBlank())editor=System.getenv("EDITOR");if(editor==null||editor.isBlank())editor="vi";
        PrivateFiles.directory(storage.resolve("drafts"));Path file=Files.createTempFile(storage.resolve("drafts"),"editor-",".txt");boolean paused=false;
        try {
            PrivateFiles.write(file,value);List<String> command=new ArrayList<>(CommandWords.parse(editor));if(command.isEmpty())throw new IOException("EDITOR 未配置有效命令");command.add(file.toString());
            if(terminal.canPauseResume()){terminal.pause();paused=true;}
            Process process=new ProcessBuilder(command).inheritIO().start();int code;
            try{code=process.waitFor();}catch(InterruptedException error){Thread.currentThread().interrupt();process.destroyForcibly();throw new IOException("编辑器已中断");}
            if(code!=0)throw new IOException("编辑器退出码 "+code+"，原草稿已保留");
            if(Files.size(file)>1024*1024)throw new IOException("编辑内容超过 1MB");String result=Files.readString(file);saveDraft(result);return result;
        }finally{if(paused)terminal.resume();Files.deleteIfExists(file);}
    }
    @Override public void close()throws IOException {reader.getHistory().save();}
}
