package cn.crosssave;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.io.IOException;
import java.util.List;

public final class DisclaimerScreen extends Screen {
    private final Screen parent;
    private final CrossSaveConfig config;
    private List<OrderedText> bodyLines = List.of();
    private int titleY;
    private int bodyY;

    public DisclaimerScreen(Screen parent, CrossSaveConfig config) {
        super(Text.translatable("crosssave.disclaimer.title"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void init() {
        bodyLines = textRenderer.wrapLines(Text.translatable("crosssave.disclaimer.body"), Math.max(40, Math.min(600, width - 48)));
        int bodyHeight = bodyLines.size() * (textRenderer.fontHeight + 3);
        titleY = Math.max(16, (height - bodyHeight - 84) / 2);
        bodyY = titleY + 30;
        int buttonWidth = Math.max(40, Math.min(420, width - 48));
        int buttonY = Math.min(height - 44, bodyY + bodyHeight + 24);
        addDrawableChild(ButtonWidget.builder(Text.translatable("crosssave.disclaimer.button"), button -> accept())
                .dimensions((width - buttonWidth) / 2, buttonY, buttonWidth, 20).build());
    }

    private void accept() {
        try {
            config.acceptDisclaimer();
        } catch (IOException exception) {
            // 配置中的历史接受状态不再控制本次启动；保存失败也不否定玩家已经点击确认。
            CrossSaveClient.LOGGER.warn("保存免责声明历史状态失败，本次确认仍有效", exception);
        }
        CrossSaveClient.hasShownDisclaimerThisSession = true;
        client.setScreen(parent);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void close() {
        // 关闭只能通过接受按钮，不能通过返回或 Escape 绕过免责声明。
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xFF000000);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xFFFFFF);
        for (int i = 0; i < bodyLines.size(); i++) {
            OrderedText line = bodyLines.get(i);
            context.drawTextWithShadow(textRenderer, line, (width - textRenderer.getWidth(line)) / 2,
                    bodyY + i * (textRenderer.fontHeight + 3), 0xDDDDDD);
        }
        super.render(context, mouseX, mouseY, delta);
    }
}
