package com.miuix.blur;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.view.View;
import android.view.Window;

/**
 * miuix-blur：模糊。
 *
 * <p>上游 {@code miuix-blur} 是可独立使用的模糊库。本实现提供三层能力：</p>
 * <ul>
 *   <li>{@link #applyWindowBlur}：Android 12+ 系统窗口背景模糊（对话框用，开销最低）；</li>
 *   <li>{@link #backdrop}：把 View 内容截成小图，做「降采样 → 盒式模糊 →
 *       升采样 → 轻微提亮」，得到全版本可用的磨砂底；</li>
 *   <li>{@link #backdropOfActivity}：抓整个 Activity 的 decorView。</li>
 * </ul>
 *
 * <p>玻璃组件 {@code miuix-glass} 的磨砂底就来自这里；模糊半径由
 * 降采样倍数与盒式迭代次数共同决定。</p>
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
     * 抓取 View 当前内容并做磨砂模糊。
     *
     * @param src       要抓取的 View（通常是页面内容根）
     * @param downscale 降采样倍数，8~16 观感较好；越大越糊也越快
     * @return 模糊后的位图；失败返回 null
     */
    public static Bitmap backdrop(View src, float downscale) {
        return backdrop(src, downscale, 2);
    }

    /**
     * 抓取 View 内容并模糊。
     *
     * @param passes 盒式模糊迭代次数（1~3，越大越柔和）
     */
    public static Bitmap backdrop(View src, float downscale, int passes) {
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

            int iters = Math.max(0, Math.min(3, passes));
            if (iters > 0 && sw * sh <= 260000) {
                int[] px = new int[sw * sh];
                small.getPixels(px, 0, sw, 0, 0, sw, sh);
                for (int i = 0; i < iters; i++) {
                    boxBlur(px, sw, sh, 1);
                }
                small.setPixels(px, 0, sw, 0, 0, sw, sh);
            }

            out = Bitmap.createScaledBitmap(small, w, h, true);
            // 轻微提亮：模拟光线穿过磨砂玻璃后的散射
            if (out != null) {
                Canvas oc = new Canvas(out);
                Paint p = new Paint();
                p.setColor(Color.argb(18, 255, 255, 255));
                oc.drawRect(0, 0, w, h, p);
            }
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

    /**
     * 从整张模糊图里裁出指定区域（用于把「胶囊背后的那块」做成磨砂）。
     * 越界会自动收敛到图内。
     */
    public static Bitmap cropRegion(Bitmap full, int left, int top, int w, int h) {
        if (full == null || full.isRecycled() || w <= 0 || h <= 0) {
            return null;
        }
        int fl = Math.max(0, Math.min(left, full.getWidth() - 1));
        int ft = Math.max(0, Math.min(top, full.getHeight() - 1));
        int fw = Math.min(w, full.getWidth() - fl);
        int fh = Math.min(h, full.getHeight() - ft);
        if (fw <= 0 || fh <= 0) {
            return null;
        }
        try {
            return Bitmap.createBitmap(full, fl, ft, fw, fh);
        } catch (Throwable t) {
            return null;
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

    /** 给对话框窗口启用模糊 + 半透明背景（API 31+ 生效，低版本仅半透明）。 */
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

    /** 盒式模糊：对 ARGB 像素数组做半径 r 的可分离均值滤波。 */
    private static void boxBlur(int[] px, int w, int h, int r) {
        int[] tmp = new int[px.length];
        System.arraycopy(px, 0, tmp, 0, px.length);
        // 横向
        for (int y = 0; y < h; y++) {
            int base = y * w;
            for (int x = 0; x < w; x++) {
                int a = 0, rr = 0, g = 0, b = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int xx = x + k;
                    if (xx < 0 || xx >= w) {
                        continue;
                    }
                    int c = tmp[base + xx];
                    a += (c >>> 24) & 0xFF;
                    rr += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                    n++;
                }
                px[base + x] = (a / n << 24) | (rr / n << 16) | (g / n << 8) | (b / n);
            }
        }
        System.arraycopy(px, 0, tmp, 0, px.length);
        // 纵向
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int a = 0, rr = 0, g = 0, b = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int yy = y + k;
                    if (yy < 0 || yy >= h) {
                        continue;
                    }
                    int c = tmp[yy * w + x];
                    a += (c >>> 24) & 0xFF;
                    rr += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                    n++;
                }
                px[y * w + x] = (a / n << 24) | (rr / n << 16) | (g / n << 8) | (b / n);
            }
        }
    }

    private static int dp(android.content.Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}
