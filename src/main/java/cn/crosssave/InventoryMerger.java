package cn.crosssave;

import net.minecraft.item.ItemStack;

import java.util.List;
import java.util.function.Consumer;

public final class InventoryMerger {
    private InventoryMerger() {
    }

    public static int merge(InventorySnapshot snapshot, List<ItemStack> main, List<ItemStack> armor,
                            List<ItemStack> offhand, int currentLevel, Consumer<ItemStack> drop) {
        // 不合并到已有堆叠，不覆盖目标主背包；仅使用空位，溢出部分掉落。
        for (ItemStack saved : snapshot.main()) {
            ItemStack remaining = NestedInventorySafety.copyFullNbt(saved);
            for (int slot = 0; slot < main.size() && !remaining.isEmpty(); slot++) {
                if (main.get(slot).isEmpty()) {
                    main.set(slot, remaining.split(Math.min(remaining.getCount(), remaining.getMaxCount())));
                }
            }
            if (!remaining.isEmpty()) {
                drop.accept(NestedInventorySafety.copyFullNbt(remaining));
            }
        }
        dropEquipment(armor, drop);
        dropEquipment(offhand, drop);
        replaceEquipment(armor, snapshot.armor());
        replaceEquipment(offhand, snapshot.offhand());
        return Math.max(currentLevel, snapshot.experienceLevel());
    }

    private static void dropEquipment(List<ItemStack> current, Consumer<ItemStack> drop) {
        // 所有原盔甲和副手副本均先掉落，再开始替换装备槽。
        for (ItemStack original : current) {
            if (!original.isEmpty()) {
                drop.accept(NestedInventorySafety.copyFullNbt(original));
            }
        }
    }

    private static void replaceEquipment(List<ItemStack> current, List<ItemStack> saved) {
        // 文件中的空槽也会清空对应装备槽。
        for (int slot = 0; slot < current.size(); slot++) {
            current.set(slot, NestedInventorySafety.copyFullNbt(saved.get(slot)));
        }
    }
}
