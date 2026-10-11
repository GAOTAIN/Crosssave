package cn.crosssave.mixin;

import cn.crosssave.AutomaticWorldSwitcher;
import cn.crosssave.TransferLoadingScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void crosssave$beginNoiseFrame(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        AutomaticWorldSwitcher switcher = AutomaticWorldSwitcher.instance();
        if (switcher != null) {
            // 原版资源包覆盖层可能遮住加载界面，仍按真实切换状态维护循环音效。
            switcher.sound().update(switcher.isActive());
            switcher.noise().nextFrame();
            switcher.synchronizeNoiseUnload();
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;draw()V"))
    private void crosssave$finishNoiseFade(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        AutomaticWorldSwitcher switcher = AutomaticWorldSwitcher.instance();
        MinecraftClient client = MinecraftClient.getInstance();
        if (switcher == null || !switcher.noise().isFading() || client.currentScreen instanceof TransferLoadingScreen) {
            return;
        }
        // 使用 1.20.1 GameRenderer 的最终 GUI 批次提交前节点，此时正交投影和 GUI 矩阵仍然有效。
        // 只叠加 DrawContext.fill 方块，让原有完成逻辑立即关闭加载界面，剩余红色噪点自行淡出。
        DrawContext context = new DrawContext(client, client.getBufferBuilders().getEntityVertexConsumers());
        switcher.noise().render(context, client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight());
        context.draw();
    }
}
