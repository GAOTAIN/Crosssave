package cn.crosssave;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.text.Text;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NestedInventorySafetyTest {
    @TempDir Path gameDirectory;

    @BeforeAll
    static void initializeMinecraft() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    private static List<ItemStack> slots(int count) {
        return new ArrayList<>(Collections.nCopies(count, ItemStack.EMPTY));
    }

    private static InventorySnapshot snapshot(List<ItemStack> main, List<ItemStack> offhand) {
        return new InventorySnapshot(UUID.randomUUID(), UUID.randomUUID(), main, slots(4), offhand, 12);
    }

    private static ItemStack packedBox() {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.setCustomName(Text.literal("嵌套安全测试箱"));
        NbtList items = new NbtList();
        for (int slot = 0; slot < 27; slot++) {
            ItemStack inner = new ItemStack(Items.DIAMOND, 64);
            NbtCompound entry = inner.writeNbt(new NbtCompound());
            entry.putByte("Slot", (byte) slot);
            items.add(entry);
        }
        // 再包一层潜影箱并加入不同类型数组，验证任意嵌套数据不会被仅取 ID 的复制截断。
        ItemStack nested = new ItemStack(Items.RED_SHULKER_BOX);
        NbtList deepest = new NbtList();
        deepest.add(new ItemStack(Items.ENCHANTED_BOOK).writeNbt(new NbtCompound()));
        nested.getOrCreateSubNbt("BlockEntityTag").put("Items", deepest);
        NbtCompound nestedEntry = nested.writeNbt(new NbtCompound());
        nestedEntry.putByte("Slot", (byte) 0);
        items.set(0, nestedEntry);
        NbtCompound block = box.getOrCreateSubNbt("BlockEntityTag");
        block.put("Items", items);
        block.putByteArray("Bytes", new byte[] { 1, 2, 3 });
        block.putIntArray("Ints", new int[] { 7, 8, 9 });
        block.putLongArray("Longs", new long[] { 11, 12, 13 });
        return box;
    }

    @Test
    void compressedMovePreservesTheEntireTreeAndClearsTheLiveSourceObject() throws IOException {
        ItemStack liveBox = packedBox();
        NbtCompound expected = NestedInventorySafety.fullNbt(liveBox);
        List<ItemStack> main = slots(36);
        main.set(0, liveBox);
        InventorySnapshot original = snapshot(main, slots(1));
        TransferStore store = new TransferStore(gameDirectory);
        TransferStore.Pending pending = store.saveAndRemove("目标", original, main, slots(4), slots(1));
        assertTrue(main.stream().allMatch(ItemStack::isEmpty));
        assertEquals(0, liveBox.getCount());
        assertNull(liveBox.getNbt());
        assertEquals(expected, NestedInventorySafety.fullNbt(original.main().get(0)));
        assertEquals(expected, NestedInventorySafety.fullNbt(store.readSnapshot(pending).main().get(0)));
        NbtCompound persisted = NbtIo.readCompressed(gameDirectory.resolve("crosssave")
                .resolve(original.playerUuid() + ".nbt").toFile());
        assertEquals(expected, persisted.getList("Main", 10).getCompound(0));
    }

    @Test
    void nestedOverflowAndOriginalOffhandDropAreBothIncludedInValidation() {
        List<ItemStack> incoming = slots(36);
        incoming.set(0, packedBox());
        List<ItemStack> target = slots(36);
        Collections.fill(target, new ItemStack(Items.STONE, 64));
        List<ItemStack> offhand = new ArrayList<>(List.of(packedBox()));
        InventorySnapshot before = snapshot(target, offhand);
        InventorySnapshot saved = snapshot(incoming, slots(1));
        List<ItemStack> drops = new ArrayList<>();
        List<ItemStack> armor = slots(4);
        InventoryMerger.merge(saved, target, armor, offhand, ExperiencePoints.fromTotalPoints(0), drops::add);
        assertEquals(2, drops.size());
        assertTrue(NestedInventorySafety.matches(saved, before, target, armor, offhand, drops));
        for (ItemStack drop : drops) {
            assertEquals(NestedInventorySafety.fullNbt(packedBox()), NestedInventorySafety.fullNbt(drop));
        }
    }

    @Test
    void missingNestedContentsAreDetectedAndAFullSnapshotReplayRestoresThem() {
        List<ItemStack> incoming = slots(36);
        incoming.set(0, packedBox());
        InventorySnapshot saved = snapshot(incoming, slots(1));
        List<ItemStack> target = slots(36);
        List<ItemStack> armor = slots(4);
        List<ItemStack> offhand = slots(1);
        InventorySnapshot before = snapshot(target, offhand);
        List<ItemStack> drops = new ArrayList<>();
        InventoryMerger.merge(saved, target, armor, offhand, ExperiencePoints.fromTotalPoints(0), drops::add);
        target.get(0).getSubNbt("BlockEntityTag").remove("Items");
        assertFalse(NestedInventorySafety.matches(saved, before, target, armor, offhand, drops));
        OriginalPlayerState.restoreSlots(before.main(), target);
        InventoryMerger.merge(saved, target, armor, offhand, ExperiencePoints.fromTotalPoints(0), drops::add);
        assertTrue(NestedInventorySafety.matches(saved, before, target, armor, offhand, drops));
        assertEquals(NestedInventorySafety.fullNbt(saved.main().get(0)), NestedInventorySafety.fullNbt(target.get(0)));
    }

    @Test
    void changingAnInnerCountOrAddingAnExtraBoxCannotPassValidation() {
        List<ItemStack> incoming = slots(36);
        incoming.set(0, packedBox());
        InventorySnapshot saved = snapshot(incoming, slots(1));
        List<ItemStack> target = slots(36);
        List<ItemStack> armor = slots(4);
        List<ItemStack> offhand = slots(1);
        InventorySnapshot before = snapshot(target, offhand);
        List<ItemStack> drops = new ArrayList<>();
        InventoryMerger.merge(saved, target, armor, offhand, ExperiencePoints.fromTotalPoints(0), drops::add);
        target.get(0).getSubNbt("BlockEntityTag").getList("Items", 10).getCompound(1).putByte("Count", (byte) 1);
        assertFalse(NestedInventorySafety.matches(saved, before, target, armor, offhand, drops));
        OriginalPlayerState.restoreSlots(before.main(), target);
        InventoryMerger.merge(saved, target, armor, offhand, ExperiencePoints.fromTotalPoints(0), drops::add);
        target.set(1, packedBox());
        assertFalse(NestedInventorySafety.matches(saved, before, target, armor, offhand, drops));
    }

    @Test
    void cancellingAndRestoringRepeatedlyLeavesOnlyOneFullShulkerBox() throws IOException {
        List<ItemStack> main = slots(36);
        main.set(0, packedBox());
        InventorySnapshot original = snapshot(main, slots(1));
        TransferStore store = new TransferStore(gameDirectory);
        TransferStore.Pending pending = store.saveAndRemove("目标", original, main, slots(4), slots(1));
        store.cancel(pending);
        OriginalPlayerState.restoreSlots(original.main(), main);
        OriginalPlayerState.restoreSlots(original.main(), main);
        assertNull(store.readPending());
        assertEquals(1, main.stream().filter(NestedInventorySafety::hasNestedInventory).count());
        assertEquals(NestedInventorySafety.fullNbt(original.main().get(0)), NestedInventorySafety.fullNbt(main.get(0)));
    }
}
