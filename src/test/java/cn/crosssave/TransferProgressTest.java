package cn.crosssave;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransferProgressTest {
    @Test
    void interpolationNeverCompletesTheRealEvent() {
        TransferProgress progress = new TransferProgress();
        progress.begin(0);
        assertEquals(0, progress.displayedPercent(0));
        assertEquals(25, progress.displayedPercent(1_000_000_000L));
        assertEquals(50, progress.displayedPercent(2_000_000_000L));
        assertEquals(50, progress.displayedPercent(30_000_000_000L));
        assertEquals(0, progress.actualNode());
        progress.worldUnloaded(30_000_000_000L);
        progress.worldLoading(31_000_000_000L);
        assertEquals(50, progress.displayedPercent(31_000_000_000L));
        assertEquals(75, progress.displayedPercent(32_000_000_000L));
        assertEquals(100, progress.displayedPercent(50_000_000_000L));
        assertEquals(50, progress.actualNode());
        progress.playerReady(51_000_000_000L);
        assertEquals(100, progress.actualNode());
        assertEquals(100, progress.displayedPercent(51_000_000_000L));
    }

    @Test
    void fastEventsAndRollbackResetTheVisibleSegment() {
        TransferProgress progress = new TransferProgress();
        progress.begin(100);
        progress.worldUnloaded(200);
        assertEquals(50, progress.displayedPercent(200));
        progress.worldLoading(200);
        progress.playerReady(300);
        assertEquals(100, progress.displayedPercent(300));
        progress.worldUnloaded(400);
        progress.worldLoading(400);
        assertEquals(50, progress.actualNode());
        assertEquals(50, progress.displayedPercent(400));
    }
}
