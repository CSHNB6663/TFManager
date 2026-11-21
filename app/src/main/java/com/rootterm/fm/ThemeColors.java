package com.rootterm.fm;

import android.content.Context;
import android.os.Build;
import android.util.TypedValue;

/**
 * 主题色解析：采用 KernelSU-Style-UI-Kit 的「Miuix + Monet 动态取色」思路。
 *
 * <p>Android 12（API 31）及以上读取系统的 Monet 调色板
 * （{@code android:color/system_accent1_*}、{@code system_neutral1_*}、{@code system_neutral2_*}），
 * 跟随壁纸/系统主题取色；更低版本回落到 MIUIX 默认蓝 {@code #3482FF}。</p>
 *
 * <p>本 App 是纯 framework Java（不含 Compose/AAR），无法直接引入该 UI Kit 的组件代码，
 * 因此把它的设计令牌（动态主色、容器色、表面色、圆角）映射成了下面这些值。</p>
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
    /** 卡片底色。 */
    public static int CARD = 0xFFFFFFFF;

    private ThemeColors() {
    }

    /** 在 Activity 创建时调用一次；上下文用于读取系统 Monet 资源。 */
    public static void init(Context ctx) {
        int accent = 0;
        int container = 0;
        int surface = 0;
        if (Build.VERSION.SDK_INT >= 31) {
            accent = resolve(ctx, "system_accent1_600", 0);
            container = resolve(ctx, "system_accent1_100", 0);
            surface = resolve(ctx, "system_neutral1_50", 0);
        }
        if (accent == 0) {
            accent = 0xFF3482FF;
        }
        if (container == 0) {
            container = 0xFFE8F0FF;
        }
        if (surface == 0) {
            surface = 0xFFF7F8FA;
        }
        ACCENT = accent;
        ACCENT_CONTAINER = blend(container, 0xFFFFFFFF, 0.35f);
        SURFACE = surface;
        CARD = 0xFFFFFFFF;
        ON_ACCENT = luminance(ACCENT) > 0.6 ? 0xFF191919 : 0xFFFFFFFF;
        ON_ACCENT_CONTAINER = luminance(ACCENT) > 0.6 ? 0xFF3A2A00 : 0xFF0B4A9E;
    }

    private static int resolve(Context ctx, String name, int fallback) {
        try {
            int id = ctx.getResources().getIdentifier(name, "color", "android");
            if (id == 0) {
                return fallback;
            }
            TypedValue tv = new TypedValue();
            ctx.getResources().getValue(id, tv, true);
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
            return ctx.getResources().getColor(id, null);
        } catch (Throwable t) {
            return fallback;
        }
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
