package cn.crosssave;

import net.minecraft.item.ItemStack;

import java.util.List;

public final class InventoryHandoff {
    private InventoryHandoff() {
    }

    public static void removeSourceItems(InventorySnapshot snapshot, List<ItemStack> main,
                                         List<ItemStack> armor, List<ItemStack> offhand) {
        // 先检查全部槽位，任何一栏发生变化都不清除任何物品，避免部分扣除或移走未序列化的物品。
        verify(snapshot.main(), main);
        verify(snapshot.armor(), armor);
        verify(snapshot.offhand(), offhand);
        clear(main);
        clear(armor);
        clear(offhand);
    }

    private static void verify(List<ItemStack> saved, List<ItemStack> current) {
        if (saved.size() != current.size()) {
            throw new IllegalStateException("转移前物品栏槽位数量发生变化");
        }
        for (int slot = 0; slot < saved.size(); slot++) {
            if (!ItemStack.areEqual(saved.get(slot), current.get(slot))) {
                throw new IllegalStateException("转移前物品发生变化，停止移出物品");
            }
        }
    }

    private static void clear(List<ItemStack> slots) {
        // 快照已独立深拷贝，移出时先替换源槽位，再清除原对象的完整 tag 和数量，避免残留引用保有潜影箱。
        for (int slot = 0; slot < slots.size(); slot++) {
            ItemStack original = slots.get(slot);
            slots.set(slot, ItemStack.EMPTY);
            if (!original.isEmpty()) {
                original.setNbt(null);
                original.setCount(0);
            }
        }
    }
}
