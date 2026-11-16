package com.jiyingda.codly.context;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** 显式文件引用及带路径作用域的 AGENTS.md。文件正文作为数据附加，规则独立放入系统提示词。 */
public final class ProjectContext {
    public static final int MAX_ATTACHMENT=65536;
    private static final Pattern REFERENCES=Pattern.compile("(?<![\\w@])@(?:\"([^\"]+)\"|'([^']+)'|((?:\\\\.|[^\\s])+))");
    private static final Pattern RANGE=Pattern.compile("^(.*)#L?(\\d+)(?:-L?(\\d+))?$");
    private final Path workspace,configRoot;
    private final ProjectFiles files;
    public record Reference(String token,String path,int start,int end) {}
    public record Prepared(String message,List<String> attachments) {}
    public record Rule(Path file,String scope,String content) {}
    public static final class LargeFile extends IllegalArgumentException {
        public final Reference reference;
        public LargeFile(Reference reference){super("文件 "+reference.path()+" 超过 64KB，请使用 @\""+reference.path()+"\"#20-80 指定行范围");this.reference=reference;}
    }
    public ProjectContext(Path workspace,Path configRoot) {this.workspace=workspace.toAbsolutePath().normalize();this.configRoot=configRoot;files=new ProjectFiles(this.workspace);}
    public ProjectFiles files(){return files;}
    public List<Reference> references(String input) {
        List<Reference> references=new ArrayList<>();Matcher matcher=REFERENCES.matcher(input);
        while(matcher.find()) {
            String path=matcher.group(1)!=null?matcher.group(1):matcher.group(2)!=null?matcher.group(2):matcher.group(3);
            if(matcher.group(3)!=null)path=path.replaceAll("\\\\(.)", "$1");
            // 引号后的 #范围属于引用；无引号时范围已在 path 内。
            String token=matcher.group();int end=matcher.end();
            if(matcher.group(1)!=null||matcher.group(2)!=null){Matcher suffix=Pattern.compile("^#L?\\d+(?:-L?\\d+)?").matcher(input.substring(end));if(suffix.find()){path+=suffix.group();token+=suffix.group();}}
            int from=0,to=0;Matcher range=RANGE.matcher(path);
            if(range.matches()) {path=range.group(1);try{from=Integer.parseInt(range.group(2));to=range.group(3)==null?from:Integer.parseInt(range.group(3));}catch(NumberFormatException error){throw new IllegalArgumentException("文件行范围无效");}
                if(from<1||to<from||to-from>=2000)throw new IllegalArgumentException("行范围必须递增，单次最多 2000 行");}
            // @name（如聊天中的提及）只有在对应文件存在时才作为引用；显式路径引用缺失时给出错误。
            if(!Files.exists(workspace.resolve(path))&&matcher.group(1)==null&&matcher.group(2)==null&&!path.contains("/")&&!path.matches("(?i).*\\.(java|kt|ts|tsx|js|jsx|py|go|rs|md|txt|json|yaml|yml|xml|html|css|sql)(#.*)?"))continue;
            references.add(new Reference(token,path,from,to));
        }return references;
    }
    public Path secureFile(Reference reference)throws IOException {
        Path path=workspace.resolve(reference.path()).normalize();Path root=workspace.toRealPath();
        if(!path.startsWith(workspace))throw new IllegalArgumentException("文件引用必须位于当前工作区内");
        Path current=workspace;
        for(Path part:workspace.relativize(path)){current=current.resolve(part);if(Files.isSymbolicLink(current))throw new IllegalArgumentException("文件引用不支持符号链接路径");}
        Path real=path.toRealPath();if(!real.startsWith(root)||!Files.isRegularFile(real))throw new IllegalArgumentException("文件引用必须是工作区内的普通文件");
        String relative=workspace.relativize(path).toString().replace('\\','/');
        if(!files.list().contains(relative)&&!files.list(true).contains(relative))throw new IllegalArgumentException("文件被忽略规则排除："+reference.path());
        return real;
    }
    public Reference needsRange(String input)throws IOException {
        for(Reference reference:references(input))if(reference.start()==0&&Files.size(secureFile(reference))>MAX_ATTACHMENT)return reference;
        return null;
    }
    public Prepared prepare(String input)throws IOException {
        StringBuilder result=new StringBuilder(input);List<String> attached=new ArrayList<>();int chars=0;
        for(Reference reference:references(input)) {
            Path path=secureFile(reference);String text;
            if(reference.start()==0) {
                if(Files.size(path)>MAX_ATTACHMENT)throw new LargeFile(reference);
                byte[] bytes=Files.readAllBytes(path);
                try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
                catch(CharacterCodingException error){throw new IllegalArgumentException("文件不是 UTF-8 文本，请选择文本文件："+reference.path());}
            } else {
                StringBuilder selected=new StringBuilder();int number=1;boolean any=false;
                try(BufferedReader reader=Files.newBufferedReader(path)) {
                    char[] buffer=new char[4096];int count;
                    outer:while((count=reader.read(buffer))!=-1)for(int i=0;i<count;i++){
                        char c=buffer[i];if(number>=reference.start()){selected.append(c);any=true;}
                        if(selected.length()>MAX_ATTACHMENT)throw new IllegalArgumentException("所选范围超过 64KB，请缩小行范围");
                        if(c=='\n'&&++number>reference.end())break outer;
                    }
                }
                if(!any||number<reference.start())throw new IllegalArgumentException("文件行范围超出文件末尾："+reference.path());text=selected.toString();
            }
            if(text.indexOf('\0')>=0)throw new IllegalArgumentException("不支持附加二进制文件："+reference.path());
            chars+=text.length();if(chars>MAX_ATTACHMENT*4)throw new IllegalArgumentException("附加文件总量超过 256KB，请减少文件或缩小行范围");
            String label=reference.path()+(reference.start()>0?"#"+reference.start()+"-"+reference.end():"");attached.add(label);
            result.append("\n\n## Attached file: ").append(label).append("\n<file_content>\n").append(text).append("\n</file_content>");
        }return new Prepared(result.toString(),List.copyOf(attached));
    }
    public List<Rule> rules()throws IOException {
        LinkedHashMap<Path,String> paths=new LinkedHashMap<>();
        paths.put(configRoot.resolve("AGENTS.md"),"全局：所有项目");
        // 仓库祖先规则只到最近的 .git 边界；无 Git 时只自动读取工作区根。
        Path repository=workspace;
        for(Path p=workspace;p!=null;p=p.getParent())if(Files.exists(p.resolve(".git"))){repository=p;break;}
        List<Path> ancestors=new ArrayList<>();for(Path p=workspace;p!=null&&p.startsWith(repository);p=p.getParent())ancestors.add(p);Collections.reverse(ancestors);
        for(Path parent:ancestors)paths.put(parent.resolve("AGENTS.md"),"项目/祖先目录："+parent+" 及其子目录");
        for(String file:files.list())if(file.endsWith("/AGENTS.md")) {Path path=workspace.resolve(file);paths.put(path,"仅适用于："+path.getParent()+" 及其子目录");}
        List<Rule> rules=new ArrayList<>();int size=0;
        for(var entry:paths.entrySet())if(Files.isRegularFile(entry.getKey(),LinkOption.NOFOLLOW_LINKS)) {
            if(Files.size(entry.getKey())>MAX_ATTACHMENT)throw new IllegalArgumentException("规则文件超过 64KB："+entry.getKey());
            String content=Files.readString(entry.getKey());size+=content.length();if(size>MAX_ATTACHMENT*2||rules.size()>=128)throw new IllegalArgumentException("项目规则超过加载上限，请精简 AGENTS.md");
            rules.add(new Rule(entry.getKey(),entry.getValue(),content));
        }return rules;
    }
    public String ruleSection()throws IOException {
        var rules=rules();if(rules.isEmpty())return "";
        StringBuilder text=new StringBuilder("\n\n## Project instructions\n全局规则适用于全部工作；目录规则只适用于标注的路径，越具体的目录规则在其范围内优先。以下子目录规则不影响其他目录。\n");
        for(Rule rule:rules)text.append("\n### ").append(rule.file()).append("\n作用域：").append(rule.scope()).append('\n').append(rule.content()).append('\n');
        return text.toString();
    }
}
