package cn.crosssave;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

public final class ScreenNoiseEffect {
    private final CrossSaveConfig config;
    private final NoiseDensity density = new NoiseDensity();
    private final NoiseAnimation animation = new NoiseAnimation();
    private long frame;
    private long updatedFrame = -1;

    public ScreenNoiseEffect(CrossSaveConfig config) {
        this.config = config;
    }

    public void nextFrame() {
        ++frame;
    }

    public void reset() {
        animation.reset();
        updatedFrame = -1;
    }

    public void worldUnloading() {
        animation.worldUnloading(System.nanoTime());
        updatedFrame = -1;
    }

    public void worldUnloaded() {
        animation.worldUnloaded(System.nanoTime());
        updatedFrame = -1;
    }

    public void worldLoading() {
        animation.worldLoading(System.nanoTime());
        updatedFrame = -1;
    }

    public void playerReady() {
        animation.playerReady(System.nanoTime());
        updatedFrame = -1;
    }

    public boolean isFading() {
        return animation.phase() == NoiseAnimation.Phase.FADING;
    }

    public double effectiveDensity() {
        return density.scale(config, MinecraftClient.getInstance().getCurrentFps());
    }

    public void render(DrawContext context, int width, int height) {
        long now = System.nanoTime();
        if (updatedFrame != frame) {
            animation.update(width, height, now, effectiveDensity());
            updatedFrame = frame;
        }
        // 同一帧可能同时绘制 Screen 和资源重载覆盖层，仅更新一次，避免密度翻倍。
        context.enableScissor(0, 0, width, height);
        for (NoiseAnimation.Pixel pixel : animation.pixels(now)) {
            context.fill(pixel.x(), pixel.y(), pixel.x() + pixel.size(), pixel.y() + pixel.size(), pixel.argb());
        }
        context.disableScissor();
    }
}
