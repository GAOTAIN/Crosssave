package cn.crosssave;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.text.Text;

public final class TransferLoadingScreen extends DownloadingTerrainScreen {
    private final AutomaticWorldSwitcher switcher;
    private final boolean waitingForTerrain;
    private boolean positionReady;

    public TransferLoadingScreen(AutomaticWorldSwitcher switcher, boolean waitingForTerrain) {
        this.switcher = switcher;
        this.waitingForTerrain = waitingForTerrain;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void tick() {
        // 继承 1.20.1 的地形等待界面，保留原版对位置同步和区块就绪的检测。
        // 原版客户端通过 instanceof DownloadingTerrainScreen 调用 setReady，不能换成普通 Screen 丢掉该信号。
        // 原版还有三十秒强制关闭分支；自动转移不用该计时分支，只接受真实位置和地形就绪。
        if (waitingForTerrain && switcher.isWaitingForPlayer() && terrainReady()) {
            switcher.onTerrainReady();
        }
    }

    @Override
    public void setReady() {
        positionReady = true;
    }

    private boolean terrainReady() {
        if (!positionReady || client.world == null || client.player == null) {
            return false;
        }
        var position = client.player.getBlockPos();
        return client.world.isOutOfHeightLimit(position.getY()) || client.player.isSpectator()
                || !client.player.isAlive() || (client.world.isChunkLoaded(position)
                && client.worldRenderer.isRenderingReady(position));
    }

    @Override
    public void close() {
        // 只接受原版地形等待逻辑的完成回调；界面没有按钮，Esc 也不能触发关闭。
        if (waitingForTerrain && terrainReady()) {
            switcher.onTerrainReady();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return true;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 原版启动内置服务器期间可能只渲染而不执行客户端 tick，音效也要在这些帧保持同步。
        switcher.sound().update(switcher.isActive());
        context.fill(0, 0, width, height, 0xD9000000);
        // 背景上叠加噪点，再绘制文字和进度条，保证原有内容清楚可读。
        switcher.noise().render(context, width, height);
        int center = width / 2;
        int titleY = Math.round(height * 0.35F);
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("crosssave.loading.title"),
                center, titleY, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("crosssave.loading.subtitle"),
                center, titleY + 20, 0xAAAAAA);
        int barWidth = Math.round(width * 0.6F);
        int barX = (width - barWidth) / 2;
        int barY = Math.round(height * 0.55F);
        int percent = switcher.progress().displayedPercent(System.nanoTime());
        context.fill(barX, barY, barX + barWidth, barY + 10, 0xFF333333);
        context.fill(barX, barY, barX + barWidth * percent / 100, barY + 10, 0xFFCCCCCC);
        // Minecraft 的翻译格式只支持 %s；语言文件按要求保留 %d%%，只在展示时转换格式占位符。
        String format = Text.translatable("crosssave.loading.percent").getString();
        Text percentage = percentageText(format, percent);
        context.drawCenteredTextWithShadow(textRenderer, percentage, center, barY + 25, 0xFFFFFF);
    }

    static Text percentageText(String format, int percent) {
        return Text.translatableWithFallback("crosssave.loading.percent.render", format.replace("%d", "%s"), percent);
    }
}
