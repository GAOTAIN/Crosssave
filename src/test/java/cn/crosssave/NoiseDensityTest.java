package cn.crosssave;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NoiseDensityTest {
    private static final long GIB = 1024L * 1024 * 1024;

    @Test
    void automaticDensityUsesPhysicalMemoryBoundaries() {
        assertEquals(0, NoiseDensity.select(2 * GIB - 1, 60, true, false));
        assertEquals(0.5, NoiseDensity.select(2 * GIB, 60, true, false));
        assertEquals(0.5, NoiseDensity.select(4 * GIB - 1, 60, true, false));
        assertEquals(1, NoiseDensity.select(4 * GIB, 60, true, false));
    }

    @Test
    void lowFpsDisablesAutomaticNoiseButAnUnknownSampleDoesNot() {
        assertEquals(0, NoiseDensity.select(8 * GIB, 29, true, false));
        assertEquals(1, NoiseDensity.select(8 * GIB, 30, true, false));
        assertEquals(1, NoiseDensity.select(8 * GIB, 0, true, false));
    }

    @Test
    void ManualChoiceHasPriorityOverAutomaticClassification() {
        assertEquals(1, NoiseDensity.select(GIB, 15, true, true));
        assertEquals(0, NoiseDensity.select(8 * GIB, 120, false, true));
        assertEquals(0, NoiseDensity.select(8 * GIB, 120, false, false));
    }
}
