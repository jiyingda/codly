package com.jiyingda.codly.config;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.Assert.*;

public class SettingsStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void savesFirstRunAndPreservesUnknownFieldsAndSecrets() throws Exception {
        Path path = temporary.getRoot().toPath().resolve(".codly/settings.json");
        SettingsStore store = new SettingsStore(path);
        var first = store.read();
        first.values().putAll(valid());
        first.values().put("custom", JSON.parseObject("{\"keep\":true}"));
        store.save(first);
        var loaded = store.read();
        assertEquals("fake-secret", loaded.values().getString("apiKey"));
        loaded.values().put("defaultModel", "new-model");
        store.save(loaded);
        assertTrue(store.read().values().getJSONObject("custom").getBooleanValue("keep"));
        assertEquals("new-model", store.read().values().getString("defaultModel"));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(path));
    }

    @Test public void refusesToOverwriteConcurrentEditsOrInvalidConfiguration() throws Exception {
        Path path = temporary.newFile("settings.json").toPath();
        Files.writeString(path, valid().toJSONString());
        SettingsStore store = new SettingsStore(path);
        var document = store.read();
        Files.writeString(path, "{\"external\":true}");
        assertThrows(IOException.class, () -> store.save(document));
        assertEquals("{\"external\":true}", Files.readString(path));
        document.values().put("apiKey", "");
        assertThrows(IllegalArgumentException.class, () -> store.save(document));
        assertEquals("{\"external\":true}", Files.readString(path));
    }

    @Test public void supportsBothProvidersAndRejectsInvalidEndpoints() {
        JSONObject settings = valid();
        settings.put("provider", "deepseek");
        assertThrows(IllegalArgumentException.class, () -> SettingsStore.validate(settings));
        settings.put("deepseek", JSON.parseObject("{\"apiKey\":\"fake-deepseek\",\"model\":\"fake-model\"}"));
        SettingsStore.validate(settings);
        settings.put("apiUrl", "file:///tmp/anything");
        assertThrows(IllegalArgumentException.class, () -> SettingsStore.validate(settings));
        settings.put("apiUrl", "https://user:secret@example.com/chat");
        assertFalse(assertThrows(IllegalArgumentException.class, () -> SettingsStore.validate(settings)).getMessage().contains("secret"));
    }

    @Test public void validatesMcpTransportsArgumentsAndCredentialMapsWithoutStartingProcesses() {
        SettingsStore.validateMcp(SettingsStore.parseMcp("{\"local\":{\"command\":\"fake-server\",\"args\":[\"--test\"],\"env\":{\"TOKEN\":\"fake\"}},\"remote\":{\"type\":\"http\",\"url\":\"http://localhost:4321/mcp\",\"headers\":{\"Authorization\":\"fake\"}}}"));
        for (String invalid : new String[]{
                "{\"server\":{}}", "{\"server\":{\"command\":\"fake\",\"url\":\"http://localhost\"}}",
                "{\"server\":{\"command\":\"fake\",\"args\":[123]}}",
                "{\"server\":{\"command\":\"fake\",\"env\":{\"TOKEN\":123}}}",
                "{\"server\":{\"type\":\"stdio\",\"url\":\"http://localhost\"}}"}) {
            assertThrows(invalid, IllegalArgumentException.class, () -> SettingsStore.parseMcp(invalid));
        }
    }

    @Test public void validatesSkillDirectoriesAndNamesBeforeSaving() {
        JSONObject settings = valid();
        settings.put("skills", JSON.parseObject("{\"directories\":[\"~/skills\",\"/tmp/my skills\"],\"enabled\":[\"my-skill\"]}"));
        SettingsStore.validate(settings);
        settings.getJSONObject("skills").put("directories", JSON.parseArray("[\"relative/path\"]"));
        assertThrows(IllegalArgumentException.class, () -> SettingsStore.validate(settings));
        settings.getJSONObject("skills").put("directories", JSON.parseArray("[]"));
        settings.getJSONObject("skills").put("enabled", JSON.parseArray("[\"../bad-name\"]"));
        assertThrows(IllegalArgumentException.class, () -> SettingsStore.validate(settings));
    }

    @Test public void malformedJsonErrorsDoNotEchoCredentialValues() throws Exception {
        Path path = temporary.newFile("bad.json").toPath();
        Files.writeString(path, "{\"apiKey\":\"do-not-echo-this-secret\"");
        assertFalse(assertThrows(IOException.class, () -> new SettingsStore(path).read())
                .getMessage().contains("do-not-echo"));
        assertFalse(assertThrows(IllegalArgumentException.class,
                () -> SettingsStore.parseMcp("{\"token\":\"do-not-echo-this-secret\""))
                .getMessage().contains("do-not-echo"));
    }

    @Test public void reloadingRemovedProviderSectionClearsStaleCredentialsAndReportsBrokenJson() throws Exception {
        Path path = temporary.newFile("config.json").toPath();
        JSONObject values = valid();
        values.put("deepseek", JSON.parseObject("{\"apiKey\":\"old-secret\"}"));
        Files.writeString(path, values.toJSONString());
        Config config = new Config(path);
        assertEquals("old-secret", config.getDeepseekApiKey());
        Files.writeString(path, valid().toJSONString());
        config.load();
        assertNull(config.getDeepseekApiKey());
        assertFalse(config.isNotConfigLoaded());
        Files.writeString(path, "{\"apiKey\":\"never-echo-secret\"");
        config.load();
        assertTrue(config.isNotConfigLoaded());
        assertFalse(config.getLoadError().contains("never-echo"));
    }

    private JSONObject valid() {
        return JSON.parseObject("{\"provider\":\"qwen\",\"apiKey\":\"fake-secret\",\"defaultModel\":\"fake-model\"}");
    }
}
