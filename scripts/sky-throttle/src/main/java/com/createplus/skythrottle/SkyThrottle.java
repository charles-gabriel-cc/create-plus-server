package com.createplus.skythrottle;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

/**
 * Cosmonautics redraws its 64x64 star plasma noise texture on every client tick, in every dimension, which
 * measured 8-11% of the render thread in spark. Redrawing it every few ticks keeps the animation at a fraction
 * of the cost; the redraw itself is skipped by {@code SkyHandlerMixin}.
 */
@Mod(value = SkyThrottle.MOD_ID, dist = Dist.CLIENT)
public final class SkyThrottle {
    public static final String MOD_ID = "createplus_sky_throttle";
    /** Ticks between redraws: 4 per second instead of 20. */
    static final long INTERVAL_TICKS = 5;

    /** Whether to keep the current texture; a clock behind the last redraw means a different world, so it redraws. */
    public static boolean skipRedraw(boolean textureExists, long now, long lastRedraw) {
        long since = now - lastRedraw;
        return textureExists && since >= 0 && since < INTERVAL_TICKS;
    }
}
