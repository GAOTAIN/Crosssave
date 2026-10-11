package cn.crosssave;

import net.minecraft.client.MinecraftClient;

public final class TransferSound {
    private static final long RETRY_NANOS = 250_000_000L;
    private final CrossSaveConfig config;
    private SonarSoundInstance current;
    private long nextCheck;

    public TransferSound(CrossSaveConfig config) {
        this.config = config;
    }

    public void update(boolean transferring) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!transferring || !config.soundEnabled()) {
            stop();
            return;
        }
        long now = System.nanoTime();
        if (now < nextCheck) {
            return;
        }
        // 此间隔只限制失效音源的重试频率，开始和结束始终由真实迁移状态决定。
        nextCheck = now + RETRY_NANOS;
        try {
            if (client.getResourceManager().getResource(CrossSaveSounds.SONAR_RESOURCE).isEmpty()
                    || client.getSoundManager().get(CrossSaveSounds.SONAR_ID) == null) {
                // 音效未生成或资源尚未加载时静默跳过，迁移、进度与回滚照常执行。
                stop();
                return;
            }
            if (current != null && client.getSoundManager().isPlaying(current)) {
                return;
            }
            // 原版 disconnect 和资源重载会停止所有声音，恢复时先释放旧实例，避免重复叠加。
            stop();
            current = new SonarSoundInstance();
            client.getSoundManager().play(current);
        } catch (RuntimeException exception) {
            // 声卡或音效资源异常只影响可选音效，不阻止存档切换，也不弹出提示。
            stop();
        }
    }

    public void stop() {
        if (current != null) {
            try {
                MinecraftClient.getInstance().getSoundManager().stop(current);
            } catch (RuntimeException ignored) {
                // 客户端退出或声音设备已经关闭时仍释放本地实例。
            } finally {
                current = null;
            }
        }
    }
}
