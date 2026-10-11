package cn.crosssave;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.io.IOException;

public final class NoiseSettingsScreen extends Screen {
    private final Screen parent;
    private final CrossSaveConfig config;
    private final ScreenNoiseEffect noise;
    private final TransferSound sound;
    private Text error;

    public NoiseSettingsScreen(Screen parent, CrossSaveConfig config, ScreenNoiseEffect noise, TransferSound sound) {
        super(Text.translatable("crosssave.settings.title"));
        this.parent = parent;
        this.config = config;
        this.noise = noise;
        this.sound = sound;
    }

    private Text toggleText() {
        return Text.translatable("crosssave.settings.noise", Text.translatable(config.noiseEnabled()
                ? "crosssave.settings.on" : "crosssave.settings.off"));
    }

    private Text soundToggleText() {
        return Text.translatable("crosssave.settings.sound", Text.translatable(config.soundEnabled()
                ? "crosssave.settings.on" : "crosssave.settings.off"));
    }

    @Override
    protected void init() {
        int buttonWidth = Math.max(40, Math.min(360, width - 48));
        addDrawableChild(ButtonWidget.builder(toggleText(), button -> {
            try {
                // 完整写入配置成功后才更新内存和按钮，写入失败不会假装开关已经保存。
                config.setNoiseEnabled(!config.noiseEnabled());
                noise.reset();
                error = null;
                button.setMessage(toggleText());
            } catch (IOException exception) {
                CrossSaveClient.LOGGER.error("保存噪点设置失败", exception);
                error = Text.translatable("crosssave.settings.save_failed");
            }
        }).dimensions((width - buttonWidth) / 2, height / 2 - 28, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(soundToggleText(), button -> {
            try {
                config.setSoundEnabled(!config.soundEnabled());
                // 开关实时保存，关闭时立即停止已有声音，不改变噪点设置。
                if (!config.soundEnabled()) {
                    sound.stop();
                }
                error = null;
                button.setMessage(soundToggleText());
            } catch (IOException exception) {
                CrossSaveClient.LOGGER.error("保存音效设置失败", exception);
                error = Text.translatable("crosssave.settings.save_failed");
            }
        }).dimensions((width - buttonWidth) / 2, height / 2, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("crosssave.settings.back"), button -> close())
                .dimensions((width - buttonWidth) / 2, height - 28, buttonWidth, 20).build());
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 20, 0xFFFFFF);
        Text status = config.noiseManualOverride() ? Text.translatable("crosssave.settings.manual")
                : Text.translatable("crosssave.settings.auto", Math.round(noise.effectiveDensity() * 100));
        context.drawCenteredTextWithShadow(textRenderer, status, width / 2, height / 2 + 32, 0xAAAAAA);
        if (error != null) {
            context.drawCenteredTextWithShadow(textRenderer, error, width / 2, height - 44, 0xFF7777);
        }
        super.render(context, mouseX, mouseY, delta);
    }
}
