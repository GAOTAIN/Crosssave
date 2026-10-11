package cn.crosssave;

public final class TransferProgress {
    private static final long INTERPOLATION_NANOS = 2_000_000_000L;
    private int actualNode;
    private int from;
    private int to;
    private long segmentStarted;

    public void begin(long now) {
        actualNode = 0;
        from = 0;
        to = 50;
        segmentStarted = now;
    }

    public void worldUnloaded(long now) {
        actualNode = 50;
        from = to = 50;
        segmentStarted = now;
    }

    public void worldLoading(long now) {
        from = 50;
        to = 100;
        segmentStarted = now;
    }

    public void playerReady(long now) {
        actualNode = 100;
        from = to = 100;
        segmentStarted = now;
    }

    public int actualNode() {
        return actualNode;
    }

    public int displayedPercent(long now) {
        // 实际节点只由卸载、加载和玩家就绪事件更新，绝不用计时器完成转移。
        // 节点间的显示估计按实际经过的时间平滑填充；两秒后显示下一节点，但继续等待真实事件。
        double elapsed = Math.max(0, now - segmentStarted) / (double) INTERPOLATION_NANOS;
        double fraction = Math.min(1, elapsed);
        double smooth = fraction * fraction * (3 - 2 * fraction);
        return (int) Math.round(from + (to - from) * smooth);
    }
}
