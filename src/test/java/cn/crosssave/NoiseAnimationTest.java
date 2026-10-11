package cn.crosssave;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class NoiseAnimationTest {
    @Test
    void unloadCompletionClearsBlueImmediatelyAndLoadingStartsRedImmediately() {
        NoiseAnimation noise = new NoiseAnimation(new Random(1));
        noise.worldUnloading(0);
        noise.update(800, 600, 0, 1);
        assertEquals(3, noise.pixels(0).size());
        assertTrue(noise.pixels(0).stream().allMatch(pixel -> (pixel.argb() & 0xFFFFFF) == 0x66B3FF));
        noise.worldUnloaded(1);
        assertEquals(NoiseAnimation.Phase.GAP, noise.phase());
        assertEquals(0xFF4040, noise.colorRgb());
        assertTrue(noise.pixels(1).isEmpty());
        noise.worldLoading(1);
        noise.update(800, 600, 1, 1);
        assertEquals(1, noise.pixels(1).size());
        assertEquals(0xFF4040, noise.pixels(1).get(0).argb() & 0xFFFFFF);
    }

    @Test
    void timeChangesDensityButNeverAdvancesTheWorldLifecycle() {
        NoiseAnimation noise = new NoiseAnimation(new Random(2));
        noise.worldUnloading(0);
        assertEquals(3, noise.emission(0));
        assertEquals(1.5, noise.emission(1_000_000_000L));
        noise.update(800, 600, 50_000_000_000L, 1);
        assertEquals(0, noise.emission(50_000_000_000L));
        assertEquals(NoiseAnimation.Phase.UNLOADING, noise.phase());
        noise.worldLoading(50_000_000_000L);
        assertEquals(1, noise.emission(50_000_000_000L));
        assertEquals(4, noise.emission(52_000_000_000L));
        noise.update(800, 600, 80_000_000_000L, 1);
        assertEquals(NoiseAnimation.Phase.LOADING, noise.phase());
    }

    @Test
    void pixelsStartAtEdgesAndMoveInTheDirectionOfTheRealPhase() {
        for (boolean unloading : new boolean[] { true, false }) {
            NoiseAnimation noise = new NoiseAnimation(new Random(3));
            if (unloading) {
                noise.worldUnloading(0);
            } else {
                noise.worldLoading(0);
            }
            noise.update(800, 600, 0, 1);
            var start = noise.pixels(0);
            var moved = noise.pixels(10_000_000L);
            for (int i = 0; i < start.size(); i++) {
                var first = start.get(i);
                var next = moved.get(i);
                int edge = Math.min(Math.min(first.x(), 800 - first.x()), Math.min(first.y(), 600 - first.y()));
                assertTrue(edge <= (unloading ? 40 : 100));
                assertTrue(first.size() >= 2 && first.size() <= 4);
                int alpha = first.argb() >>> 24;
                assertTrue(alpha >= (unloading ? 76 : 102) && alpha <= (unloading ? 153 : 204));
                double before = Math.hypot(first.x() - 400, first.y() - 300);
                double after = Math.hypot(next.x() - 400, next.y() - 300);
                assertTrue(unloading ? after > before : after < before);
                assertTrue((next.argb() >>> 24) < alpha);
            }
        }
    }

    @Test
    void playerReadyStopsEmissionAndFadeFinishesWithinHalfASecond() {
        NoiseAnimation noise = new NoiseAnimation(new Random(4));
        noise.worldLoading(0);
        noise.update(800, 600, 0, 1);
        noise.playerReady(1);
        assertEquals(NoiseAnimation.Phase.FADING, noise.phase());
        assertEquals(0, noise.emission(1));
        noise.update(800, 600, 500_000_001L, 1);
        assertEquals(NoiseAnimation.Phase.IDLE, noise.phase());
        assertTrue(noise.pixels(500_000_001L).isEmpty());
        noise.worldUnloading(600_000_000L);
        noise.update(800, 600, 600_000_000L, 1);
        assertEquals(3, noise.pixels(600_000_000L).size());
    }
}
