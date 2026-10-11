package cn.crosssave;

import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class NestedInventorySafety {
    private NestedInventorySafety() { }

    public static NbtCompound fullNbt(ItemStack stack) {
        // writeNbt 会写入完整物品及 tag；必须再 copy 整棵树，不能只复制物品 ID 或最外层字段。
        return stack.writeNbt(new NbtCompound()).copy();
    }

    public static ItemStack copyFullNbt(ItemStack original) {
        if (original.isEmpty()) {
            return ItemStack.EMPTY;
        }
        NbtCompound saved = fullNbt(original);
        ItemStack copy = ItemStack.fromNbt(saved.copy());
        if (!saved.equals(fullNbt(copy))) {
            // 不允许带着已经截断或被物品模组改写的嵌套数据继续移走源物品。
            throw new IllegalStateException("完整物品 NBT 往返校验失败，停止迁移");
        }
        return copy;
    }

    public static boolean hasNestedInventory(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof BlockItem item && item.getBlock() instanceof ShulkerBoxBlock) {
            return true;
        }
        NbtCompound tag = stack.getNbt();
        if (tag == null) {
            return false;
        }
        // 迭代检查任意深度的 Items 列表，也支持带有自定义包裹结构的容器物品。
        ArrayDeque<NbtElement> pending = new ArrayDeque<>();
        pending.add(tag);
        while (!pending.isEmpty()) {
            NbtElement element = pending.removeLast();
            if (element instanceof NbtCompound compound) {
                if (compound.contains("Items", NbtElement.LIST_TYPE)) {
                    return true;
                }
                for (String key : compound.getKeys()) {
                    NbtElement child = compound.get(key);
                    if (child instanceof NbtCompound || child instanceof NbtList) {
                        pending.add(child);
                    }
                }
            } else if (element instanceof NbtList list) {
                for (NbtElement child : list) {
                    if (child instanceof NbtCompound || child instanceof NbtList) {
                        pending.add(child);
                    }
                }
            }
        }
        return false;
    }

    public static boolean matches(InventorySnapshot incoming, InventorySnapshot before,
                                  List<ItemStack> main, List<ItemStack> armor, List<ItemStack> offhand,
                                  List<ItemStack> createdDrops) {
        Map<NbtCompound, Long> expected = new HashMap<>();
        collect(expected, incoming.main());
        collect(expected, incoming.armor());
        collect(expected, incoming.offhand());
        collect(expected, before.main());
        collect(expected, before.armor());
        collect(expected, before.offhand());
        Map<NbtCompound, Long> actual = new HashMap<>();
        collect(actual, main);
        collect(actual, armor);
        collect(actual, offhand);
        collect(actual, createdDrops);
        // 原装备掉落也是有效去向；只汇总数量，不忽略内部物品的 Count、Slot 或任何其他嵌套字段。
        return expected.equals(actual);
    }

    private static void collect(Map<NbtCompound, Long> result, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (hasNestedInventory(stack)) {
                NbtCompound key = fullNbt(stack);
                key.remove("Count");
                result.merge(key, (long) stack.getCount(), Long::sum);
            }
        }
    }
}
