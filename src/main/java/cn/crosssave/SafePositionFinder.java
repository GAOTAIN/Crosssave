package cn.crosssave;

import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityPose;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

public final class SafePositionFinder {
    private SafePositionFinder() {
    }

    public static void ensureSafe(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        if (isSafe(world, player, player.getPos())) {
            return;
        }
        BlockPos origin = player.getBlockPos();
        Vec3d destination = find(world, player, origin);
        if (destination == null) {
            // 搜索当前维度的出生点，不切换维度，不生成或破坏方块。
            destination = find(world, player, world.getSpawnPos());
        }
        if (destination == null) {
            player.sendMessage(Text.translatable("crosssave.chat.no_safe_position"), false);
            return;
        }
        player.stopRiding();
        player.teleport(world, destination.x, destination.y, destination.z, player.getYaw(), player.getPitch());
        player.setVelocity(Vec3d.ZERO);
        player.fallDistance = 0;
        player.sendMessage(Text.translatable("crosssave.chat.moved_to_safety"), false);
    }

    private static Vec3d find(ServerWorld world, ServerPlayerEntity player, BlockPos origin) {
        int startY = Math.max(world.getBottomY() + 1, Math.min(origin.getY(), world.getTopY() - 2));
        // 优先沿原位置向上寻找，碰撞箱需完全离开方块，脚下也必须有支撑。
        for (int y = startY; y < world.getTopY() - 1; y++) {
            Vec3d candidate = standingPosition(world, origin.getX(), y, origin.getZ());
            if (isSafe(world, player, candidate)) {
                return candidate;
            }
        }
        // 从近到远按方形环搜索水平安全方向，同时检查相邻高度。
        for (int radius = 1; radius <= 32; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    int x = origin.getX() + dx;
                    int z = origin.getZ() + dz;
                    if (!isChunkLoaded(world, new BlockPos(x, startY, z))) {
                        continue;
                    }
                    // 每列先检查原高度，再交替检查上下各八格。
                    for (int offset = 0; offset <= 16; offset++) {
                        int dy = offset == 0 ? 0 : (offset % 2 == 1 ? (offset + 1) / 2 : -offset / 2);
                        Vec3d candidate = standingPosition(world, x, startY + dy, z);
                        if (isSafe(world, player, candidate)) {
                            return candidate;
                        }
                    }
                    // 深坑或虚空位置还需检查同列地表，避免只搜索原高度而漏掉地面。
                    int surfaceY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                    Vec3d surface = standingPosition(world, x, surfaceY, z);
                    if (isSafe(world, player, surface)) {
                        return surface;
                    }
                }
            }
        }
        return null;
    }

    private static Vec3d standingPosition(ServerWorld world, int x, int y, int z) {
        BlockPos floor = new BlockPos(x, y - 1, z);
        if (y > world.getBottomY() && y < world.getTopY() && isChunkLoaded(world, floor)) {
            var shape = world.getBlockState(floor).getCollisionShape(world, floor);
            if (!shape.isEmpty()) {
                // 使用碰撞形状上表面，半砖等非整格地面同样可以作为安全落点。
                return new Vec3d(x + 0.5, y - 1 + shape.getMax(Direction.Axis.Y), z + 0.5);
            }
        }
        return new Vec3d(x + 0.5, y, z + 0.5);
    }

    private static boolean isChunkLoaded(ServerWorld world, BlockPos pos) {
        return world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static boolean isSafe(ServerWorld world, ServerPlayerEntity player, Vec3d position) {
        // 用站立姿态检查，防止潜行或游泳时找到无法正常站立的位置。
        Box body = player.getDimensions(EntityPose.STANDING).getBoxAt(position);
        if (body.minY < world.getBottomY() + 1 || body.maxY >= world.getTopY()
                || !world.getWorldBorder().contains(body)) {
            return false;
        }
        BlockPos from = BlockPos.ofFloored(body.minX - 2, body.minY - 1, body.minZ - 2);
        BlockPos to = BlockPos.ofFloored(body.maxX + 2, body.maxY + 1, body.maxZ + 2);
        for (BlockPos pos : BlockPos.iterate(from, to)) {
            if (!isChunkLoaded(world, pos)) {
                return false;
            }
        }
        if (!world.isSpaceEmpty(player, body)) {
            return false;
        }
        // 脚下没有碰撞支撑视为不安全，覆盖虚空上方与正在坠落的情况。
        Box support = new Box(body.minX, body.minY - 0.08, body.minZ,
                body.maxX, body.minY, body.maxZ);
        if (!world.getBlockCollisions(player, support).iterator().hasNext()) {
            return false;
        }
        // 通过碰撞与支撑检查后，再检查玩家全身附近的岩浆。
        for (BlockPos pos : BlockPos.iterate(from, to)) {
            if (world.getBlockState(pos).getFluidState().isIn(FluidTags.LAVA)) {
                return false;
            }
        }
        for (BlockPos pos : BlockPos.iterate(BlockPos.ofFloored(body.minX, body.minY - 0.08, body.minZ),
                BlockPos.ofFloored(body.maxX, body.maxY, body.maxZ))) {
            var state = world.getBlockState(pos);
            if (state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE) || state.isOf(Blocks.MAGMA_BLOCK)
                    || state.isOf(Blocks.CACTUS) || state.isOf(Blocks.CAMPFIRE) || state.isOf(Blocks.SOUL_CAMPFIRE)
                    || !state.getFluidState().isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
