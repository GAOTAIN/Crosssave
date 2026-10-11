package cn.crosssave;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SoundConfigTest {
    @TempDir
    Path gameDirectory;

    @Test
    void upgradeAddsSoundDefaultAndPreservesExistingChoices() throws IOException {
        Path file = gameDirectory.resolve("config/crosssave.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"disclaimerAccepted\":true,\"key\":\"J\",\"noiseEnabled\":false,"
                + "\"noiseManualOverride\":true,\"other\":17}", StandardCharsets.UTF_8);
        CrossSaveConfig config = CrossSaveConfig.load(gameDirectory);
        assertTrue(config.soundEnabled());
        assertTrue(config.disclaimerAccepted());
        assertFalse(config.noiseEnabled());
        assertTrue(config.noiseManualOverride());
        var upgraded = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertTrue(upgraded.get("soundEnabled").getAsBoolean());
        config.setSoundEnabled(false);
        assertFalse(CrossSaveConfig.load(gameDirectory).soundEnabled());
        var saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals("J", saved.get("key").getAsString());
        assertEquals(17, saved.get("other").getAsInt());
        assertFalse(saved.get("noiseEnabled").getAsBoolean());
        config.setSoundEnabled(true);
        assertTrue(CrossSaveConfig.load(gameDirectory).soundEnabled());
        try (var children = Files.list(file.getParent())) {
            assertEquals(1, children.count());
        }
    }

    @Test
    void acceptingDisclaimerDoesNotResetDisabledSound() throws IOException {
        CrossSaveConfig config = CrossSaveConfig.load(gameDirectory);
        config.setSoundEnabled(false);
        config.acceptDisclaimer();
        CrossSaveConfig reloaded = CrossSaveConfig.load(gameDirectory);
        assertTrue(reloaded.disclaimerAccepted());
        assertFalse(reloaded.soundEnabled());
    }

    @Test
    void failedSoundWritePreservesThePreviousInMemoryAndNoiseChoices() throws IOException {
        CrossSaveConfig config = CrossSaveConfig.load(gameDirectory);
        config.setNoiseEnabled(false);
        Path file = gameDirectory.resolve("config/crosssave.json");
        Files.delete(file);
        Files.delete(file.getParent());
        Files.writeString(file.getParent(), "阻止创建配置目录", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> config.setSoundEnabled(false));
        assertTrue(config.soundEnabled());
        assertFalse(config.noiseEnabled());
        assertTrue(config.noiseManualOverride());
    }
}
