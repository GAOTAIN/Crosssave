package cn.crosssave;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// 仅维护屏幕坐标；所有阶段变化都由真实世界事件调用，经过时间只影响密度、运动和淡出。
public final class NoiseAnimation {
    public enum Phase { IDLE, UNLOADING, GAP, LOADING, FADING }
    private static final long DENSITY_RAMP = 2_000_000_000L;
    private static final long FADE_DURATION = 500_000_000L;
    private static final int MAX_DOTS = 512;
    private static final int BLUE = 0x66B3FF;
    private static final int RED = 0xFF4040;
    private final Random random;
    private final List<Dot> dots = new ArrayList<>();
    private Phase phase = Phase.IDLE;
    private long phaseStarted;
    private double emissionCredit;
    private int colorRgb = BLUE;

    public NoiseAnimation() {
        this(new Random());
    }

    NoiseAnimation(Random random) {
        this.random = random;
    }

    public Phase phase() {
        return phase;
    }

    int colorRgb() {
        return colorRgb;
    }

    public void reset() {
        phase = Phase.IDLE;
        dots.clear();
        emissionCredit = 0;
        colorRgb = BLUE;
    }

    public void worldUnloading(long now) {
        change(Phase.UNLOADING, now);
    }

    public void worldUnloaded(long now) {
        // 事件到达时立即清空全部蓝色方块，绝不使用插值百分比判断是否卸载完成。
        change(Phase.GAP, now);
    }

    public void worldLoading(long now) {
        // 不人为加入等待间隙；真实加载开始时立即切换红色并允许首帧生成。
        change(Phase.LOADING, now);
    }

    public void playerReady(long now) {
        if (phase == Phase.LOADING) {
            phase = Phase.FADING;
            phaseStarted = now;
            emissionCredit = 0;
        }
    }

    private void change(Phase next, long now) {
        phase = next;
        phaseStarted = now;
        emissionCredit = 0;
        dots.clear();
        // 颜色变量在卸载完成时就切到红色，间隙中不生成方块，不添加任何固定等待。
        colorRgb = next == Phase.UNLOADING ? BLUE : RED;
    }

    double emission(long now) {
        double fraction = Math.min(1, Math.max(0, now - phaseStarted) / (double) DENSITY_RAMP);
        return switch (phase) {
            case UNLOADING -> 3 * (1 - fraction);
            case LOADING -> 1 + 3 * fraction;
            default -> 0;
        };
    }

    public void update(int width, int height, long now, double density) {
        if (phase == Phase.FADING && now - phaseStarted >= FADE_DURATION) {
            reset();
            return;
        }
        dots.removeIf(dot -> now - dot.born >= dot.life);
        if (density <= 0 || width <= 0 || height <= 0) {
            dots.clear();
            emissionCredit = 0;
            return;
        }
        emissionCredit += emission(now) * density;
        int count = (int) emissionCredit;
        emissionCredit -= count;
        for (int i = 0; i < count && dots.size() < MAX_DOTS; i++) {
            dots.add(spawn(width, height, now));
        }
    }

    private Dot spawn(int width, int height, long now) {
        boolean blue = phase == Phase.UNLOADING;
        double band = blue ? 40 : 100;
        double x = random.nextDouble() * width;
        double y = random.nextDouble() * height;
        switch (random.nextInt(4)) {
            case 0 -> x = random.nextDouble() * Math.min(band, width / 2.0);
            case 1 -> x = width - random.nextDouble() * Math.min(band, width / 2.0);
            case 2 -> y = random.nextDouble() * Math.min(band, height / 2.0);
            default -> y = height - random.nextDouble() * Math.min(band, height / 2.0);
        }
        double dx = width / 2.0 - x;
        double dy = height / 2.0 - y;
        double length = Math.hypot(dx, dy);
        double speed = (blue ? -1 : 1) * (80 + random.nextDouble() * 120);
        double vx = length == 0 ? 0 : dx / length * speed;
        double vy = length == 0 ? 0 : dy / length * speed;
        double alpha = blue ? 0.3 + random.nextDouble() * 0.3 : 0.4 + random.nextDouble() * 0.4;
        // 红色生成区域为距屏幕边缘零至一百像素的带状区域，小窗口按半宽或半高收缩。
        return new Dot(x, y, vx, vy, 2 + random.nextInt(3), alpha,
                colorRgb, now, 100_000_000L + random.nextInt(300_000_001));
    }

    public List<Pixel> pixels(long now) {
        double fade = phase == Phase.FADING
                ? Math.max(0, 1 - (now - phaseStarted) / (double) FADE_DURATION) : 1;
        List<Pixel> pixels = new ArrayList<>(dots.size());
        for (Dot dot : dots) {
            long age = Math.max(0, now - dot.born);
            if (age >= dot.life) {
                continue;
            }
            double seconds = age / 1_000_000_000.0;
            // 每个方块在其短寿命内衰减；玩家生成后再叠加最多半秒的整体淡出。
            int alpha = (int) Math.round(255 * dot.alpha * (1 - age / (double) dot.life) * fade);
            pixels.add(new Pixel((int) Math.round(dot.x + dot.vx * seconds),
                    (int) Math.round(dot.y + dot.vy * seconds), dot.size, (alpha << 24) | dot.rgb));
        }
        return pixels;
    }

    private record Dot(double x, double y, double vx, double vy, int size, double alpha,
                       int rgb, long born, long life) { }

    public record Pixel(int x, int y, int size, int argb) { }
}
