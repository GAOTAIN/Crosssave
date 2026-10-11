package cn.crosssave;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.entity.player.PlayerInventory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record InventorySnapshot(UUID playerUuid, UUID transferId, List<ItemStack> main,
                                List<ItemStack> armor, List<ItemStack> offhand, int experienceLevel) {
    public InventorySnapshot {
        // 防御性深拷贝：源槽位彻底清理时，迁移文件与回滚快照不能共享同一个 NBT 树。
        main = copy(main);
        armor = copy(armor);
        offhand = copy(offhand);
    }

    public static InventorySnapshot capture(UUID playerUuid, PlayerInventory inventory, int level) {
        // 只复制随身三个物品栏，不读取末影箱或任何实体数据。
        return new InventorySnapshot(playerUuid, UUID.randomUUID(), inventory.main,
                inventory.armor, inventory.offHand, level);
    }

    private static List<ItemStack> copy(List<ItemStack> stacks) {
        return stacks.stream().map(NestedInventorySafety::copyFullNbt).toList();
    }

    public NbtCompound toNbt() {
        NbtCompound root = new NbtCompound();
        root.putInt("FormatVersion", 1);
        root.putUuid("PlayerUuid", playerUuid);
        root.putUuid("TransferId", transferId);
        root.put("Main", writeSlots(main));
        root.put("Armor", writeSlots(armor));
        root.put("Offhand", writeSlots(offhand));
        root.putInt("ExperienceLevel", experienceLevel);
        return root;
    }

    private static NbtList writeSlots(List<ItemStack> stacks) {
        NbtList slots = new NbtList();
        // 保存全部槽位，包括空槽，主物品栏顺序为快捷栏后接主背包。
        for (ItemStack stack : stacks) {
            slots.add(NestedInventorySafety.fullNbt(stack));
        }
        return slots;
    }

    public static InventorySnapshot fromNbt(NbtCompound root) throws IOException {
        if (root.getInt("FormatVersion") != 1 || !root.containsUuid("PlayerUuid")
                || !root.containsUuid("TransferId") || !root.contains("ExperienceLevel", NbtElement.INT_TYPE)
                || root.getInt("ExperienceLevel") < 0) {
            throw new IOException("随身数据格式无效");
        }
        return new InventorySnapshot(root.getUuid("PlayerUuid"), root.getUuid("TransferId"),
                readSlots(root, "Main", 36), readSlots(root, "Armor", 4),
                readSlots(root, "Offhand", 1), root.getInt("ExperienceLevel"));
    }

    private static List<ItemStack> readSlots(NbtCompound root, String key, int size) throws IOException {
        NbtList slots = root.getList(key, NbtElement.COMPOUND_TYPE);
        if (slots.size() != size) {
            throw new IOException("物品栏槽位数量无效：" + key);
        }
        List<ItemStack> stacks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            NbtCompound slot = slots.getCompound(i);
            ItemStack stack = ItemStack.fromNbt(slot.copy());
            if (slot.contains("id", NbtElement.STRING_TYPE)
                    && !slot.getString("id").equals("minecraft:air") && stack.isEmpty()) {
                throw new IOException("无法恢复物品，请确认两个存档安装了相同的物品模组");
            }
            stacks.add(stack);
        }
        return stacks;
    }
}
