package cn.crosssave;

import net.minecraft.nbt.NbtIo;
import net.minecraft.util.WorldSavePath;
import net.minecraft.server.integrated.IntegratedServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class WorldFolderResolver {
    private WorldFolderResolver() {
    }

    public static String resolve(Path savesDirectory, IntegratedServer server) throws IOException {
        String displayName = server.getSaveProperties().getLevelName();
        List<Path> matches = new ArrayList<>();
        // 按显示名遍历并读取各存档的 level.dat，取得匹配目录的文件夹名。
        try (var directories = Files.list(savesDirectory)) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                Path levelDat = directory.resolve("level.dat");
                if (!Files.isRegularFile(levelDat)) {
                    continue;
                }
                try {
                    String name = NbtIo.readCompressed(levelDat.toFile()).getCompound("Data").getString("LevelName");
                    if (displayName.equals(name)) {
                        matches.add(directory);
                    }
                } catch (IOException exception) {
                    CrossSaveClient.LOGGER.warn("无法读取存档的 level.dat：{}", directory, exception);
                }
            }
        }
        if (matches.size() == 1) {
            return matches.get(0).getFileName().toString();
        }
        // 多个存档显示名相同时，通过公开存档路径消除歧义，避免恢复到错误存档。
        Path currentRoot = server.getSavePath(WorldSavePath.ROOT).toRealPath();
        for (Path match : matches) {
            if (match.toRealPath().equals(currentRoot)) {
                return match.getFileName().toString();
            }
        }
        throw new IOException("未能从 saves/ 中识别当前存档文件夹");
    }
}
