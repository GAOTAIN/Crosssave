package cn.crosssave;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.NbtIo;
import net.minecraft.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.List;

public final class TransferStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path directory;

    public TransferStore(Path gameDirectory) {
        directory = gameDirectory.resolve("crosssave");
    }

    public record Pending(String targetFolder, UUID playerUuid, UUID transferId) {
        public boolean matches(String currentFolder, UUID currentPlayer) {
            return targetFolder.equals(currentFolder) && playerUuid.equals(currentPlayer);
        }
    }

    public static boolean isFolderName(String name) {
        return name != null && !name.isBlank() && !name.equals(".") && !name.equals("..")
                && !name.contains("/") && !name.contains("\\") && !name.contains("\0")
                && !name.contains(":");
    }

    public void save(String targetFolder, InventorySnapshot snapshot) throws IOException {
        if (!isFolderName(targetFolder)) {
            throw new IOException("请输入目标存档文件夹名，不要输入路径");
        }
        Files.createDirectories(directory);
        JsonObject pending = new JsonObject();
        pending.addProperty("targetFolder", targetFolder);
        pending.addProperty("playerUuid", snapshot.playerUuid().toString());
        pending.addProperty("transferId", snapshot.transferId().toString());
        Path nbtTemporary = Files.createTempFile(directory, "snapshot-", ".tmp");
        Path jsonTemporary = Files.createTempFile(directory, "pending-", ".tmp");
        try {
            NbtIo.writeCompressed(snapshot.toNbt(), nbtTemporary.toFile());
            Files.writeString(jsonTemporary, GSON.toJson(pending), StandardCharsets.UTF_8);
            // 临时文件只用于完整写入，不保留备份；先提交 NBT，再提交指向它的记录。
            replace(nbtTemporary, nbtPath(snapshot.playerUuid()));
            replace(jsonTemporary, pendingPath());
        } finally {
            Files.deleteIfExists(nbtTemporary);
            Files.deleteIfExists(jsonTemporary);
        }
    }

    public Pending saveAndRemove(String targetFolder, InventorySnapshot snapshot, List<ItemStack> main,
                                 List<ItemStack> armor, List<ItemStack> offhand)
            throws IOException {
        if (readPending() != null) {
            throw new PendingTransferException();
        }
        save(targetFolder, snapshot);
        Pending expected = new Pending(targetFolder, snapshot.playerUuid(), snapshot.transferId());
        try {
            if (!expected.equals(readPending())) {
                throw new IOException("写入后的待迁移批次不匹配");
            }
            InventorySnapshot persisted = readSnapshot(expected);
            // 所有玩家迁移入口都必须走此方法；序列化和校验完成前绝不移出原存档物品。
            InventoryHandoff.removeSourceItems(persisted, main, armor, offhand);
            return expected;
        } catch (IOException | RuntimeException exception) {
            try {
                // 校验失败没有扣除物品，必须取消已写入的副本，防止以后进入目标再次导入。
                cancel(expected);
            } catch (IOException cancellationFailure) {
                exception.addSuppressed(cancellationFailure);
            }
            throw exception;
        }
    }

    public static final class PendingTransferException extends IOException {
        public PendingTransferException() {
            super("存在未完成迁移，不能覆盖待恢复物品");
        }
    }

    public Pending readPending() throws IOException {
        if (!Files.exists(pendingPath())) {
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(pendingPath(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (String state : new String[] { "cancelled", "consumed" }) {
                var marker = json.get(state);
                if (marker != null && marker.isJsonPrimitive()
                        && marker.getAsJsonPrimitive().isBoolean() && marker.getAsBoolean()) {
                    return null;
                }
            }
            String folder = json.get("targetFolder").getAsString();
            if (!isFolderName(folder)) {
                throw new IllegalArgumentException("目标文件夹名无效");
            }
            return new Pending(folder, UUID.fromString(json.get("playerUuid").getAsString()),
                    UUID.fromString(json.get("transferId").getAsString()));
        } catch (RuntimeException exception) {
            throw new IOException("pending.json 格式无效，未恢复数据", exception);
        }
    }

    public InventorySnapshot readSnapshot(Pending pending) throws IOException {
        InventorySnapshot snapshot = InventorySnapshot.fromNbt(NbtIo.readCompressed(nbtPath(pending.playerUuid()).toFile()));
        if (!snapshot.playerUuid().equals(pending.playerUuid()) || !snapshot.transferId().equals(pending.transferId())) {
            throw new IOException("NBT 与 pending.json 的玩家或保存批次不匹配");
        }
        return snapshot;
    }

    public void delete(Pending pending) throws IOException {
        if (markInactive(pending, "consumed")) {
            cleanupInactive(pending);
        }
    }

    public void cancel(Pending pending) throws IOException {
        if (markInactive(pending, "cancelled")) {
            cleanupInactive(pending);
        }
    }

    private boolean markInactive(Pending expected, String state) throws IOException {
        Pending current = readPending();
        if (current == null) {
            return false;
        }
        if (!expected.equals(current)) {
            throw new IOException("待迁移记录已改变，不能结束其他批次");
        }
        JsonObject inactive = new JsonObject();
        inactive.addProperty("targetFolder", expected.targetFolder());
        inactive.addProperty("playerUuid", expected.playerUuid().toString());
        inactive.addProperty("transferId", expected.transferId().toString());
        inactive.addProperty(state, true);
        Path temporary = Files.createTempFile(directory, "completed-", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(inactive), StandardCharsets.UTF_8);
            // 在删除 NBT 或归还原物品之前，先原子提交已消费或已取消标记，防止跨启动再次导入。
            replace(temporary, pendingPath());
        } finally {
            Files.deleteIfExists(temporary);
        }
        return true;
    }

    private void cleanupInactive(Pending pending) {
        try {
            Files.deleteIfExists(nbtPath(pending.playerUuid()));
            Files.deleteIfExists(pendingPath());
        } catch (IOException exception) {
            // 提交已完成，清理失败只能保留不可再次导入的记录，不能撤销已经成功的目标恢复。
            CrossSaveClient.LOGGER.warn("已结束的迁移文件清理失败，记录不会再次恢复", exception);
        }
    }

    private Path pendingPath() {
        return directory.resolve("pending.json");
    }

    private Path nbtPath(UUID playerUuid) {
        return directory.resolve(playerUuid + ".nbt");
    }

    private static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
