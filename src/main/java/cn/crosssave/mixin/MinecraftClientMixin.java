package cn.crosssave.mixin;

import cn.crosssave.AutomaticWorldSwitcher;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.resource.ServerResourcePackProvider;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.crash.CrashReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Shadow private IntegratedServer server;
    @Shadow private boolean integratedServerRunning;
    @Unique private IntegratedServer crosssave$disconnectingServer;

    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen crosssave$keepLoadingScreen(Screen screen) {
        AutomaticWorldSwitcher switcher = AutomaticWorldSwitcher.instance();
        return switcher == null ? screen : switcher.screenFor(screen);
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screen/Screen;)V", at = @At("HEAD"))
    private void crosssave$beforeDisconnect(Screen screen, CallbackInfo ci) {
        crosssave$disconnectingServer = server;
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screen/Screen;)V", at = @At("RETURN"))
    private void crosssave$afterDisconnect(Screen screen, CallbackInfo ci) {
        AutomaticWorldSwitcher switcher = AutomaticWorldSwitcher.instance();
        if (switcher != null) {
            // 1.20.1 的 disconnect(Screen) 在返回前等待旧服务器线程退出，并清空 world 和 player。
            // 使用返回节点而非估计时间判定客户端卸载；控制器还会核实旧线程和存档会话已结束。
            switcher.onWorldDisconnected(crosssave$disconnectingServer);
            if (switcher.isActive() && server == null) {
                // 启动失败可能尚无 ClientWorld，原版条件分支不会重置这个标志，自动流程需要补齐清理。
                integratedServerRunning = false;
            }
        }
        crosssave$disconnectingServer = null;
    }

    @Redirect(method = "disconnect(Lnet/minecraft/client/gui/screen/Screen;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resource/ServerResourcePackProvider;clear()Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> crosssave$trackResourceClearing(ServerResourcePackProvider provider) {
        CompletableFuture<Void> result = provider.clear();
        AutomaticWorldSwitcher switcher = AutomaticWorldSwitcher.instance();
        if (switcher != null && switcher.isActive()) {
            // 沿用原版 clear 调用，只记录返回的真实完成信号，避免新资源包与旧包卸载重叠。
            switcher.onResourcesClearing(result);
        }
        return result;
    }

    @Inject(method = "setCrashReportSupplier", at = @At("HEAD"), cancellable = true)
    private void crosssave$handleTransferStartupCrash(CrashReport report, CallbackInfo ci) {
        AutomaticWorldSwitcher switcher = AutomaticWorldSwitcher.instance();
        if (switcher != null && switcher.captureStartupCrash(report)) {
            // 仅自动切换中新世界的启动失败转为回滚；正常游戏的崩溃报告仍按原版处理。
            ci.cancel();
        }
    }

    @Redirect(method = "startIntegratedServer", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;yield()V"))
    private void crosssave$checkServerStartup() {
        AutomaticWorldSwitcher switcher = AutomaticWorldSwitcher.instance();
        if (switcher != null) {
            switcher.checkStartupFailure();
        }
        Thread.yield();
    }

    @Redirect(method = "startIntegratedServer", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;sleep(J)V"))
    private void crosssave$checkWorldPreparation(long milliseconds) throws InterruptedException {
        AutomaticWorldSwitcher switcher = AutomaticWorldSwitcher.instance();
        if (switcher != null) {
            // 原版准备出生区块的循环只渲染不执行客户端时钟，因此必须在这个内部等待点检查启动异常。
            switcher.checkStartupFailure();
        }
        Thread.sleep(milliseconds);
    }
}
