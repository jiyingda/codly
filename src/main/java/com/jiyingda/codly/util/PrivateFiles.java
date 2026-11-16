package com.jiyingda.codly.util;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class PrivateFiles {
    private PrivateFiles() {}
    public static void directory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)) throw new IOException("存储目录不能是符号链接");
        Files.createDirectories(directory);
        try { Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException ignored) { }
    }
    public static void file(Path path) throws IOException {
        directory(path.getParent());
        if (Files.isSymbolicLink(path)) throw new IOException("存储文件不能是符号链接");
        if (!Files.exists(path)) Files.createFile(path);
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { }
    }
    public static void write(Path path, String text) throws IOException {
        directory(path.getParent());
        if (Files.isSymbolicLink(path)) throw new IOException("存储文件不能是符号链接");
        Path temporary=Files.createTempFile(path.getParent(),".codly-",".tmp");
        try {
            file(temporary); Files.writeString(temporary,text,StandardCharsets.UTF_8);
            try { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    public static String key(Path workspace) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(workspace.toRealPath().toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
