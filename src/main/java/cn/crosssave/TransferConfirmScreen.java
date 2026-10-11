package cn.crosssave;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class TransferConfirmScreen extends Screen {
    private final Screen parent;
    private final SaveCatalog.Entry target;
    private final CrossSaveClient crossSave;
    private boolean saving;
    private ButtonWidget confirm;
    private ButtonWidget cancel;
    private Text error;

    public TransferConfirmScreen(Screen parent, SaveCatalog.Entry target, CrossSaveClient crossSave) {
        super(Text.translatable("crosssave.screen.title"));
        this.parent = parent;
        this.target = target;
        this.crossSave = crossSave;
    }

    @Override
    protected void init() {
        int buttonWidth = Math.max(40, width - 48);
        int buttonY = height / 2 + 8;
        confirm = addDrawableChild(ButtonWidget.builder(Text.translatable("crosssave.screen.confirm", target.displayName()),
                        button -> confirmTransfer())
                .dimensions((width - buttonWidth) / 2, buttonY, buttonWidth, 20).build());
        cancel = addDrawableChild(ButtonWidget.builder(Text.translatable("crosssave.screen.cancel"), button -> close())
                .dimensions((width - buttonWidth) / 2, buttonY + 28, buttonWidth, 20).build());
        confirm.active = !saving;
        cancel.active = !saving;
    }

    private void confirmTransfer() {
        if (saving) {
            return;
        }
        saving = true;
        confirm.active = false;
        cancel.active = false;
        // 直接启动自动流程，保存、卸载、加载和恢复均由控制器根据真实事件推进。
        if (!crossSave.transferFromScreen(target)) {
            saving = false;
            confirm.active = true;
            cancel.active = true;
            error = Text.translatable("crosssave.screen.save_failed");
        }
    }

    @Override
    public void close() {
        if (!saving) {
            client.setScreen(parent);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !saving;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 52, 0xFFFFFF);
        // 小字仍使用原版字体，只缩小字号并改变颜色，不绘制自定义贴图。
        float scale = 0.8F;
        context.getMatrices().push();
        context.getMatrices().scale(scale, scale, 1);
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("crosssave.screen.hint"),
                Math.round(width / (2 * scale)), Math.round((height / 2F - 24) / scale), 0xAAAAAA);
        context.getMatrices().pop();
        if (error != null) {
            context.drawCenteredTextWithShadow(textRenderer, error, width / 2, height / 2 + 72, 0xFF7777);
        }
        super.render(context, mouseX, mouseY, delta);
    }
}
