package cn.crosssave;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

public final class CrossSaveKeyBindings {
    private final KeyBinding openScreen;
    private final InputUtil.Key configuredKey;
    private boolean configuredThisLaunch;

    public CrossSaveKeyBindings(CrossSaveConfig config) {
        configuredKey = config.openScreenKey();
        openScreen = KeyBindingHelper.registerKeyBinding(new KeyBinding("crosssave.key.open",
                InputUtil.Type.KEYSYM, configuredKey.getCode(), "crosssave.key.category"));
    }

    public void tick(MinecraftClient client, CrossSaveClient crossSave) {
        if (!configuredThisLaunch) {
            // 在原版选项加载之后应用配置，使修改 crosssave.json 可以改变本次启动的按键。
            openScreen.setBoundKey(configuredKey);
            KeyBinding.updateKeysByCode();
            configuredThisLaunch = true;
        }
        while (openScreen.wasPressed()) {
            if (client.currentScreen == null) {
                crossSave.openSaveSelection();
            }
        }
    }
}
