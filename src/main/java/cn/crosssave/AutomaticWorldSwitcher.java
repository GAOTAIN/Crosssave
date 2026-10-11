package cn.crosssave;

import cn.crosssave.mixin.IntegratedServerLoaderInvoker;
import cn.crosssave.mixin.MinecraftServerAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.client.gui.screen.LevelLoadingScreen;
import net.minecraft.client.gui.screen.NoticeScreen;
import net.minecraft.client.gui.screen.ProgressScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.VanillaDataPackProvider;
import net.minecraft.server.SaveLoader;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.crash.CrashException;
import net.minecraft.util.crash.CrashReport;
import net.minecraft.world.level.storage.LevelStorage;
import net.minecraft.world.level.storage.LevelSummary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

// 内部调用均按本地 Yarn 1.20.1+build.10 核对，不使用其他游戏版本的方法签名。
// 只在客户端线程卸载和启动世界；物品、经验、掉落及回滚在内置服务器线程处理。
public final class AutomaticWorldSwitcher {
    private enum Stage {
        IDLE, SAVING, UNLOADING_TARGET, LOADING_TARGET, RESTORING_TARGET,
        UNLOADING_ORIGINAL, LOADING_ORIGINAL, RESTORING_ORIGINAL, UNLOADING_MANUAL
    }

    private static AutomaticWorldSwitcher instance;
    private final CrossSaveClient crossSave;
    private final TransferProgress progress = new TransferProgress();
    private final AtomicReference<RuntimeException> startupFailure = new AtomicReference<>();
    private volatile Stage stage = Stage.IDLE;
    private IntegratedServer originalServer;
    private IntegratedServer unloadingServer;
    private boolean disconnectReturned;
    private boolean noiseUnloadReported;
    private UUID playerUuid;
    private Path gameDirectory;
    private String originalFolder;
    private SaveCatalog.Entry target;
    private OriginalPlayerState originalState;
    private TransferStore.Pending pending;
    private Resources prepared;
    private CompletableFuture<?> packLoading;
    private CompletableFuture<Void> clearingPack = CompletableFuture.completedFuture(null);
    private int generation;
    private boolean insideServerStart;

    public AutomaticWorldSwitcher(CrossSaveClient crossSave) {
        this.crossSave = crossSave;
        instance = this;
    }

    public static AutomaticWorldSwitcher instance() {
        return instance;
    }

    public boolean isActive() {
        return stage != Stage.IDLE;
    }

    public boolean isWaitingForPlayer() {
        return stage == Stage.LOADING_TARGET || stage == Stage.LOADING_ORIGINAL;
    }

    public TransferProgress progress() {
        return progress;
    }

    public TransferSound sound() {
        return crossSave.sound();
    }

    public ScreenNoiseEffect noise() {
        return crossSave.noise();
    }

    public boolean begin(SaveCatalog.Entry destination) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (isActive() || !client.isInSingleplayer() || client.getServer() == null || client.player == null) {
            return false;
        }
        originalServer = client.getServer();
        playerUuid = client.player.getUuid();
        gameDirectory = client.runDirectory.toPath();
        target = destination;
        noise().reset();
        stage = Stage.SAVING;
        startupFailure.set(null);
        progress.begin(System.nanoTime());
        // 必须在客户端线程调用内部 setScreenAndRender，先展示一帧加载条再提交保存任务。
        client.setScreenAndRender(new TransferLoadingScreen(this, false));
        originalServer.execute(() -> {
            try {
                ServerPlayerEntity player = originalServer.getPlayerManager().getPlayer(playerUuid);
                if (player == null) {
                    throw new IOException("当前玩家已离开原存档");
                }
                String source = WorldFolderResolver.resolve(gameDirectory.resolve("saves"), originalServer);
                if (source.equals(target.folderName()) || !TransferStore.isFolderName(target.folderName())
                        || !Files.isRegularFile(gameDirectory.resolve("saves").resolve(target.folderName()).resolve("level.dat"))) {
                    throw new IOException("目标存档无效或与原存档相同");
                }
                // 共用 26.01 的保存实现，骑乘也由该方法在序列化前强制解除；自动路径不发送手动退出提示。
                CrossSaveClient.SavedTransfer saved = crossSave.saveForSwitch(player, gameDirectory, target);
                if (saved == null) {
                    client.execute(this::saveFailed);
                    return;
                }
                // 保存入口已经完成移出，直接保留它返回的回滚快照，禁止再次清空物品栏。
                client.execute(() -> {
                    originalFolder = source;
                    originalState = saved.originalState();
                    pending = saved.pending();
                    unload(false);
                });
            } catch (IOException | RuntimeException exception) {
                CrossSaveClient.LOGGER.error("自动切换前保存或校验失败", exception);
                client.execute(this::saveFailed);
            }
        });
        return true;
    }

    private void saveFailed() {
        MinecraftClient client = MinecraftClient.getInstance();
        stage = Stage.IDLE;
        sound().stop();
        noise().reset();
        client.setScreen(null);
        CrossSaveClient.clientMessage(client, "crosssave.chat.save_failed");
    }

    public void onWorldDisconnected(IntegratedServer departedServer) {
        if ((stage == Stage.UNLOADING_TARGET || stage == Stage.UNLOADING_ORIGINAL || stage == Stage.UNLOADING_MANUAL)
                && departedServer == unloadingServer) {
            disconnectReturned = true;
            synchronizeNoiseUnload();
        }
    }

    public void synchronizeNoiseUnload() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!noiseUnloadReported && disconnectReturned
                && (stage == Stage.UNLOADING_TARGET || stage == Stage.UNLOADING_ORIGINAL || stage == Stage.UNLOADING_MANUAL)
                && client.world == null && client.getServer() == null
                && (unloadingServer == null || (unloadingServer.isStopped() && !unloadingServer.getThread().isAlive()))) {
            // 在 disconnect 返回节点以及每次渲染前核实旧线程退出，蓝色不会多停留一帧。
            // 资源包清理可能继续进行，但旧世界已经卸载；红色仍只由 loadWorld 的实际开始节点触发。
            noiseUnloadReported = true;
            noise().worldUnloaded();
        }
    }

    public void onResourcesClearing(CompletableFuture<Void> completion) {
        clearingPack = completion;
    }

    private void unload(boolean rollback) {
        MinecraftClient client = MinecraftClient.getInstance();
        stage = rollback ? Stage.UNLOADING_ORIGINAL : Stage.UNLOADING_TARGET;
        noiseUnloadReported = false;
        noise().worldUnloading();
        disconnectReturned = false;
        unloadingServer = client.getServer();
        clearingPack = CompletableFuture.completedFuture(null);
        try {
            // 顺序与 1.20.1 原版退出单人存档一致：先断开 ClientWorld，再执行 MinecraftClient.disconnect(Screen)。
            // 启动失败时可能尚无 ClientWorld，补发服务器停止标志，否则会遗留服务器和存档锁。
            boolean hadWorld = client.world != null;
            if (hadWorld) {
                client.world.disconnect();
            }
            if (unloadingServer != null) {
                ((MinecraftServerAccessor) unloadingServer).crosssave$setRunning(false);
            }
            client.disconnect(new TransferLoadingScreen(this, false));
            if (!hadWorld) {
                clearingPack = client.getServerResourcePackProvider().clear();
            }
        } catch (RuntimeException exception) {
            CrossSaveClient.LOGGER.error("卸载存档失败", exception);
            if (rollback) {
                manualFallback();
            } else {
                rollback(exception);
            }
        }
    }

    public boolean tick(MinecraftClient client) {
        if (!isActive()) {
            return false;
        }
        RuntimeException failure = startupFailure.get();
        if (failure != null && isWaitingForPlayer() && !insideServerStart) {
            if (stage == Stage.LOADING_TARGET) {
                rollback(failure);
            } else {
                manualFallback();
            }
            return true;
        }
        if (stage == Stage.UNLOADING_TARGET || stage == Stage.UNLOADING_ORIGINAL || stage == Stage.UNLOADING_MANUAL) {
            synchronizeNoiseUnload();
            // 必须同时确认客户端卸载返回、世界字段清空、旧服务器线程退出，不能用进度条或延时替代。
            if (disconnectReturned && client.world == null && client.getServer() == null
                    && (unloadingServer == null || (unloadingServer.isStopped() && !unloadingServer.getThread().isAlive()))) {
                if (!clearingPack.isDone()) {
                    return true;
                }
                if (stage == Stage.UNLOADING_MANUAL) {
                    showManualFallback();
                    return true;
                }
                if (clearingPack.isCompletedExceptionally()) {
                    if (stage == Stage.UNLOADING_TARGET) {
                        rollback(new IOException("卸载原存档资源包失败"));
                    } else {
                        manualFallback();
                    }
                    return true;
                }
                boolean rollback = stage == Stage.UNLOADING_ORIGINAL;
                progress.worldUnloaded(System.nanoTime());
                loadWorld(rollback);
            }
        }
        return true;
    }

    private void loadWorld(boolean rollback) {
        MinecraftClient client = MinecraftClient.getInstance();
        String folder = rollback ? originalFolder : target.folderName();
        stage = rollback ? Stage.LOADING_ORIGINAL : Stage.LOADING_TARGET;
        noise().worldLoading();
        startupFailure.set(null);
        int attempt = ++generation;
        progress.worldLoading(System.nanoTime());
        client.setScreenAndRender(new TransferLoadingScreen(this, false));
        try {
            LevelStorage.Session session = client.getLevelStorage().createSession(folder);
            Resources resources = new Resources(session);
            prepared = resources;
            LevelSummary summary = session.getLevelSummary();
            // 原版要求额外版本处理的存档走手动路径，尤其不能自动降级更高版本的世界。
            if (summary == null || summary.requiresConversion() || !summary.isVersionAvailable()
                    || summary.getConversionWarning().promptsBackup()) {
                throw new IOException("目标存档需要手动处理版本或格式转换");
            }
            resources.manager = VanillaDataPackProvider.createManager(session);
            // 复用 1.20.1 私有 createSaveLoader，正常解析数据包、注册表和 level.dat；不启用安全模式或替换数据包。
            resources.loader = ((IntegratedServerLoaderInvoker) client.createIntegratedServerLoader())
                    .crosssave$createSaveLoader(session, false, resources.manager);
            // 复用原版存档资源包加载流程，不改变其网络请求；将异步失败交给回滚，不弹出自动流程无法操作的确认界面。
            packLoading = client.getServerResourcePackProvider().loadServerPack(session);
            packLoading.whenComplete((unused, error) -> client.execute(() -> {
                if (attempt != generation || !isWaitingForPlayer()) {
                    resources.close();
                    return;
                }
                if (error != null) {
                    loadFailed(rollback, error);
                    return;
                }
                startPreparedServer(folder, resources, rollback);
            }));
        } catch (Exception exception) {
            loadFailed(rollback, exception);
        }
    }

    private void startPreparedServer(String folder, Resources resources, boolean rollback) {
        MinecraftClient client = MinecraftClient.getInstance();
        try {
            insideServerStart = true;
            // 必须调用内部 startIntegratedServer；它创建服务器、准备出生区块并建立原版本地连接。
            // 顺序是旧世界完全卸载 -> 加载目标数据包及资源包 -> 启动目标服务器 -> 等待地形和玩家 -> 恢复。
            client.startIntegratedServer(folder, resources.session, resources.manager, resources.loader, false);
            resources.ownedByServer = true;
        } catch (RuntimeException exception) {
            resources.ownedByServer = client.getServer() != null;
            loadFailed(rollback, exception);
        } finally {
            insideServerStart = false;
        }
    }

    public boolean captureStartupCrash(CrashReport report) {
        if (!isWaitingForPlayer()) {
            return false;
        }
        // 内置服务器线程可能先于客户端等待循环失败，只记录异常；客户端负责卸载和回滚。
        startupFailure.compareAndSet(null, new CrashException(report));
        return true;
    }

    public void checkStartupFailure() {
        if (insideServerStart) {
            RuntimeException failure = startupFailure.get();
            if (failure != null) {
                throw failure;
            }
        }
    }

    private void loadFailed(boolean rollback, Throwable exception) {
        CrossSaveClient.LOGGER.error(rollback ? "重新加载原存档失败" : "加载目标存档失败", exception);
        if (rollback) {
            manualFallback();
        } else {
            rollback(exception);
        }
    }

    private void rollback(Throwable reason) {
        CrossSaveClient.LOGGER.warn("自动切换失败，重新加载原存档", reason);
        invalidatePreparation();
        startupFailure.set(null);
        unload(true);
    }

    public void onTerrainReady() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!isWaitingForPlayer() || client.world == null || client.player == null || client.getServer() == null) {
            return;
        }
        boolean rollback = stage == Stage.LOADING_ORIGINAL;
        IntegratedServer server = client.getServer();
        UUID actualPlayer = client.player.getUuid();
        stage = rollback ? Stage.RESTORING_ORIGINAL : Stage.RESTORING_TARGET;
        progress.playerReady(System.nanoTime());
        noise().playerReady();
        server.execute(() -> {
            try {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(actualPlayer);
                String folder = WorldFolderResolver.resolve(gameDirectory.resolve("saves"), server);
                if (player == null || !playerUuid.equals(actualPlayer)
                        || !folder.equals(rollback ? originalFolder : target.folderName())) {
                    throw new IOException("加载完成后的玩家或存档不匹配");
                }
                if (rollback) {
                    // 先持久化取消状态，再归还原物品，否则残留 pending 会在以后进入目标时再次复制。
                    new TransferStore(gameDirectory).cancel(pending);
                    originalState.restore(player);
                    player.sendMessage(Text.translatable("crosssave.chat.failed"), false);
                    player.sendMessage(Text.translatable("crosssave.chat.transfer_cancelled"), false);
                } else {
                    TransferStore.Pending current = new TransferStore(gameDirectory).readPending();
                    if (!pending.equals(current) || !crossSave.loadPlayerData(player, server, gameDirectory, false)) {
                        throw new IOException("目标存档数据恢复失败");
                    }
                }
                client.execute(this::completed);
            } catch (IOException | RuntimeException exception) {
                client.execute(() -> loadFailed(rollback, exception));
            }
        });
    }

    private void completed() {
        MinecraftClient client = MinecraftClient.getInstance();
        // 实际玩家已经生成且恢复已完成，立即绘制一次 100%，不依赖固定显示时长。
        client.setScreenAndRender(new TransferLoadingScreen(this, false));
        stage = Stage.IDLE;
        sound().stop();
        prepared = null;
        packLoading = null;
        originalState = null;
        originalServer = null;
        unloadingServer = null;
        client.setScreen(null);
    }

    public Screen screenFor(Screen requested) {
        if (!isActive() || requested instanceof TransferLoadingScreen) {
            return requested;
        }
        if (requested instanceof DownloadingTerrainScreen) {
            return new TransferLoadingScreen(this, true);
        }
        if (requested == null || requested instanceof ProgressScreen || requested instanceof LevelLoadingScreen) {
            return new TransferLoadingScreen(this, false);
        }
        // 原版失败界面或其他模组中断加载时不伪装为成功，保持遮挡并让客户端下一次时钟执行回滚。
        startupFailure.compareAndSet(null, new IllegalStateException("加载被界面中断：" + requested.getClass().getName()));
        return new TransferLoadingScreen(this, false);
    }

    private void invalidatePreparation() {
        ++generation;
        if (packLoading != null && !packLoading.isDone()) {
            packLoading.cancel(false);
        }
        if (prepared != null) {
            prepared.close();
            prepared = null;
        }
    }

    private void manualFallback() {
        MinecraftClient client = MinecraftClient.getInstance();
        invalidatePreparation();
        // 原存档也无法自动重开时终止自动流程；不删除转移文件，允许玩家手动打开目标存档继续恢复。
        stage = Stage.UNLOADING_MANUAL;
        noiseUnloadReported = false;
        noise().worldUnloading();
        disconnectReturned = false;
        IntegratedServer server = client.getServer();
        unloadingServer = server;
        clearingPack = CompletableFuture.completedFuture(null);
        boolean hadWorld = client.world != null;
        if (hadWorld) {
            client.world.disconnect();
        }
        if (server != null) {
            ((MinecraftServerAccessor) server).crosssave$setRunning(false);
        }
        client.disconnect(new TransferLoadingScreen(this, false));
        if (!hadWorld) {
            clearingPack = client.getServerResourcePackProvider().clear();
        }
    }

    private void showManualFallback() {
        MinecraftClient client = MinecraftClient.getInstance();
        stage = Stage.IDLE;
        sound().stop();
        noise().reset();
        Text explanation = Text.translatable("crosssave.chat.rollback_failed")
                .append(Text.translatable("crosssave.chat.manual_fallback", target.displayName()));
        CrossSaveClient.clientMessage(client, "crosssave.chat.manual_fallback", target.displayName());
        client.setScreen(new NoticeScreen(() -> client.setScreen(new TitleScreen()),
                Text.translatable("crosssave.screen.rollback_failed"), explanation,
                Text.translatable("crosssave.screen.cancel"), true));
    }

    private static final class Resources {
        private final LevelStorage.Session session;
        private ResourcePackManager manager;
        private SaveLoader loader;
        private boolean ownedByServer;
        private boolean closed;

        private Resources(LevelStorage.Session session) {
            this.session = session;
        }

        private void close() {
            if (closed || ownedByServer) {
                return;
            }
            closed = true;
            try {
                if (loader != null) {
                    loader.close();
                }
            } catch (RuntimeException exception) {
                CrossSaveClient.LOGGER.warn("关闭失败加载的数据包资源失败", exception);
            } finally {
                // 1.20.1 的 ResourcePackManager 没有 close 方法；打开的数据包资源由 SaveLoader.close 释放。
                try {
                    session.close();
                } catch (IOException exception) {
                    CrossSaveClient.LOGGER.warn("关闭失败加载的存档会话失败", exception);
                }
            }
        }
    }
}
