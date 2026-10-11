package cn.crosssave.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {
    // 设置与 MinecraftServer.stop(false) 相同的停止标志，由原版服务器线程保存世界并关闭会话。
    // IntegratedServer.stop 会先 submitAndJoin；启动已失败的线程不能再执行任务，直接调用会死锁。
    @Accessor("running")
    void crosssave$setRunning(boolean running);
}
