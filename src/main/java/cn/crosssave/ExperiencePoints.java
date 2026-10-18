package cn.crosssave;

import net.minecraft.server.network.ServerPlayerEntity;

public final class ExperiencePoints {
    // 原版下一等级所需经验使用 int；超过此等级会使原版运算溢出。
    private static final int MAX_LEVEL = (int) ((Integer.MAX_VALUE + 158L) / 9L);
    private static final long MAX_TOTAL = pointsAtLevel(MAX_LEVEL) + pointsToNextLevel(MAX_LEVEL) - 1L;

    private ExperiencePoints() {
    }

    public record State(long totalPoints, int level, int pointsIntoLevel) {
        public State {
            if (level < 0 || level > MAX_LEVEL || pointsIntoLevel < 0
                    || pointsIntoLevel >= pointsToNextLevel(level)
                    || totalPoints != pointsAtLevel(level) + pointsIntoLevel) {
                throw new IllegalArgumentException("经验点数、等级与进度不一致");
            }
        }

        public float progress() {
            return (float) pointsIntoLevel / pointsToNextLevel(level);
        }
    }

    public static int pointsToNextLevel(int level) {
        requireLevel(level);
        // 对应 Minecraft 1.20.1 PlayerEntity.getNextLevelExperience 的三段原版公式。
        if (level >= 30) {
            return (int) (9L * level - 158L);
        }
        if (level >= 15) {
            return 5 * level - 38;
        }
        return 2 * level + 7;
    }

    public static long pointsAtLevel(int level) {
        requireLevel(level);
        long value = level;
        // 将原版每次升级的点数逐级求和；整数公式避免浮点开方在等级边界的误差。
        if (level <= 16) {
            return value * value + 6L * value;
        }
        if (level <= 31) {
            return (5L * value * value - 81L * value + 720L) / 2L;
        }
        return (9L * value * value - 325L * value + 4440L) / 2L;
    }

    private static void requireLevel(int level) {
        if (level < 0 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("经验等级超出原版安全运算范围");
        }
    }

    public static State fromLevelProgress(int level, float progress) {
        requireLevel(level);
        if (!Float.isFinite(progress) || progress < 0.0F || progress >= 1.0F) {
            throw new IllegalArgumentException("经验进度必须是零到一之间的有限值");
        }
        // 原版用 float 保存经验条；四舍五入还原整数点，避免一个经验点因浮点误差被截成零。
        long withinLevel = Math.round((double) progress * pointsToNextLevel(level));
        return fromTotalPoints(Math.addExact(pointsAtLevel(level), withinLevel));
    }

    public static State fromTotalPoints(long totalPoints) {
        if (totalPoints < 0 || totalPoints > MAX_TOTAL) {
            throw new IllegalArgumentException("总经验点数超出原版安全运算范围");
        }
        int low = 0;
        int high = MAX_LEVEL;
        // 二分反解累计经验公式，无需按经验点或等级循环添加经验。
        while (low < high) {
            int middle = low + (high - low + 1) / 2;
            if (pointsAtLevel(middle) <= totalPoints) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return new State(totalPoints, low, (int) (totalPoints - pointsAtLevel(low)));
    }

    public static State capture(ServerPlayerEntity player) {
        // 附魔或等级指令可能使 totalExperience 计数与实际可用经验不一致，因此以等级和经验条为准。
        return fromLevelProgress(player.experienceLevel, player.experienceProgress);
    }

    public static State merge(State source, State target) {
        // 原版经验曲线是非线性的，高等级合并时提升的等级低于直接相加等级，因而存在收益递减。
        return fromTotalPoints(Math.addExact(source.totalPoints(), target.totalPoints()));
    }

    public static void apply(ServerPlayerEntity player, State state) {
        // 极端点数若已超出原版 float 经验条的精确表达能力，交由既有回滚处理，不能静默丢点。
        if (fromLevelProgress(state.level(), state.progress()).totalPoints() != state.totalPoints()) {
            throw new IllegalArgumentException("合并经验超出原版经验条的精度范围");
        }
        // 只调用 1.20.1 的公开经验接口，同时同步等级、当前等级点数及原版累计计数。
        player.setExperienceLevel(state.level());
        player.setExperiencePoints(state.pointsIntoLevel());
        // 原版累计计数为 int；大额经验仍完整保存在等级、进度和迁移 NBT 的 long 总点数字段中。
        player.totalExperience = (int) Math.min(state.totalPoints(), Integer.MAX_VALUE);
    }

    public static void removeSource(ServerPlayerEntity player, InventorySnapshot persisted) {
        if (capture(player).totalPoints() != persisted.experience().totalPoints()) {
            throw new IllegalStateException("保存期间源存档经验发生变化，取消迁移");
        }
        // 仅在完整快照已写入并读回校验后调用；失败沿用原状态回滚，防止往返迁移复制经验。
        apply(player, fromTotalPoints(0));
    }
}
