package cn.crosssave;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.text.Text;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CoreLogicTest {
    @TempDir
    Path gameDirectory;

    @BeforeAll
    static void initializeMinecraft() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    private static List<ItemStack> emptySlots(int count) {
        return new ArrayList<>(Collections.nCopies(count, ItemStack.EMPTY));
    }

    private static InventorySnapshot snapshot(List<ItemStack> main, List<ItemStack> armor,
                                              List<ItemStack> offhand, int level) {
        return new InventorySnapshot(UUID.randomUUID(), UUID.randomUUID(), main, armor, offhand, level);
    }

    @Test
    void compressedNbtPreservesAllSlotsAndItemTags() throws IOException {
        List<ItemStack> main = emptySlots(36);
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.setDamage(17);
        sword.setCustomName(Text.literal("跨存档测试剑"));
        NbtCompound custom = new NbtCompound();
        custom.putString("CustomMarker", "保留自定义物品数据");
        sword.getOrCreateNbt().put("CrosssaveTest", custom);
        main.set(0, sword);
        main.set(8, new ItemStack(Items.TORCH, 32));
        main.set(9, new ItemStack(Items.IRON_INGOT, 12));
        main.set(35, new ItemStack(Items.DIAMOND, 64));
        List<ItemStack> armor = emptySlots(4);
        armor.set(0, new ItemStack(Items.IRON_BOOTS));
        armor.set(3, new ItemStack(Items.DIAMOND_HELMET));
        InventorySnapshot saved = snapshot(main, armor, List.of(new ItemStack(Items.SHIELD)), 42);
        Path file = gameDirectory.resolve("test.nbt");
        NbtIo.writeCompressed(saved.toNbt(), file.toFile());
        InventorySnapshot restored = InventorySnapshot.fromNbt(NbtIo.readCompressed(file.toFile()));
        assertEquals(saved.playerUuid(), restored.playerUuid());
        assertEquals(saved.transferId(), restored.transferId());
        assertEquals(42, restored.experienceLevel());
        assertEquals(36, restored.main().size());
        for (int i = 0; i < 36; i++) {
            assertTrue(ItemStack.areEqual(main.get(i), restored.main().get(i)), "槽位 " + i);
        }
        for (int i = 0; i < 4; i++) {
            assertTrue(ItemStack.areEqual(armor.get(i), restored.armor().get(i)));
        }
        assertTrue(ItemStack.areEqual(saved.offhand().get(0), restored.offhand().get(0)));
        assertFalse(saved.toNbt().contains("EnderItems"));
    }

    @Test
    void mergeOnlyUsesEmptySlotsAndDropsOverflowWithoutChangingExistingStacks() {
        List<ItemStack> current = emptySlots(36);
        for (int i = 0; i < 36; i++) {
            current.set(i, new ItemStack(Items.STONE, 63));
        }
        current.set(5, ItemStack.EMPTY);
        List<ItemStack> main = emptySlots(36);
        main.set(0, new ItemStack(Items.STONE, 12));
        main.set(35, new ItemStack(Items.DIAMOND, 7));
        List<ItemStack> drops = new ArrayList<>();
        InventorySnapshot saved = snapshot(main, emptySlots(4), emptySlots(1), 30);
        int level = InventoryMerger.merge(saved, current, emptySlots(4), emptySlots(1), 8, drops::add);
        assertEquals(30, level);
        assertEquals(12, current.get(5).getCount());
        assertEquals(63, current.get(0).getCount());
        assertEquals(1, drops.size());
        assertTrue(drops.get(0).isOf(Items.DIAMOND));
        assertEquals(7, drops.get(0).getCount());
        assertEquals(12, saved.main().get(0).getCount());
    }

    @Test
    void equipmentIsCopiedToDropsAndReplacedIncludingEmptySavedSlots() {
        List<ItemStack> armor = emptySlots(4);
        ItemStack oldBoots = new ItemStack(Items.IRON_BOOTS);
        oldBoots.setDamage(8);
        armor.set(0, oldBoots);
        armor.set(3, new ItemStack(Items.IRON_HELMET));
        List<ItemStack> offhand = new ArrayList<>(List.of(new ItemStack(Items.SHIELD)));
        List<ItemStack> savedArmor = emptySlots(4);
        savedArmor.set(3, new ItemStack(Items.DIAMOND_HELMET));
        List<ItemStack> drops = new ArrayList<>();
        int level = InventoryMerger.merge(snapshot(emptySlots(36), savedArmor, emptySlots(1), 2),
                emptySlots(36), armor, offhand, 50, stack -> {
                    assertSame(oldBoots, armor.get(0));
                    assertTrue(offhand.get(0).isOf(Items.SHIELD));
                    drops.add(stack);
                });
        assertEquals(50, level);
        assertEquals(3, drops.size());
        assertTrue(ItemStack.areEqual(oldBoots, drops.get(0)));
        assertNotSame(oldBoots, drops.get(0));
        assertTrue(armor.get(0).isEmpty());
        assertTrue(armor.get(3).isOf(Items.DIAMOND_HELMET));
        assertTrue(offhand.get(0).isEmpty());
    }

    @Test
    void pendingRecordsTargetUuidAndDeletesBothFilesAfterRecovery() throws IOException {
        TransferStore store = new TransferStore(gameDirectory);
        InventorySnapshot saved = snapshot(emptySlots(36), emptySlots(4), emptySlots(1), 8);
        store.save("目标 存档", saved);
        TransferStore.Pending pending = store.readPending();
        assertNotNull(pending);
        assertTrue(pending.matches("目标 存档", saved.playerUuid()));
        assertFalse(pending.matches("其他存档", saved.playerUuid()));
        assertFalse(pending.matches("目标 存档", UUID.randomUUID()));
        assertTrue(Files.exists(gameDirectory.resolve("crosssave").resolve(saved.playerUuid() + ".nbt")));
        assertEquals(saved.transferId(), store.readSnapshot(pending).transferId());
        store.delete(pending);
        assertNull(store.readPending());
        assertFalse(Files.exists(gameDirectory.resolve("crosssave").resolve(saved.playerUuid() + ".nbt")));
    }

    @Test
    void corruptedOrMismatchedNbtIsRejectedWithoutDeletion() throws IOException {
        TransferStore store = new TransferStore(gameDirectory);
        InventorySnapshot saved = snapshot(emptySlots(36), emptySlots(4), emptySlots(1), 8);
        store.save("目标存档", saved);
        TransferStore.Pending pending = store.readPending();
        Path nbtFile = gameDirectory.resolve("crosssave").resolve(saved.playerUuid() + ".nbt");
        NbtCompound nbt = saved.toNbt();
        nbt.putUuid("TransferId", UUID.randomUUID());
        NbtIo.writeCompressed(nbt, nbtFile.toFile());
        assertThrows(IOException.class, () -> store.readSnapshot(pending));
        assertNotNull(store.readPending());
        assertTrue(Files.exists(nbtFile));
        nbt = saved.toNbt();
        nbt.remove("Main");
        NbtCompound invalid = nbt;
        assertThrows(IOException.class, () -> InventorySnapshot.fromNbt(invalid));
    }

    @Test
    void folderNamesCannotEscapeSavesDirectory() {
        assertTrue(TransferStore.isFolderName("世界 1"));
        assertFalse(TransferStore.isFolderName("../world"));
        assertFalse(TransferStore.isFolderName(".."));
        // 根据临时测试目录生成绝对路径，避免把本机盘符写入测试代码。
        assertFalse(TransferStore.isFolderName(gameDirectory.toAbsolutePath().resolve("world").toString()));
        assertFalse(TransferStore.isFolderName("world:invalid"));
        assertFalse(TransferStore.isFolderName("world/child"));
    }

    @Test
    void rollbackReplacesOriginalSlotsWithoutDuplicatingStacks() {
        List<ItemStack> saved = emptySlots(36);
        saved.set(0, new ItemStack(Items.DIAMOND, 7));
        saved.set(35, new ItemStack(Items.TORCH, 32));
        List<ItemStack> current = emptySlots(36);
        current.set(0, new ItemStack(Items.DIAMOND, 7));
        current.set(1, new ItemStack(Items.STONE, 64));
        OriginalPlayerState.restoreSlots(saved, current);
        assertEquals(7, current.get(0).getCount());
        assertTrue(current.get(1).isEmpty());
        assertEquals(32, current.get(35).getCount());
        assertNotSame(saved.get(0), current.get(0));
        OriginalPlayerState.restoreSlots(saved, current);
        assertEquals(7, current.get(0).getCount());
        assertThrows(IllegalArgumentException.class, () -> OriginalPlayerState.restoreSlots(saved, emptySlots(4)));
    }

    @Test
    void automaticHandoffMovesItemsInsteadOfCopyingThemBetweenWorlds() throws IOException {
        List<ItemStack> sourceMain = emptySlots(36);
        sourceMain.set(0, new ItemStack(Items.DIAMOND, 7));
        sourceMain.set(35, new ItemStack(Items.TORCH, 32));
        List<ItemStack> sourceArmor = emptySlots(4);
        sourceArmor.set(3, new ItemStack(Items.DIAMOND_HELMET));
        List<ItemStack> sourceOffhand = new ArrayList<>(List.of(new ItemStack(Items.SHIELD)));
        InventorySnapshot original = snapshot(sourceMain, sourceArmor, sourceOffhand, 12);
        TransferStore store = new TransferStore(gameDirectory);
        TransferStore.Pending pending = store.saveAndRemove("目标存档", original, sourceMain, sourceArmor, sourceOffhand);
        InventorySnapshot persisted = store.readSnapshot(pending);
        assertTrue(sourceMain.stream().allMatch(ItemStack::isEmpty));
        assertTrue(sourceArmor.stream().allMatch(ItemStack::isEmpty));
        assertTrue(sourceOffhand.stream().allMatch(ItemStack::isEmpty));
        assertEquals(36, sourceMain.size());
        assertEquals(7, persisted.main().get(0).getCount());

        List<ItemStack> targetMain = emptySlots(36);
        targetMain.set(0, new ItemStack(Items.STONE, 64));
        List<ItemStack> targetArmor = emptySlots(4);
        List<ItemStack> targetOffhand = emptySlots(1);
        List<ItemStack> drops = new ArrayList<>();
        InventoryMerger.merge(persisted, targetMain, targetArmor, targetOffhand, 3, drops::add);
        assertEquals(64, targetMain.get(0).getCount());
        assertEquals(7, targetMain.stream().filter(stack -> stack.isOf(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum());
        assertTrue(targetArmor.get(3).isOf(Items.DIAMOND_HELMET));
        assertTrue(targetOffhand.get(0).isOf(Items.SHIELD));
        assertTrue(drops.isEmpty());
        store.delete(pending);
        assertNull(store.readPending());
    }

    @Test
    void changedSourceInventoryIsNotPartiallyCleared() throws IOException {
        List<ItemStack> main = emptySlots(36);
        main.set(0, new ItemStack(Items.DIAMOND, 7));
        List<ItemStack> armor = emptySlots(4);
        armor.set(3, new ItemStack(Items.DIAMOND_HELMET));
        InventorySnapshot saved = InventorySnapshot.fromNbt(snapshot(main, armor, emptySlots(1), 12).toNbt());
        armor.get(3).setDamage(1);
        TransferStore store = new TransferStore(gameDirectory);
        assertThrows(IllegalStateException.class,
                () -> store.saveAndRemove("目标存档", saved, main, armor, emptySlots(1)));
        assertNull(store.readPending());
        assertEquals(7, main.get(0).getCount());
        assertTrue(armor.get(3).isOf(Items.DIAMOND_HELMET));
        assertEquals(1, armor.get(3).getDamage());
    }

    @Test
    void rollbackRestoresTheSourceAndCancelsTheFutureImport() throws IOException {
        List<ItemStack> main = emptySlots(36);
        main.set(9, new ItemStack(Items.DIAMOND, 7));
        List<ItemStack> armor = emptySlots(4);
        armor.set(0, new ItemStack(Items.IRON_BOOTS));
        List<ItemStack> offhand = new ArrayList<>(List.of(new ItemStack(Items.SHIELD)));
        TransferStore store = new TransferStore(gameDirectory);
        store.save("目标存档", snapshot(main, armor, offhand, 12));
        TransferStore.Pending pending = store.readPending();
        InventorySnapshot saved = store.readSnapshot(pending);
        InventoryHandoff.removeSourceItems(saved, main, armor, offhand);
        store.cancel(pending);
        OriginalPlayerState.restoreSlots(saved.main(), main);
        OriginalPlayerState.restoreSlots(saved.armor(), armor);
        OriginalPlayerState.restoreSlots(saved.offhand(), offhand);
        assertEquals(7, main.get(9).getCount());
        assertTrue(armor.get(0).isOf(Items.IRON_BOOTS));
        assertTrue(offhand.get(0).isOf(Items.SHIELD));
        assertNull(new TransferStore(gameDirectory).readPending());
        assertFalse(Files.exists(gameDirectory.resolve("crosssave").resolve(saved.playerUuid() + ".nbt")));
    }

    @Test
    void cancelledTransferCannotLoadAgainEvenIfFileCleanupFails() throws IOException {
        TransferStore store = new TransferStore(gameDirectory);
        InventorySnapshot saved = snapshot(emptySlots(36), emptySlots(4), emptySlots(1), 8);
        store.save("目标存档", saved);
        TransferStore.Pending pending = store.readPending();
        Path nbtFile = gameDirectory.resolve("crosssave").resolve(saved.playerUuid() + ".nbt");
        Files.delete(nbtFile);
        Files.createDirectory(nbtFile);
        Files.writeString(nbtFile.resolve("阻止删除"), "验证已取消记录不会再次恢复");
        store.cancel(pending);
        assertTrue(Files.exists(gameDirectory.resolve("crosssave").resolve("pending.json")));
        assertNull(new TransferStore(gameDirectory).readPending());
    }

    @Test
    void cancellingAStaleTransferDoesNotDeleteANewerBatch() throws IOException {
        TransferStore store = new TransferStore(gameDirectory);
        InventorySnapshot saved = snapshot(emptySlots(36), emptySlots(4), emptySlots(1), 8);
        store.save("目标存档", saved);
        TransferStore.Pending current = store.readPending();
        TransferStore.Pending stale = new TransferStore.Pending(current.targetFolder(), current.playerUuid(), UUID.randomUUID());
        assertThrows(IOException.class, () -> store.cancel(stale));
        assertEquals(current, store.readPending());
        assertEquals(saved.transferId(), store.readSnapshot(current).transferId());
    }

    @Test
    void pendingItemsCannotBeOverwrittenByASecondSave() throws IOException {
        TransferStore store = new TransferStore(gameDirectory);
        List<ItemStack> first = emptySlots(36);
        first.set(0, new ItemStack(Items.DIAMOND, 7));
        TransferStore.Pending pending = store.saveAndRemove("世界乙", snapshot(first, emptySlots(4), emptySlots(1), 12),
                first, emptySlots(4), emptySlots(1));
        List<ItemStack> second = emptySlots(36);
        second.set(0, new ItemStack(Items.TORCH, 32));
        assertThrows(TransferStore.PendingTransferException.class,
                () -> store.saveAndRemove("世界丙", snapshot(second, emptySlots(4), emptySlots(1), 12),
                        second, emptySlots(4), emptySlots(1)));
        assertTrue(first.stream().allMatch(ItemStack::isEmpty));
        assertEquals(32, second.get(0).getCount());
        assertEquals(pending, store.readPending());
        assertEquals(7, store.readSnapshot(pending).main().get(0).getCount());
    }

    @Test
    void consumedTransferCannotLoadAgainWhenCleanupFails() throws IOException {
        TransferStore store = new TransferStore(gameDirectory);
        List<ItemStack> source = emptySlots(36);
        source.set(0, new ItemStack(Items.DIAMOND, 7));
        TransferStore.Pending pending = store.saveAndRemove("目标存档", snapshot(source, emptySlots(4), emptySlots(1), 12),
                source, emptySlots(4), emptySlots(1));
        InventorySnapshot persisted = store.readSnapshot(pending);
        List<ItemStack> target = emptySlots(36);
        InventoryMerger.merge(persisted, target, emptySlots(4), emptySlots(1), 0, stack -> fail("不应掉落"));
        Path nbtFile = gameDirectory.resolve("crosssave").resolve(pending.playerUuid() + ".nbt");
        Files.delete(nbtFile);
        Files.createDirectory(nbtFile);
        Files.writeString(nbtFile.resolve("阻止删除"), "验证提交后清理失败不撤销物品");
        assertDoesNotThrow(() -> store.delete(pending));
        assertEquals(7, target.get(0).getCount());
        assertTrue(source.stream().allMatch(ItemStack::isEmpty));
        assertNull(new TransferStore(gameDirectory).readPending());
    }

    @Test
    void failedCompletionCommitKeepsTheRecoverableNbt() throws IOException {
        TransferStore store = new TransferStore(gameDirectory);
        InventorySnapshot saved = snapshot(emptySlots(36), emptySlots(4), emptySlots(1), 12);
        store.save("目标存档", saved);
        TransferStore.Pending pending = store.readPending();
        Path metadata = gameDirectory.resolve("crosssave").resolve("pending.json");
        Files.delete(metadata);
        Files.createDirectory(metadata);
        Files.writeString(metadata.resolve("阻止写入"), "验证提交失败不删除 NBT");
        assertThrows(IOException.class, () -> store.delete(pending));
        assertEquals(saved.transferId(), store.readSnapshot(pending).transferId());
    }

    @Test
    void failedNbtWriteDoesNotRemoveSourceItems() throws IOException {
        Files.writeString(gameDirectory.resolve("crosssave"), "阻止创建数据目录");
        TransferStore store = new TransferStore(gameDirectory);
        List<ItemStack> main = emptySlots(36);
        main.set(0, new ItemStack(Items.DIAMOND, 7));
        assertThrows(IOException.class,
                () -> store.saveAndRemove("目标存档", snapshot(main, emptySlots(4), emptySlots(1), 12),
                        main, emptySlots(4), emptySlots(1)));
        assertEquals(7, main.get(0).getCount());
    }

    @Test
    void requiredIntegerPercentageFormatRendersWithMinecraftText() {
        assertEquals("0%", TransferLoadingScreen.percentageText("%d%%", 0).getString());
        assertEquals("37%", TransferLoadingScreen.percentageText("%d%%", 37).getString());
        assertEquals("100%", TransferLoadingScreen.percentageText("%d%%", 100).getString());
    }
}
