package cn.crosssave;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class CrossSaveClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("crosssave");
    public static boolean hasShownDisclaimerThisSession = false;
    // 此集合仅由内置服务器线程访问，防止清理失败后重复恢复同一批数据。
    private final Set<UUID> appliedTransfers = new HashSet<>();
    private CrossSaveConfig config;
    private ScreenNoiseEffect noise;
    private TransferSound sound;
    private CrossSaveKeyBindings keyBindings;
    private IntegratedServer checkedServer;
    private UUID checkedPlayer;
    private boolean openingSaveSelection;
    private AutomaticWorldSwitcher worldSwitcher;

    @Override
    public void onInitializeClient() {
        config = CrossSaveConfig.load(MinecraftClient.getInstance().runDirectory.toPath());
        noise = new ScreenNoiseEffect(config);
        sound = new TransferSound(config);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> sound.stop());
        keyBindings = new CrossSaveKeyBindings(config);
        worldSwitcher = new AutomaticWorldSwitcher(this);
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("crosssave")
                        .executes(context -> run(context.getSource(), player -> message(player, "crosssave.chat.usage")))
                        .then(literal("save").then(argument("targetFolder", StringArgumentType.greedyString())
                                .executes(context -> save(context.getSource(), parseFolderArgument(
                                        StringArgumentType.getString(context, "targetFolder"))))))
                        .then(literal("load").executes(context -> load(context.getSource())))));
    }

    public ScreenNoiseEffect noise() {
        return noise;
    }

    public TransferSound sound() {
        return sound;
    }

    public void openNoiseSettings(Screen parent) {
        MinecraftClient.getInstance().setScreen(new NoiseSettingsScreen(parent, config, noise, sound));
    }

    private void onClientTick(MinecraftClient client) {
        sound.update(worldSwitcher.isActive());
        if (client.currentScreen instanceof TitleScreen && !hasShownDisclaimerThisSession) {
            client.setScreen(new DisclaimerScreen(client.currentScreen, config));
            return;
        }
        if (!hasShownDisclaimerThisSession) {
            return;
        }
        if (worldSwitcher.tick(client)) {
            return;
        }
        keyBindings.tick(client, this);
        if (!client.isInSingleplayer() || client.getServer() == null || client.player == null || client.world == null) {
            checkedServer = null;
            checkedPlayer = null;
            return;
        }
        if (client.currentScreen != null) {
            return;
        }
        IntegratedServer server = client.getServer();
        UUID uuid = client.player.getUuid();
        if (checkedServer == server && uuid.equals(checkedPlayer)) {
            return;
        }
        checkedServer = server;
        checkedPlayer = uuid;
        Path gameDirectory = client.runDirectory.toPath();
        // 每次进入单人世界只检查一次，不自动切换存档；不匹配的待恢复记录保持不变。
        server.execute(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                try {
                    loadPlayerData(player, server, gameDirectory, true);
                } catch (RuntimeException exception) {
                    LOGGER.error("自动恢复随身数据失败", exception);
                    message(player, "crosssave.chat.load_failed");
                }
            }
        });
    }

    private static String parseFolderArgument(String argument) throws CommandSyntaxException {
        // 贪婪字符串支持不加引号的中文目录名，也接受带空格目录名的双引号写法。
        if (!argument.startsWith("\"")) {
            return argument;
        }
        StringReader reader = new StringReader(argument);
        String folder = reader.readQuotedString();
        reader.skipWhitespace();
        if (reader.canRead()) {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().createWithContext(reader);
        }
        return folder;
    }

    private int run(FabricClientCommandSource source, Consumer<ServerPlayerEntity> action) {
        MinecraftClient client = MinecraftClient.getInstance();
        // 客户端命令不要求作弊或权限等级，每次执行前先限制为单人存档。
        if (worldSwitcher.isActive()) {
            source.sendError(Text.translatable("crosssave.chat.busy"));
            return 0;
        }
        if (!client.isInSingleplayer() || client.getServer() == null) {
            source.sendError(Text.translatable("crosssave.chat.singleplayer_only"));
            return 0;
        }
        IntegratedServer server = client.getServer();
        UUID playerUuid = source.getPlayer().getUuid();
        // 背包、经验、实体掉落与传送均在服务器线程处理，避免客户端修改被同步覆盖。
        server.execute(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUuid);
            if (player != null) {
                try {
                    action.accept(player);
                } catch (RuntimeException exception) {
                    LOGGER.error("执行 crosssave 命令失败", exception);
                    message(player, "crosssave.chat.operation_failed");
                }
            }
        });
        return 1;
    }

    private int save(FabricClientCommandSource source, String targetFolder) {
        Path gameDirectory = MinecraftClient.getInstance().runDirectory.toPath();
        return run(source, player -> savePlayerData(player, gameDirectory, targetFolder,
                targetDisplayName(gameDirectory, targetFolder)));
    }

    record SavedTransfer(TransferStore.Pending pending, OriginalPlayerState originalState) { }

    private boolean savePlayerData(ServerPlayerEntity player, Path gameDirectory, String targetFolder, String displayName) {
        return saveTransfer(player, gameDirectory, targetFolder, displayName, true) != null;
    }

    SavedTransfer saveForSwitch(ServerPlayerEntity player, Path gameDirectory, SaveCatalog.Entry target) {
        return saveTransfer(player, gameDirectory, target.folderName(), target.displayName(), false);
    }

    private SavedTransfer saveTransfer(ServerPlayerEntity player, Path gameDirectory, String targetFolder,
                                       String displayName, boolean showManualMessage) {
        if (!TransferStore.isFolderName(targetFolder)) {
            message(player, "crosssave.chat.invalid_folder");
            return null;
        }
        if (player.hasVehicle()) {
            player.stopRiding();
            message(player, "crosssave.chat.dismounted");
        }
        TransferStore store = new TransferStore(gameDirectory);
        OriginalPlayerState before = null;
        TransferStore.Pending saved = null;
        try {
            InventorySnapshot snapshot = InventorySnapshot.capture(player.getUuid(), player.getInventory(), player.experienceLevel);
            before = OriginalPlayerState.capture(player, snapshot);
            // 手动命令、手动界面与自动切换共用同一移出入口，避免任何路径仅保存副本。
            saved = store.saveAndRemove(targetFolder, snapshot, player.getInventory().main,
                    player.getInventory().armor, player.getInventory().offHand);
            player.getInventory().markDirty();
            player.currentScreenHandler.sendContentUpdates();
            if (showManualMessage) {
                message(player, "crosssave.chat.saved", displayName);
            }
            return new SavedTransfer(saved, before);
        } catch (IOException | RuntimeException exception) {
            if (saved != null) {
                try {
                    // 同步失败时先取消可导入记录，再归还原物品，不能同时留下两份可用数据。
                    store.cancel(saved);
                    before.restore(player);
                } catch (IOException | RuntimeException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
            }
            LOGGER.error("保存或移出随身物品失败", exception);
            message(player, exception instanceof TransferStore.PendingTransferException
                    ? "crosssave.chat.pending_exists" : "crosssave.chat.save_failed");
            return null;
        }
    }

    private static String targetDisplayName(Path gameDirectory, String folder) {
        if (TransferStore.isFolderName(folder)) {
            try {
                return SaveCatalog.displayName(gameDirectory.resolve("saves").resolve(folder));
            } catch (IOException | RuntimeException exception) {
                LOGGER.debug("无法读取目标显示名，使用文件夹名：{}", folder, exception);
            }
        }
        return folder;
    }

    public void openSaveSelection() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isInSingleplayer() || client.getServer() == null || client.player == null) {
            clientMessage(client, "crosssave.chat.singleplayer_only");
            return;
        }
        if (openingSaveSelection || worldSwitcher.isActive() || !hasShownDisclaimerThisSession) {
            return;
        }
        openingSaveSelection = true;
        IntegratedServer server = client.getServer();
        UUID uuid = client.player.getUuid();
        Path savesDirectory = client.runDirectory.toPath().resolve("saves");
        server.execute(() -> {
            try {
                String currentFolder = WorldFolderResolver.resolve(savesDirectory, server);
                var entries = SaveCatalog.list(savesDirectory, currentFolder);
                client.execute(() -> {
                    openingSaveSelection = false;
                    // 目录读取结束时再次检查会话，避免退出世界后打开过期的选择界面。
                    if (client.getServer() == server && client.player != null && uuid.equals(client.player.getUuid())
                            && client.currentScreen == null && client.isInSingleplayer()) {
                        client.setScreen(new SaveSelectionScreen(entries, this));
                    }
                });
            } catch (IOException | RuntimeException exception) {
                LOGGER.error("读取存档列表失败", exception);
                client.execute(() -> {
                    openingSaveSelection = false;
                    if (client.getServer() == server) {
                        clientMessage(client, "crosssave.chat.list_failed");
                    }
                });
            }
        });
    }

    public void saveFromScreen(SaveCatalog.Entry target, Consumer<Boolean> completion) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isInSingleplayer() || client.getServer() == null || client.player == null) {
            clientMessage(client, "crosssave.chat.singleplayer_only");
            completion.accept(false);
            return;
        }
        IntegratedServer server = client.getServer();
        UUID uuid = client.player.getUuid();
        Path gameDirectory = client.runDirectory.toPath();
        server.execute(() -> {
            boolean success = false;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            try {
                if (player != null) {
                    // 与命令共用同一个保存实现，元数据始终使用文件夹名而不是显示名。
                    success = savePlayerData(player, gameDirectory, target.folderName(), target.displayName());
                }
            } catch (RuntimeException exception) {
                LOGGER.error("从确认界面保存随身数据失败", exception);
                if (player != null) {
                    message(player, "crosssave.chat.save_failed");
                }
            }
            boolean saved = success;
            client.execute(() -> completion.accept(saved));
        });
    }

    private int load(FabricClientCommandSource source) {
        MinecraftClient client = MinecraftClient.getInstance();
        Path gameDirectory = client.runDirectory.toPath();
        IntegratedServer server = client.getServer();
        return run(source, player -> loadPlayerData(player, server, gameDirectory, false));
    }

    public boolean transferFromScreen(SaveCatalog.Entry target) {
        return worldSwitcher.begin(target);
    }

    boolean loadPlayerData(ServerPlayerEntity player, IntegratedServer server, Path gameDirectory, boolean automatic) {
        TransferStore store = new TransferStore(gameDirectory);
        OriginalPlayerState targetBeforeMerge = null;
        TransferStore.Pending pending = null;
        List<ItemEntity> createdDrops = new ArrayList<>();
        boolean committed = false;
        boolean repairedNestedInventory = false;
        try {
            pending = store.readPending();
            if (pending == null) {
                if (!automatic) {
                    message(player, "crosssave.chat.no_pending");
                }
                return false;
            }
            String folder = WorldFolderResolver.resolve(gameDirectory.resolve("saves"), server);
            if (!pending.targetFolder().equals(folder)) {
                if (!automatic) {
                    message(player, "crosssave.chat.world_mismatch");
                }
                return false;
            }
            if (!pending.playerUuid().equals(player.getUuid())) {
                if (!automatic) {
                    message(player, "crosssave.chat.uuid_mismatch");
                }
                return false;
            }
            // 匹配存档与 UUID 后才允许打开 NBT；清理重试不再次合并物品。
            if (!appliedTransfers.contains(pending.transferId())) {
                InventorySnapshot snapshot = store.readSnapshot(pending);
                targetBeforeMerge = OriginalPlayerState.capture(player, InventorySnapshot.capture(player.getUuid(),
                        player.getInventory(), player.experienceLevel));
                int level = InventoryMerger.merge(snapshot, player.getInventory().main, player.getInventory().armor,
                        player.getInventory().offHand, player.experienceLevel,
                        stack -> createdDrops.add(dropAtFeet(player, stack)));
                if (level > player.experienceLevel) {
                    player.addExperienceLevels(level - player.experienceLevel);
                }
                if (!NestedInventorySafety.matches(snapshot, targetBeforeMerge.inventory(),
                        player.getInventory().main, player.getInventory().armor, player.getInventory().offHand,
                        createdDrops.stream().map(ItemEntity::getStack).toList())) {
                    LOGGER.warn("检测到潜影箱或嵌套物品 NBT 异常，将按完整快照重做本批迁移：{}", pending.transferId());
                    // 撤销本次目标变更及掉落，再从独立完整 NBT 深拷贝；不得在异常物品旁再添加一份副本。
                    for (ItemEntity drop : createdDrops) {
                        drop.discard();
                    }
                    createdDrops.clear();
                    targetBeforeMerge.restore(player);
                    int correctedLevel = InventoryMerger.merge(snapshot, player.getInventory().main,
                            player.getInventory().armor, player.getInventory().offHand, player.experienceLevel,
                            stack -> createdDrops.add(dropAtFeet(player, stack)));
                    if (correctedLevel > player.experienceLevel) {
                        player.addExperienceLevels(correctedLevel - player.experienceLevel);
                    }
                    if (!NestedInventorySafety.matches(snapshot, targetBeforeMerge.inventory(),
                            player.getInventory().main, player.getInventory().armor, player.getInventory().offHand,
                            createdDrops.stream().map(ItemEntity::getStack).toList())) {
                        throw new IOException("嵌套物品安全校验仍失败，撤销目标并交由原有回滚处理");
                    }
                    repairedNestedInventory = true;
                }
                player.getInventory().markDirty();
                player.currentScreenHandler.sendContentUpdates();
                appliedTransfers.add(pending.transferId());
                SafePositionFinder.ensureSafe(player);
            }
            store.delete(pending);
            committed = true;
            appliedTransfers.remove(pending.transferId());
            if (repairedNestedInventory) {
                message(player, "crosssave.chat.nested_safe");
            }
            message(player, "crosssave.chat.arrived", server.getSaveProperties().getLevelName());
            return true;
        } catch (IOException | RuntimeException exception) {
            if (committed) {
                // 物品已经提交成功，聊天提示异常不能把物品撤销，否则手动迁移已无源数据可恢复。
                LOGGER.warn("迁移已完成，但完成提示失败", exception);
                return true;
            }
            if (targetBeforeMerge != null) {
                // 导入或清理失败时撤销目标侧的物品变更和本批次掉落，不能把部分导入留在目标后又归还原存档。
                for (ItemEntity drop : createdDrops) {
                    drop.discard();
                }
                targetBeforeMerge.restore(player);
                appliedTransfers.remove(pending.transferId());
            }
            LOGGER.error("恢复或清理随身数据失败", exception);
            message(player, "crosssave.chat.load_failed");
            return false;
        }
    }

    private static void message(ServerPlayerEntity player, String key, Object... arguments) {
        player.sendMessage(Text.translatable(key, arguments), false);
    }

    static void clientMessage(MinecraftClient client, String key, Object... arguments) {
        if (client.player != null) {
            client.player.sendMessage(Text.translatable(key, arguments), false);
        } else {
            client.inGameHud.getChatHud().addMessage(Text.translatable(key, arguments));
        }
    }

    private static ItemEntity dropAtFeet(ServerPlayerEntity player, ItemStack stack) {
        // 直接在脚下生成物品实体，避免原版抛掷动作把物品丢到前方。
        ItemEntity entity = new ItemEntity(player.getServerWorld(), player.getX(), player.getY() + 0.1,
                player.getZ(), NestedInventorySafety.copyFullNbt(stack));
        entity.setToDefaultPickupDelay();
        entity.setVelocity(0, 0.1, 0);
        if (!player.getServerWorld().spawnEntity(entity)) {
            throw new IllegalStateException("无法生成转移物品的掉落实体");
        }
        return entity;
    }
}
