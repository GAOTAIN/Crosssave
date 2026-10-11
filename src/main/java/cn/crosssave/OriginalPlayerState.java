package cn.crosssave;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;

public record OriginalPlayerState(InventorySnapshot inventory, int selectedSlot,
                                  float experienceProgress, int totalExperience) {
    public static OriginalPlayerState capture(ServerPlayerEntity player, InventorySnapshot snapshot) {
        return new OriginalPlayerState(snapshot, player.getInventory().selectedSlot,
                player.experienceProgress, player.totalExperience);
    }

    public static void restoreSlots(List<ItemStack> saved, List<ItemStack> current) {
        if (saved.size() != current.size()) {
            throw new IllegalArgumentException("回滚物品栏槽位数量不匹配");
        }
        for (int i = 0; i < saved.size(); i++) {
            current.set(i, NestedInventorySafety.copyFullNbt(saved.get(i)));
        }
    }

    public void restore(ServerPlayerEntity player) {
        if (!inventory.playerUuid().equals(player.getUuid())) {
            throw new IllegalArgumentException("回滚玩家 UUID 不匹配");
        }
        PlayerInventory current = player.getInventory();
        // 原存档的转移物品已被移出，回滚按原槽位恢复，不能执行目标存档的合并规则。
        // 只恢复随身物品和经验，不读取末影箱，不重建骑乘实体，也不产生装备掉落。
        restoreSlots(inventory.main(), current.main);
        restoreSlots(inventory.armor(), current.armor);
        restoreSlots(inventory.offhand(), current.offHand);
        current.selectedSlot = selectedSlot;
        player.setExperienceLevel(inventory.experienceLevel());
        player.experienceProgress = experienceProgress;
        player.totalExperience = totalExperience;
        current.markDirty();
        player.currentScreenHandler.sendContentUpdates();
        SafePositionFinder.ensureSafe(player);
    }
}
