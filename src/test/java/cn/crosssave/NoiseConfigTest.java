package cn.crosssave;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NoiseConfigTest {
    @TempDir
    Path gameDirectory;

    @Test
    void defaultsAndManualChoicesArePersistedWithoutDiscardingExistingFields() throws IOException {
        Path file = gameDirectory.resolve("config").resolve("crosssave.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"disclaimerAccepted\":true,\"key\":\"J\",\"other\":17}", StandardCharsets.UTF_8);
        CrossSaveConfig config = CrossSaveConfig.load(gameDirectory);
        assertTrue(config.noiseEnabled());
        assertFalse(config.noiseManualOverride());
        assertTrue(config.disclaimerAccepted());
        config.setNoiseEnabled(false);
        CrossSaveConfig reread = CrossSaveConfig.load(gameDirectory);
        assertFalse(reread.noiseEnabled());
        assertTrue(reread.noiseManualOverride());
        var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals("J", json.get("key").getAsString());
        assertEquals(17, json.get("other").getAsInt());
        config.setNoiseEnabled(true);
        assertTrue(CrossSaveConfig.load(gameDirectory).noiseEnabled());
        try (var children = Files.list(file.getParent())) {
            assertEquals(1, children.count());
        }
    }

    @Test
    void explicitlyConfiguredSwitchWithoutModeIsTreatedAsManual() throws IOException {
        Path file = gameDirectory.resolve("config").resolve("crosssave.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"noiseEnabled\":true}", StandardCharsets.UTF_8);
        CrossSaveConfig config = CrossSaveConfig.load(gameDirectory);
        assertTrue(config.noiseEnabled());
        assertTrue(config.noiseManualOverride());
        assertFalse(config.disclaimerAccepted());
    }

    @Test
    void failedWriteDoesNotChangeTheInMemoryChoice() throws IOException {
        CrossSaveConfig config = CrossSaveConfig.load(gameDirectory);
        Path file = gameDirectory.resolve("config").resolve("crosssave.json");
        Files.delete(file);
        Files.delete(file.getParent());
        Files.writeString(file.getParent(), "阻止创建配置目录", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> config.setNoiseEnabled(false));
        assertTrue(config.noiseEnabled());
        assertFalse(config.noiseManualOverride());
    }
}
