package com.jiyingda.codly.context;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Git 项目使用原生忽略规则；非 Git 项目按目录读取 .gitignore / .codlyignore。 */
public final class ProjectFiles {
    private final Path root;
    private List<String> cache=List.of(); private long cachedAt;
    private static final Set<String> EXCLUDED=Set.of(".git",".codly",".aws","node_modules","target",".gradle");
    public ProjectFiles(Path root) { this.root=root.toAbsolutePath().normalize(); }
    public synchronized List<String> list() throws IOException { return list(false); }
    public synchronized List<String> list(boolean refresh) throws IOException {
        if (!refresh && cachedAt!=0 && System.nanoTime()-cachedAt<2_000_000_000L) return cache;
        List<String> result=gitFiles();
        if (result==null) result=walk();
        cache=result.stream().filter(path->Arrays.stream(path.split("/")).noneMatch(EXCLUDED::contains)).distinct().sorted().toList();
        cachedAt=System.nanoTime(); return cache;
    }
    private List<String> gitFiles() throws IOException {
        Path output=Files.createTempFile("codly-files-",".txt");Process process=null;
        try {
            process=new ProcessBuilder("git","ls-files","--cached","--others","--exclude-standard","-z","--",".")
                    .directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(output.toFile()).start();
            if(!process.waitFor(3,TimeUnit.SECONDS)){process.destroyForcibly();return null;}
            if(process.exitValue()!=0)return null;
            if(Files.size(output)>8_000_000)throw new IOException("项目文件过多，请缩小工作区");
            return Arrays.asList(Files.readString(output).split("\0"));
        } catch(IOException error) { if (process==null) return null;throw error; }
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException("文件扫描已中断");}
        finally {if(process!=null&&process.isAlive())process.destroyForcibly();Files.deleteIfExists(output);}
    }
    private List<String> walk() throws IOException {
        List<String> result=new ArrayList<>();List<Rule> rules=new ArrayList<>();
        Files.walkFileTree(root,EnumSet.noneOf(FileVisitOption.class),64,new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs)throws IOException {
                if(!dir.equals(root)&&(EXCLUDED.contains(dir.getFileName().toString())||ignored(dir,true,rules)))return FileVisitResult.SKIP_SUBTREE;
                for(String name:List.of(".gitignore",".codlyignore")) {
                    Path file=dir.resolve(name);
                    if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&Files.size(file)<65536)
                        for(String line:Files.readAllLines(file)) {Rule rule=Rule.parse(root.relativize(dir).toString().replace('\\','/'),line);if(rule!=null)rules.add(rule);}
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException {
                if(attrs.isRegularFile()&&!ignored(file,false,rules))result.add(root.relativize(file).toString().replace('\\','/'));
                if(result.size()>100_000)throw new IOException("项目文件过多，请缩小工作区");return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file,IOException error){return FileVisitResult.CONTINUE;}
        });return result;
    }
    private boolean ignored(Path path,boolean dir,List<Rule> rules) {
        String relative=root.relativize(path).toString().replace('\\','/');boolean ignored=false;
        for(Rule rule:rules)if((!rule.directory||dir)&&rule.pattern.matcher(relative).matches())ignored=!rule.negated;
        return ignored;
    }
    private record Rule(Pattern pattern,boolean negated,boolean directory) {
        static Rule parse(String parent,String input) {
            String line=input.strip();if(line.isEmpty()||line.startsWith("#"))return null;
            boolean negate=line.startsWith("!");if(negate)line=line.substring(1);
            boolean directory=line.endsWith("/");if(directory)line=line.substring(0,line.length()-1);
            boolean anchored=line.startsWith("/");if(anchored)line=line.substring(1);
            if(line.isEmpty())return null;
            StringBuilder regex=new StringBuilder("^");if(!parent.isEmpty())regex.append(Pattern.quote(parent)).append('/');
            if(!anchored&&!line.contains("/"))regex.append("(?:.*/)?");
            for(int i=0;i<line.length();i++) {
                char c=line.charAt(i);
                if(c=='*'&&i+1<line.length()&&line.charAt(i+1)=='*') {i++;if(i+1<line.length()&&line.charAt(i+1)=='/'){i++;regex.append("(?:.*/)?");}else regex.append(".*");}
                else if(c=='*')regex.append("[^/]*");else if(c=='?')regex.append("[^/]");else regex.append(Pattern.quote(String.valueOf(c)));
            }
            return new Rule(Pattern.compile(regex+"$"),negate,directory);
        }
    }
}
