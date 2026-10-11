package cn.crosssave;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public final class CrossSaveConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path path;
    private JsonObject values;
    private boolean disclaimerAccepted;

    private CrossSaveConfig(Path path, JsonObject values) {
        this.path = path;
        this.values = values;
        // 只有明确的布尔值 true 才视为已接受，损坏的配置不会绕过提示。
        var accepted = values.get("disclaimerAccepted");
        disclaimerAccepted = accepted != null && accepted.isJsonPrimitive()
                && accepted.getAsJsonPrimitive().isBoolean() && accepted.getAsBoolean();
    }

    public static CrossSaveConfig load(Path gameDirectory) {
        Path path = gameDirectory.resolve("config").resolve("crosssave.json");
        JsonObject values = new JsonObject();
        if (Files.isRegularFile(path)) {
            try {
                values = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (IOException | RuntimeException exception) {
                CrossSaveClient.LOGGER.warn("读取 CrossSave 配置失败，将显示免责声明", exception);
            }
        }
        CrossSaveConfig config = new CrossSaveConfig(path, values);
        if (!values.has("noiseEnabled") || !values.has("noiseManualOverride") || !values.has("soundEnabled")) {
            JsonObject updated = values.deepCopy();
            if (!updated.has("noiseEnabled")) {
                updated.addProperty("noiseEnabled", true);
            }
            if (!updated.has("noiseManualOverride")) {
                // 已有配置显式写入 noiseEnabled 时视为玩家手动配置，新配置仍使用自适应。
                updated.addProperty("noiseManualOverride", values.has("noiseEnabled"));
            }
            if (!updated.has("soundEnabled")) {
                updated.addProperty("soundEnabled", true);
            }
            try {
                config.write(updated);
            } catch (IOException exception) {
                CrossSaveClient.LOGGER.warn("写入默认特效配置失败，将使用内存中的默认值", exception);
            }
        }
        return config;
    }

    public boolean disclaimerAccepted() {
        return disclaimerAccepted;
    }

    public boolean noiseEnabled() {
        return booleanValue("noiseEnabled", true);
    }

    public boolean soundEnabled() {
        return booleanValue("soundEnabled", true);
    }

    public void setSoundEnabled(boolean enabled) throws IOException {
        JsonObject updated = values.deepCopy();
        updated.addProperty("soundEnabled", enabled);
        write(updated);
    }

    public boolean noiseManualOverride() {
        return booleanValue("noiseManualOverride", false);
    }

    private boolean booleanValue(String key, boolean fallback) {
        var value = values.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                ? value.getAsBoolean() : fallback;
    }

    public void setNoiseEnabled(boolean enabled) throws IOException {
        JsonObject updated = values.deepCopy();
        updated.addProperty("noiseEnabled", enabled);
        updated.addProperty("noiseManualOverride", true);
        write(updated);
    }

    public InputUtil.Key openScreenKey() {
        try {
            String value = values.has("key") ? values.get("key").getAsString() : "K";
            String translationKey = value.startsWith("key.keyboard.")
                    ? value : "key.keyboard." + value.toLowerCase(Locale.ROOT);
            InputUtil.Key key = InputUtil.fromTranslationKey(translationKey);
            if (key.getCategory() == InputUtil.Type.KEYSYM && key.getCode() != GLFW.GLFW_KEY_UNKNOWN) {
                return key;
            }
        } catch (RuntimeException exception) {
            CrossSaveClient.LOGGER.warn("快捷键配置无效，使用默认 K 键", exception);
        }
        return InputUtil.Type.KEYSYM.createFromCode(GLFW.GLFW_KEY_K);
    }

    public void acceptDisclaimer() throws IOException {
        JsonObject updated = values.deepCopy();
        updated.addProperty("disclaimerAccepted", true);
        if (!updated.has("key")) {
            updated.addProperty("key", "K");
        }
        if (!updated.has("noiseEnabled")) {
            updated.addProperty("noiseEnabled", true);
        }
        if (!updated.has("noiseManualOverride")) {
            updated.addProperty("noiseManualOverride", false);
        }
        if (!updated.has("soundEnabled")) {
            updated.addProperty("soundEnabled", true);
        }
        write(updated);
        disclaimerAccepted = true;
    }

    private void write(JsonObject updated) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "crosssave-", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(updated), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            // 完整提交后才更新内存，保留配置中其他字段，不保留备份文件。
            values = updated;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
