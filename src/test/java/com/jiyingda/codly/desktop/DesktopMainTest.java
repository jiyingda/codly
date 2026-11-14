package com.jiyingda.codly.desktop;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class DesktopMainTest {
    @Test public void usesCurrentDirectoryWhenNoArgumentIsProvided() throws Exception {
        assertEquals(Path.of("").toRealPath(), DesktopMain.resolveWorkspace(new String[0]));
    }
    @Test public void usesExplicitDirectoryAndRejectsFiles() throws Exception {
        Path file = Files.createTempFile("codly-desktop", ".txt");
        try {
            assertEquals(file.getParent().toRealPath(), DesktopMain.resolveWorkspace(new String[] { file.getParent().toString() }));
            assertThrows(java.io.IOException.class, () -> DesktopMain.resolveWorkspace(new String[] { file.toString() }));
        } finally { Files.deleteIfExists(file); }
    }
}
