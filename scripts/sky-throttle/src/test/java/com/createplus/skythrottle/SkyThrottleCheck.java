package com.createplus.skythrottle;

/** Run by build.py before packaging; fails the build if the redraw decision is wrong. */
public final class SkyThrottleCheck {
    public static void main(String[] args) {
        check(!SkyThrottle.skipRedraw(false, 100, 100), "the first draw must run");
        check(SkyThrottle.skipRedraw(true, 100, 100), "the same tick is skipped");
        check(SkyThrottle.skipRedraw(true, 104, 100), "a redraw inside the interval is skipped");
        check(!SkyThrottle.skipRedraw(true, 105, 100), "it redraws once the interval passes");
        check(!SkyThrottle.skipRedraw(true, 50, 100), "a world with an earlier clock must redraw");
        System.out.println("SkyThrottle self-check passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
