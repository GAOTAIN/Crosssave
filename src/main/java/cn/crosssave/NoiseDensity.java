package cn.crosssave;

import java.lang.management.ManagementFactory;

public final class NoiseDensity {
    private static final long GIB = 1024L * 1024 * 1024;
    private final long memoryBytes = detectMemory();

    private static long detectMemory() {
        try {
            // 使用 Java 17 的公开系统管理接口读取物理内存，不能把 JVM 最大堆大小当作电脑内存。
            var bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean system) {
                return system.getTotalMemorySize();
            }
        } catch (RuntimeException exception) {
            CrossSaveClient.LOGGER.warn("无法检测物理内存，自动模式保守禁用噪点", exception);
        }
        return 0;
    }

    public double scale(CrossSaveConfig config, int fps) {
        return select(memoryBytes, fps, config.noiseEnabled(), config.noiseManualOverride());
    }

    static double select(long memory, int fps, boolean enabled, boolean manual) {
        if (!enabled) {
            return 0;
        }
        // 手动开启覆盖内存和低帧率降级；没有有效帧率样本时不把初始零值当作低帧率。
        if (manual) {
            return 1;
        }
        if (memory < 2 * GIB || (fps > 0 && fps < 30)) {
            return 0;
        }
        return memory >= 4 * GIB ? 1 : 0.5;
    }
}
