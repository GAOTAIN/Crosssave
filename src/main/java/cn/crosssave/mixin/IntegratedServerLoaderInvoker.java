package cn.crosssave.mixin;

import net.minecraft.resource.ResourcePackManager;
import net.minecraft.server.SaveLoader;
import net.minecraft.server.integrated.IntegratedServerLoader;
import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(IntegratedServerLoader.class)
public interface IntegratedServerLoaderInvoker {
    // 调用 Yarn 1.20.1 的私有数据包加载方法，沿用原版注册表和存档解析。
    // 必须取得加载异常而非弹出原版选择界面，才能在自动切换失败时关闭会话并回滚。
    @Invoker("createSaveLoader")
    SaveLoader crosssave$createSaveLoader(LevelStorage.Session session, boolean safeMode,
                                         ResourcePackManager manager) throws Exception;
}
