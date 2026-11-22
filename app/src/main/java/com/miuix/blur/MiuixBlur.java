package com.miuix.blur;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.view.View;
import android.view.Window;

/**
 * miuix-blur：模糊。
 *
 * <p>两种能力：</p>
 * <ul>
 *   <li>{@link #applyWindowBlur(Window, int)}：Android 12+ 系统级窗口背景模糊（用于对话框，开销低）；</li>
 *   <li>{@link #backdrop(View, float)}：把某个 View 的内容截成缩小位图再放大，得到廉价的
 *       「磨砂背景」，用于 App 内的玻璃面板（全版本可用）。</li>
 * </ul>
 */
public final class MiuixBlur {

    private MiuixBlur() {
    }

    /** 系统窗口模糊（API 31+）。返回是否生效。 */
    public static boolean applyWindowBlur(Window window, int radiusPx) {
        if (window == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false;
        }
        try {
            window.setBackgroundBlurRadius(Math.max(1, radiusPx));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否支持真实模糊。 */
    public static boolean supportsRealBlur() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /**
     * 抓取 View 当前内容并做「降采样 + 升采样」模糊。
     *
     * @param src       要抓取的 View（通常是页面内容根）
     * @param downscale 降采样倍数，8~16 观感较好；越大越糊也越快
     * @return 模糊后的位图；失败返回 null
     */
    public static Bitmap backdrop(View src, float downscale) {
        if (src == null) {
            return null;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        float scale = downscale <= 1f ? 8f : downscale;
        int sw = Math.max(1, (int) (w / scale));
        int sh = Math.max(1, (int) (h / scale));
        Bitmap small = null;
        Bitmap out = null;
        try {
            small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(small);
            c.scale(1f / scale, 1f / scale);
            src.draw(c);

            out = Bitmap.createScaledBitmap(small, w, h, true);
            return out;
        } catch (Throwable t) {
            if (out != null) {
                out.recycle();
            }
            return null;
        } finally {
            if (small != null && small != out && !small.isRecycled()) {
                small.recycle();
            }
        }
    }

    /** 抓取整个 Activity 内容（不含状态栏）并模糊。 */
    public static Bitmap backdropOfActivity(Activity a, float downscale) {
        if (a == null) {
            return null;
        }
        View decor = a.getWindow() == null ? null : a.getWindow().getDecorView();
        return backdrop(decor, downscale);
    }

    /**
     * 给对话框窗口启用模糊 + 半透明背景（API 31+ 生效，低版本仅半透明）。
     */
    public static void decorateDialog(Window window, float dimAmount) {
        if (window == null) {
            return;
        }
        try {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams lp = window.getAttributes();
            lp.dimAmount = dimAmount;
            window.setAttributes(lp);
        } catch (Throwable ignored) {
        }
        applyWindowBlur(window, dp(window.getContext(), 22));
    }

    private static int dp(android.content.Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}