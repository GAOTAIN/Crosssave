package cn.crosssave.mixin;

import cn.crosssave.AutomaticWorldSwitcher;
import cn.crosssave.TransferLoadingScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.SplashOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SplashOverlay.class)
public abstract class SplashOverlayMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void crosssave$showProgressDuringResourceReload(DrawContext context, int mouseX, int mouseY,
                                                           float delta, CallbackInfo ci) {
        AutomaticWorldSwitcher switcher = AutomaticWorldSwitcher.instance();
        MinecraftClient client = MinecraftClient.getInstance();
        if (switcher != null && switcher.isActive() && client.currentScreen instanceof TransferLoadingScreen screen) {
            // 资源包重载时原版只画覆盖层，不画 Screen；在其后绘制同一加载条，保留原版重载生命周期。
            screen.render(context, mouseX, mouseY, delta);
        }
    }
}
