package com.rootterm.fm;

import android.content.Context;
import android.os.Build;
import android.util.TypedValue;

/**
 * 主题色：固定玻璃配色（已移除 Monet 动态取色）。
 *
 * <p>不再读取系统 {@code system_accent1_*}，避免换壁纸后界面配色跳变；
 * 统一使用固定的玻璃蓝调，配合 {@link #pageBackground()} 的浅色渐变，
 * 让半透明玻璃卡片有可读的层次。</p>
 */
public final class ThemeColors {

    /** 主色（强调色）。 */
    public static int ACCENT = 0xFF3482FF;
    /** 主色容器（选中项浅底）。 */
    public static int ACCENT_CONTAINER = 0xFFE8F0FF;
    /** 主色上的文字色。 */
    public static int ON_ACCENT = 0xFFFFFFFF;
    /** 主色容器上的文字色（深色，便于浅底）。 */
    public static int ON_ACCENT_CONTAINER = 0xFF0B4A9E;
    /** 页面底色。 */
    public static int SURFACE = 0xFFF7F8FA;
    /** 卡片底色（半透明白，叠在渐变页面上呈玻璃感）。 */
    public static int CARD = 0xF2FFFFFF;
    /** 页面渐变顶部色。 */
    public static int BG_TOP = 0xFFF1F5FC;
    /** 页面渐变底部色。 */
    public static int BG_BOTTOM = 0xFFE6EDF9;

    private ThemeColors() {
    }

    /** 在 Activity 创建时调用一次（固定配色，不再读系统 Monet）。 */
    public static void init(Context ctx) {
        ACCENT = 0xFF3482FF;
        ACCENT_CONTAINER = 0xFFE8F0FF;
        ON_ACCENT = 0xFFFFFFFF;
        ON_ACCENT_CONTAINER = 0xFF0B4A9E;
        SURFACE = 0xFFF1F5FC;
        CARD = 0xF2FFFFFF;
        BG_TOP = 0xFFF1F5FC;
        BG_BOTTOM = 0xFFE6EDF9;
    }

    /** 页面背景：竖向浅色渐变，让玻璃卡片有层次。 */
    public static android.graphics.drawable.GradientDrawable pageBackground() {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{BG_TOP, BG_BOTTOM});
        return g;
    }

    /** 颜色混合：ratio 为 0 时取 a，为 1 时取 b。 */
    public static int blend(int a, int b, float ratio) {
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * ratio);
        int g = Math.round(ag + (bg - ag) * ratio);
        int bl = Math.round(ab + (bb - ab) * ratio);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** 相对亮度，用于决定前景用深色还是浅色。 */
    public static float luminance(int color) {
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        r = r <= 0.03928f ? r / 12.92f : (float) Math.pow((r + 0.055) / 1.055, 2.4);
        g = g <= 0.03928f ? g / 12.92f : (float) Math.pow((g + 0.055) / 1.055, 2.4);
        b = b <= 0.03928f ? b / 12.92f : (float) Math.pow((b + 0.055) / 1.055, 2.4);
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }
}
