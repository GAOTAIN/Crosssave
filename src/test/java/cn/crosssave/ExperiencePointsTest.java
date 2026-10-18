package cn.crosssave;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
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

class ExperiencePointsTest {
    @TempDir Path gameDirectory;

    @BeforeAll
    static void initializeMinecraft() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    private static List<ItemStack> slots(int size) {
        return new ArrayList<>(Collections.nCopies(size, ItemStack.EMPTY));
    }

    private static InventorySnapshot snapshot(int level, float progress) {
        return new InventorySnapshot(UUID.randomUUID(), UUID.randomUUID(), slots(36), slots(4), slots(1),
                level, progress);
    }

    @Test
    void knownVanillaLevelThresholdsAndCostsAreExact() {
        int[] levels = {0, 1, 15, 16, 17, 29, 30, 31, 32, 39, 40, 50};
        long[] totals = {0, 7, 315, 352, 394, 1288, 1395, 1507, 1628, 2727, 2920, 5345};
        int[] costs = {7, 9, 37, 42, 47, 107, 112, 121, 130, 193, 202, 292};
        for (int i = 0; i < levels.length; i++) {
            assertEquals(totals[i], ExperiencePoints.pointsAtLevel(levels[i]));
            assertEquals(costs[i], ExperiencePoints.pointsToNextLevel(levels[i]));
            assertEquals(levels[i], ExperiencePoints.fromTotalPoints(totals[i]).level());
        }
    }

    @Test
    void cumulativeInverseMatchesIndependentStepwiseVanillaCosts() {
        long total = 0;
        // 用原版逐级累加验证闭式公式及逆运算，覆盖两处曲线切换和大量等级边界。
        for (int level = 0; level <= 2000; level++) {
            int cost;
            if (level >= 30) {
                cost = 112 + (level - 30) * 9;
            } else if (level >= 15) {
                cost = 37 + (level - 15) * 5;
            } else {
                cost = 7 + level * 2;
            }
            assertEquals(total, ExperiencePoints.pointsAtLevel(level));
            assertEquals(cost, ExperiencePoints.pointsToNextLevel(level));
            assertEquals(level, ExperiencePoints.fromTotalPoints(total).level());
            assertEquals(level, ExperiencePoints.fromTotalPoints(total + cost - 1).level());
            assertEquals(level + 1, ExperiencePoints.fromTotalPoints(total + cost).level());
            for (int within : new int[] {1, cost / 2, cost - 1}) {
                ExperiencePoints.State state = ExperiencePoints.fromTotalPoints(total + within);
                assertEquals(within, state.pointsIntoLevel());
                assertEquals(state, ExperiencePoints.fromLevelProgress(level, state.progress()));
            }
            total += cost;
        }
    }

    @Test
    void twoLevelThirtyPlayersBecomeLevelThirtyNineWithSixtyThreePoints() {
        ExperiencePoints.State result = ExperiencePoints.merge(
                ExperiencePoints.fromLevelProgress(30, 0), ExperiencePoints.fromLevelProgress(30, 0));
        assertEquals(2790, result.totalPoints());
        assertEquals(39, result.level());
        assertEquals(63, result.pointsIntoLevel());
        assertEquals(63.0F / 193.0F, result.progress());
        assertTrue(result.level() < 60);
    }

    @Test
    void bothExperienceBarsAreIncludedAndCanCrossALevelBoundary() {
        ExperiencePoints.State result = ExperiencePoints.merge(
                ExperiencePoints.fromLevelProgress(0, 4.0F / 7.0F),
                ExperiencePoints.fromLevelProgress(0, 5.0F / 7.0F));
        assertEquals(9, result.totalPoints());
        assertEquals(1, result.level());
        assertEquals(2, result.pointsIntoLevel());
        assertEquals(2.0F / 9.0F, result.progress());
    }

    @Test
    void onePointFloatRoundingDoesNotLoseExperience() {
        for (int level : new int[] {0, 15, 16, 29, 30, 31, 50, 1000}) {
            float progress = 1.0F / ExperiencePoints.pointsToNextLevel(level);
            assertEquals(ExperiencePoints.pointsAtLevel(level) + 1,
                    ExperiencePoints.fromLevelProgress(level, progress).totalPoints());
        }
    }

    @Test
    void emptyAndLargeTransfersPreserveTotalPointsWithoutIntegerOverflow() {
        ExperiencePoints.State source = ExperiencePoints.fromTotalPoints(2_000_000_000L);
        ExperiencePoints.State zero = ExperiencePoints.fromTotalPoints(0);
        assertEquals(source, ExperiencePoints.merge(source, zero));
        assertEquals(zero, ExperiencePoints.merge(zero, zero));
        ExperiencePoints.State result = ExperiencePoints.merge(source, source);
        assertEquals(4_000_000_000L, result.totalPoints());
        assertEquals(result, ExperiencePoints.fromLevelProgress(result.level(), result.progress()));
    }

    @Test
    void compressedSnapshotKeepsLevelProgressAndTotalPoints() throws IOException {
        InventorySnapshot original = snapshot(30, 57.0F / 112.0F);
        Path file = gameDirectory.resolve("experience.nbt");
        NbtIo.writeCompressed(original.toNbt(), file.toFile());
        NbtCompound root = NbtIo.readCompressed(file.toFile());
        assertEquals(2, root.getInt("FormatVersion"));
        assertEquals(1452, root.getLong("TotalExperiencePoints"));
        InventorySnapshot loaded = InventorySnapshot.fromNbt(root);
        assertEquals(original.experience(), loaded.experience());
        assertEquals(original.experienceProgress(), loaded.experienceProgress());
    }

    @Test
    void oldLevelOnlySnapshotsRemainReadableAtZeroProgress() throws IOException {
        NbtCompound old = snapshot(30, 0.5F).toNbt();
        old.putInt("FormatVersion", 1);
        old.remove("ExperienceProgress");
        old.remove("TotalExperiencePoints");
        InventorySnapshot loaded = InventorySnapshot.fromNbt(old);
        assertEquals(30, loaded.experienceLevel());
        assertEquals(0.0F, loaded.experienceProgress());
        assertEquals(1395, loaded.experience().totalPoints());
    }

    @Test
    void inconsistentMissingAndNonFiniteExperienceDataAreRejected() {
        NbtCompound inconsistent = snapshot(30, 0.5F).toNbt();
        inconsistent.putLong("TotalExperiencePoints", 1);
        assertThrows(IOException.class, () -> InventorySnapshot.fromNbt(inconsistent));
        NbtCompound missing = snapshot(30, 0.5F).toNbt();
        missing.remove("ExperienceProgress");
        assertThrows(IOException.class, () -> InventorySnapshot.fromNbt(missing));
        for (float progress : new float[] {-0.1F, 1.0F, Float.NaN, Float.POSITIVE_INFINITY}) {
            NbtCompound invalid = snapshot(30, 0.0F).toNbt();
            invalid.putFloat("ExperienceProgress", progress);
            assertThrows(IOException.class, () -> InventorySnapshot.fromNbt(invalid));
            assertThrows(IllegalArgumentException.class, () -> ExperiencePoints.fromLevelProgress(30, progress));
        }
        assertThrows(IllegalArgumentException.class, () -> ExperiencePoints.fromTotalPoints(-1));
        assertThrows(IllegalArgumentException.class, () -> ExperiencePoints.fromTotalPoints(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> ExperiencePoints.fromLevelProgress(-1, 0));
    }

    @Test
    void persistedTransferMergesBothBarsAndCannotBeImportedAfterCommit() throws IOException {
        TransferStore store = new TransferStore(gameDirectory);
        List<ItemStack> sourceMain = slots(36);
        sourceMain.set(0, new ItemStack(Items.DIAMOND, 7));
        InventorySnapshot source = new InventorySnapshot(UUID.randomUUID(), UUID.randomUUID(), sourceMain,
                slots(4), slots(1), 30, 57.0F / 112.0F);
        TransferStore.Pending pending = store.saveAndRemove("目标", source, sourceMain, slots(4), slots(1));
        assertTrue(sourceMain.stream().allMatch(ItemStack::isEmpty));
        ExperiencePoints.State target = ExperiencePoints.fromLevelProgress(16, 0.5F);
        ExperiencePoints.State merged = InventoryMerger.merge(store.readSnapshot(pending), slots(36), slots(4), slots(1),
                target, stack -> fail("不应掉落"));
        assertEquals(1452 + 352 + 21, merged.totalPoints());
        store.delete(pending);
        assertNull(store.readPending());
    }

    @Test
    void restoringTargetBeforeReplayPreventsDoubleExperience() {
        InventorySnapshot source = snapshot(30, 0.5F);
        InventorySnapshot before = snapshot(16, 0.5F);
        List<ItemStack> targetMain = slots(36);
        List<ItemStack> targetArmor = slots(4);
        List<ItemStack> targetOffhand = slots(1);
        ExperiencePoints.State first = InventoryMerger.merge(source, targetMain, targetArmor, targetOffhand,
                before.experience(), stack -> fail("不应掉落"));
        OriginalPlayerState.restoreSlots(before.main(), targetMain);
        ExperiencePoints.State replayed = InventoryMerger.merge(source, targetMain, targetArmor, targetOffhand,
                before.experience(), stack -> fail("不应掉落"));
        assertEquals(first, replayed);
        assertEquals(source.experience().totalPoints() + before.experience().totalPoints(), replayed.totalPoints());
    }

    @Test
    void rejectedExperienceSumDoesNotStartMovingItems() {
        List<ItemStack> source = slots(36);
        source.set(0, new ItemStack(Items.DIAMOND, 7));
        int maxLevel = (int) ((Integer.MAX_VALUE + 158L) / 9L);
        InventorySnapshot saved = new InventorySnapshot(UUID.randomUUID(), UUID.randomUUID(), source,
                slots(4), slots(1), maxLevel, 0.0F);
        List<ItemStack> target = slots(36);
        target.set(0, new ItemStack(Items.STONE, 64));
        assertThrows(IllegalArgumentException.class, () -> InventoryMerger.merge(saved, target, slots(4), slots(1),
                saved.experience(), stack -> fail("不应在校验失败时生成掉落")));
        assertTrue(target.get(1).isEmpty());
        assertEquals(64, target.get(0).getCount());
        assertEquals(7, source.get(0).getCount());
    }
}
