package com.jiyingda.codly.task;

import com.alibaba.fastjson.*;
import com.jiyingda.codly.core.CancellationToken;
import com.jiyingda.codly.util.PrivateFiles;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** 进程和完整日志由当前 Codly 进程管理；正常退出时停止后台任务，保留日志和元数据。 */
public final class TaskManager implements AutoCloseable {
    private final Path directory;
    private final Map<String,Job> jobs=new ConcurrentHashMap<>();
    private final ExecutorService monitor=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"Codly-Task-Monitor");t.setDaemon(true);return t;});
    private final Thread shutdown;
    private volatile boolean closed;
    private static final class Job {
        String id,command,workspace,startedAt;long ownerPid=ProcessHandle.current().pid();volatile String state="RUNNING";volatile Integer exitCode;Process process;
        Map<String,Object> info(Path directory){Map<String,Object> value=new LinkedHashMap<>();value.put("id",id);value.put("command",command);value.put("workspace",workspace);value.put("startedAt",startedAt);value.put("state",state);value.put("exitCode",exitCode);value.put("logFile",directory.resolve(id+".log").toString());value.put("ownerPid",ownerPid);return value;}
    }
    public TaskManager(Path directory,boolean shutdownHook)throws IOException {
        this.directory=directory.toAbsolutePath().normalize();PrivateFiles.directory(this.directory);
        try(var paths=Files.list(this.directory)) {
            for(Path path:paths.filter(p->p.getFileName().toString().matches("[a-f0-9]{32}\\.json")&&!Files.isSymbolicLink(p)).limit(500).toList())try {
                JSONObject value=JSON.parseObject(Files.readString(path));
                if("RUNNING".equals(value.getString("state"))&&ProcessHandle.of(value.getLongValue("ownerPid")).map(ProcessHandle::isAlive).orElse(false))continue;
                Job job=new Job();job.ownerPid=value.getLongValue("ownerPid");job.id=path.getFileName().toString().replace(".json","");job.command=value.getString("command");job.workspace=value.getString("workspace");job.startedAt=value.getString("startedAt");job.state="RUNNING".equals(value.getString("state"))?"INTERRUPTED":value.getString("state");job.exitCode=value.getInteger("exitCode");jobs.put(job.id,job);
            }catch(RuntimeException|IOException ignored){}
        }
        shutdown=shutdownHook?new Thread(this::close,"Codly-Task-Shutdown"):null;
        if(shutdown!=null)Runtime.getRuntime().addShutdownHook(shutdown);
    }
    public synchronized String start(String command,Path workspace,int timeoutSeconds)throws IOException {
        if(closed)throw new IllegalStateException("任务管理器已关闭");
        Job job=new Job();job.id=UUID.randomUUID().toString().replace("-","");job.command=command;job.workspace=workspace.toRealPath().toString();job.startedAt=Instant.now().toString();
        Path output=directory.resolve(job.id+".log");PrivateFiles.file(output);
        String wrapper="trap 'for pid in $(jobs -pr); do kill \"$pid\" 2>/dev/null || true; done' EXIT; eval \"$1\"; result=$?; wait; exit \"$result\"";
        job.process=new ProcessBuilder("bash","-c",wrapper,"codly-task",command).directory(workspace.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();jobs.put(job.id,job);
        try{persist(job);}catch(IOException error){stopJob(job,"FAILED");throw error;}
        monitor.submit(()->{
            try {
                if(!job.process.waitFor(timeoutSeconds,TimeUnit.SECONDS)){stopJob(job,"TIMED_OUT");}
                synchronized(job){if("RUNNING".equals(job.state)){job.exitCode=job.process.exitValue();job.state=job.exitCode==0?"COMPLETED":"FAILED";}persist(job);}
            }catch(InterruptedException error){Thread.currentThread().interrupt();stopJob(job,"STOPPED");}
            catch(Exception error){stopJob(job,"FAILED");}
        });return job.id;
    }
    private void persist(Job job)throws IOException {PrivateFiles.write(directory.resolve(job.id+".json"),JSON.toJSONString(job.info(directory)));}
    private Job job(String id){if(id==null||!id.matches("[a-f0-9]{32}")||!jobs.containsKey(id))throw new IllegalArgumentException("任务 ID 不存在");return jobs.get(id);}
    public List<Map<String,Object>> list(){return jobs.values().stream().sorted(Comparator.comparing((Job j)->Objects.toString(j.startedAt,"")).reversed()).map(j->j.info(directory)).toList();}
    public Map<String,Object> status(String id){return job(id).info(directory);}
    public Map<String,Object> output(String id,long offset,int limit)throws IOException {
        Job job=job(id);if(offset<0||limit<4||limit>65536)throw new IllegalArgumentException("offset 必须非负，limit 必须为 4..65536 字节");
        Path path=directory.resolve(id+".log");if(Files.isSymbolicLink(path))throw new IOException("任务日志不能是符号链接");
        try(RandomAccessFile file=new RandomAccessFile(path.toFile(),"r")) {
            long size=file.length();if(offset>size)throw new IllegalArgumentException("offset 超出日志长度");
            file.seek(offset);byte[] bytes=new byte[(int)Math.min(limit,size-offset)];file.readFully(bytes);
            if(bytes.length>0&&(bytes[0]&0xC0)==0x80)throw new IllegalArgumentException("offset 位于 UTF-8 字符中间，请使用上次返回的 nextOffset");
            int length=bytes.length;
            if(length>0&&(offset+length<size||"RUNNING".equals(job.state))){int start=length-1;while(start>0&&(bytes[start]&0xC0)==0x80)start--;int lead=bytes[start]&255;int needed=lead<128?1:lead>=240?4:lead>=224?3:lead>=192?2:1;if(length-start<needed)length=start;}
            Map<String,Object> result=new LinkedHashMap<>(job.info(directory));result.put("offset",offset);result.put("nextOffset",offset+length);result.put("size",size);result.put("hasMore",offset+length<size);result.put("output",new String(bytes,0,length,StandardCharsets.UTF_8));return result;
        }
    }
    public String tail(String id,int characters)throws IOException {
        Job job=job(id);Path path=directory.resolve(id+".log");if(Files.isSymbolicLink(path))throw new IOException("任务日志不能是符号链接");
        try(RandomAccessFile file=new RandomAccessFile(path.toFile(),"r")) {
            long size=file.length();file.seek(Math.max(0,size-(long)characters*4));byte[] bytes=new byte[(int)(size-file.getFilePointer())];file.readFully(bytes);String text=new String(bytes,StandardCharsets.UTF_8);
            boolean truncated=file.length()>bytes.length||text.length()>characters;if(text.length()>characters)text=text.substring(text.length()-characters);
            return (truncated?"[已截断，仅展示尾部；完整输出："+path+"]\n":"")+text;
        }
    }
    public Map<String,Object> await(String id,int yieldSeconds,CancellationToken cancellation,Consumer<String> output)throws IOException {
        Job job=job(id);Runnable cancel=()->stopJob(job,"CANCELLED");cancellation.register(cancel);
        long deadline=yieldSeconds<=0?Long.MAX_VALUE:System.nanoTime()+TimeUnit.SECONDS.toNanos(yieldSeconds);long offset=0;int streamed=0;
        try {
            while("RUNNING".equals(job.state)) {
                if(cancellation.isCancelled())break;
                if(output!=null&&streamed<30000){var part=output(id,offset,Math.max(4,Math.min(8192,30000-streamed)));offset=((Number)part.get("nextOffset")).longValue();String text=(String)part.get("output");if(!text.isEmpty()){output.accept(text);streamed+=text.length();}}
                if(System.nanoTime()>=deadline)break;
                try{Thread.sleep(80);}catch(InterruptedException error){Thread.currentThread().interrupt();cancel.run();break;}
            }
            if(output!=null&&streamed<30000){var part=output(id,offset,Math.max(4,Math.min(8192,30000-streamed)));String text=(String)part.get("output");if(!text.isEmpty())output.accept(text);}
            return job.info(directory);
        }finally{cancellation.unregister(cancel);}
    }
    public Map<String,Object> stop(String id){Job job=job(id);stopJob(job,"STOPPED");return job.info(directory);}
    private void stopJob(Job job,String state) {
        synchronized(job) {
            if(!"RUNNING".equals(job.state))return;
            if(job.process!=null){try{job.process.descendants().forEach(ProcessHandle::destroyForcibly);}catch(RuntimeException ignored){}finally{job.process.destroyForcibly();}}
            job.state=state;try{persist(job);}catch(IOException ignored){}
        }
    }
    @Override public synchronized void close(){if(closed)return;closed=true;jobs.values().forEach(job->stopJob(job,"STOPPED"));monitor.shutdownNow();if(shutdown!=null&&Thread.currentThread()!=shutdown)try{Runtime.getRuntime().removeShutdownHook(shutdown);}catch(IllegalStateException ignored){}}
}
