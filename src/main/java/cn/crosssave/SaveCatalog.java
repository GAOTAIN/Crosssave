package cn.crosssave;

import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SaveCatalog {
    private SaveCatalog() {
    }

    public record Entry(String folderName, String displayName) {
    }

    public static String displayName(Path saveDirectory) throws IOException {
        String name = NbtIo.readCompressed(saveDirectory.resolve("level.dat").toFile())
                .getCompound("Data").getString("LevelName");
        return name.isBlank() ? saveDirectory.getFileName().toString() : name;
    }

    public static List<Entry> list(Path savesDirectory, String currentFolder) throws IOException {
        List<Entry> entries = new ArrayList<>();
        if (!Files.isDirectory(savesDirectory)) {
            return List.of();
        }
        try (var directories = Files.list(savesDirectory)) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                String folder = directory.getFileName().toString();
                // 按文件夹名排除当前存档，显示名相同的其他存档仍可选择。
                if (folder.equals(currentFolder) || !Files.isRegularFile(directory.resolve("level.dat"))) {
                    continue;
                }
                try {
                    entries.add(new Entry(folder, displayName(directory)));
                } catch (IOException | RuntimeException exception) {
                    CrossSaveClient.LOGGER.warn("跳过无法读取的存档：{}", directory, exception);
                }
            }
        }
        entries.sort(Comparator.comparing(Entry::displayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Entry::folderName));
        return List.copyOf(entries);
    }
}
