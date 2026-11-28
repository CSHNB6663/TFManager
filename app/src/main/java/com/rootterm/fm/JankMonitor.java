package com.rootterm.fm;

import android.view.Choreographer;

/**
 * 掉帧监测：统计每帧间隔，把慢帧汇总写进日志。
 *
 * <p>只在出现明显卡顿时才写一条（8 秒窗口），平时几乎零开销。
 * 排查「不丝滑」这类主观问题时，日志里的
 * {@code [JANK] frames=.. slow=.. max=..ms} 能说明到底是掉帧还是观感问题。</p>
 */
public final class JankMonitor {

    /** 超过这个间隔算一次慢帧（60Hz 理论 16.7ms，留点余量）。 */
    private static final long SLOW_MS = 20L;
    /** 汇总周期。 */
    private static final long WINDOW_MS = 8000L;

    private static long sLastFrame;
    private static long sWindowStart;
    private static int sFrames;
    private static int sSlow;
    private static long sMaxGap;
    private static boolean sRunning;

    private JankMonitor() {
    }

    public static void start() {
        if (sRunning) {
            return;
        }
        sRunning = true;
        sWindowStart = System.currentTimeMillis();
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                onFrame(frameTimeNanos / 1000000L);
                Choreographer.getInstance().postFrameCallback(this);
            }
        });
    }

    private static void onFrame(long nowMs) {
        if (sLastFrame != 0) {
            long gap = nowMs - sLastFrame;
            sFrames++;
            if (gap > SLOW_MS) {
                sSlow++;
                if (gap > sMaxGap) {
                    sMaxGap = gap;
                }
            }
        }
        sLastFrame = nowMs;

        long wall = System.currentTimeMillis();
        if (wall - sWindowStart >= WINDOW_MS) {
            if (sSlow > 0) {
                AppLog.log("JANK", "frames=" + sFrames + " slow=" + sSlow
                        + " max=" + sMaxGap + "ms");
            }
            sWindowStart = wall;
            sFrames = 0;
            sSlow = 0;
            sMaxGap = 0;
        }
    }
}
