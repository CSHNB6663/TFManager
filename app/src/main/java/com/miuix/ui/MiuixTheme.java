package com.miuix.ui;

import android.content.Context;
import android.graphics.Typeface;

import com.rootterm.fm.ThemeColors;

/**
 * miuix-ui：主题（颜色 / 字号 / 圆角 / 间距）。
 *
 * <p>Miuix 用 Compose 的 {@code MiuixTheme} 提供语义色。这里用静态字段承载同一套语义，
 * 颜色来自 Monet 动态取色（{@link ThemeColors}），保证跟随壁纸。</p>
 */
public final class MiuixTheme {

    // ---------------------------------------------------------------- 语义色

    /** 页面背景。 */
    public static int background() {
        return ThemeColors.SURFACE;
    }

    /** 卡片表面。 */
    public static int surface() {
        return ThemeColors.CARD;
    }

    /** 次级表面（分组内嵌）。 */
    public static int surfaceVariant() {
        return 0xFFF2F3F5;
    }

    /** 强调色。 */
    public static int primary() {
        return ThemeColors.ACCENT;
    }

    /** 强调色上的文字。 */
    public static int onPrimary() {
        return ThemeColors.ON_ACCENT;
    }

    /** 强调容器色（浅底）。 */
    public static int primaryContainer() {
        return ThemeColors.ACCENT_CONTAINER;
    }

    /** 主文字。 */
    public static int onSurface() {
        return 0xFF191919;
    }

    /** 次文字。 */
    public static int onSurfaceVariant() {
        return 0xFF8A8F98;
    }

    /** 分隔线。 */
    public static int outline() {
        return 0xFFF0F1F3;
    }

    /** 成功。 */
    public static int success() {
        return 0xFF1FA35B;
    }

    /** 成功浅底。 */
    public static int successContainer() {
        return 0xFFE7F7EC;
    }

    /** 错误。 */
    public static int error() {
        return 0xFFE8481F;
    }

    // ---------------------------------------------------------------- 形状

    /** 大卡片圆角（dp）。 */
    public static final float RADIUS_CARD = 20f;
    /** 中卡片 / 内嵌块圆角（dp）。 */
    public static final float RADIUS_MEDIUM = 16f;
    /** 小控件圆角（dp）。 */
    public static final float RADIUS_SMALL = 12f;
    /** 胶囊圆角（dp）。 */
    public static final float RADIUS_PILL = 30f;

    // ---------------------------------------------------------------- 字号

    public static final float TEXT_TITLE = 30f;
    public static final float TEXT_HEADLINE = 22f;
    public static final float TEXT_BODY = 15f;
    public static final float TEXT_LABEL = 13f;
    public static final float TEXT_CAPTION = 11f;

    // ---------------------------------------------------------------- 间距

    public static final int SPACE_PAGE = 20;
    public static final int SPACE_CARD = 14;
    public static final int SPACE_ROW_V = 14;
    public static final int SPACE_ROW_H = 16;

    // ---------------------------------------------------------------- 工具

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static int dp(Context c, int v) {
        return dp(c, (float) v);
    }

    public static float sp(Context c, float v) {
        return v;
    }

    public static Typeface bold() {
        return Typeface.DEFAULT_BOLD;
    }

    public static Typeface normal() {
        return Typeface.DEFAULT;
    }

    private MiuixTheme() {
    }
}