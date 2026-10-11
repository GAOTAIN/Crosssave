package cn.crosssave;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public final class SaveSelectionScreen extends Screen {
    private final List<SaveCatalog.Entry> entries;
    private final CrossSaveClient crossSave;
    private int scrollOffset;
    private int visibleRows;
    private static final int LIST_TOP = 40;
    private static final int ROW_HEIGHT = 24;

    public SaveSelectionScreen(List<SaveCatalog.Entry> entries, CrossSaveClient crossSave) {
        super(Text.translatable("crosssave.screen.title"));
        this.entries = List.copyOf(entries);
        this.crossSave = crossSave;
    }

    @Override
    protected void init() {
        // 最多显示十行，小窗口根据可用高度减少行数；只创建可见的原版按钮。
        visibleRows = Math.max(1, Math.min(10, (height - 92) / ROW_HEIGHT));
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, entries.size() - visibleRows)));
        int buttonWidth = Math.max(40, width - 48);
        for (int row = 0; row < visibleRows && scrollOffset + row < entries.size(); row++) {
            SaveCatalog.Entry entry = entries.get(scrollOffset + row);
            ButtonWidget button = ButtonWidget.builder(Text.translatable("crosssave.screen.save_name", entry.displayName()),
                            clicked -> client.setScreen(new TransferConfirmScreen(this, entry, crossSave)))
                    .dimensions((width - buttonWidth) / 2, LIST_TOP + row * ROW_HEIGHT, buttonWidth, 20).build();
            button.setTooltip(Tooltip.of(Text.translatable("crosssave.screen.save_details", entry.displayName(), entry.folderName())));
            addDrawableChild(button);
        }
        int left = (width - buttonWidth) / 2;
        int settingsWidth = Math.min(100, Math.max(20, buttonWidth / 3));
        addDrawableChild(ButtonWidget.builder(Text.translatable("crosssave.screen.cancel"), button -> close())
                .dimensions(left, height - 28, buttonWidth - settingsWidth - 8, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("crosssave.settings.title"),
                        button -> crossSave.openNoiseSettings(this))
                .dimensions(left + buttonWidth - settingsWidth, height - 28, settingsWidth, 20).build());
    }

    private boolean scroll(int amount) {
        int updated = Math.max(0, Math.min(scrollOffset + amount, Math.max(0, entries.size() - visibleRows)));
        if (updated == scrollOffset) {
            return false;
        }
        scrollOffset = updated;
        clearAndInit();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (mouseY >= LIST_TOP && mouseY < height - 44 && amount != 0) {
            return scroll(amount > 0 ? -1 : 1);
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            return scroll(-visibleRows);
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            return scroll(visibleRows);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void close() {
        client.setScreen(null);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 16, 0xFFFFFF);
        if (entries.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable("crosssave.screen.empty"), width / 2,
                    height / 2, 0xAAAAAA);
        } else if (entries.size() > visibleRows) {
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable("crosssave.screen.scroll",
                    scrollOffset + 1, Math.min(entries.size(), scrollOffset + visibleRows), entries.size()),
                    width / 2, height - 44, 0xAAAAAA);
        }
        super.render(context, mouseX, mouseY, delta);
    }
}
