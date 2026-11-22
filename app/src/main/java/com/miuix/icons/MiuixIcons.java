package com.miuix.icons;

import android.content.Context;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.miuix.glassicons.MiuixGlassIcons;
import com.miuix.ui.MiuixTheme;

/**
 * miuix-icons：纯线条图标。
 *
 * <p>上游把图标分成两个可独立使用的模块：本模块只产出「图标本身」
 * （{@code miuix-icons}）；带玻璃底的图标由 {@code miuix-glass-icons}
 * （{@link MiuixGlassIcons}）负责。{@link #glassTile} 保留为兼容入口，
 * 实际转交给玻璃图标模块。</p>
 */
public final class MiuixIcons {

    private MiuixIcons() {
    }

    /** 纯图标（可着色、可指定尺寸）。 */
    public static ImageView icon(Context c, int res, int tint, int sizeDp) {
        ImageView iv = new ImageView(c);
        if (res != 0) {
            iv.setImageResource(res);
        }
        if (tint != 0) {
            iv.setColorFilter(tint);
        }
        iv.setLayoutParams(new FrameLayout.LayoutParams(
                MiuixTheme.dp(c, sizeDp), MiuixTheme.dp(c, sizeDp)));
        return iv;
    }

    /** 主色图标。 */
    public static ImageView accentIcon(Context c, int res, int sizeDp) {
        return icon(c, res, MiuixTheme.primary(), sizeDp);
    }

    /** 生成超椭圆纯色背景（旧代码兼容）。 */
    public static android.graphics.drawable.GradientDrawable squircleBg(int color,
                                                                       float radiusDp) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * 3f);
        return g;
    }

    /**
     * 兼容入口：玻璃底图标块。
     * 新代码请用 {@link MiuixGlassIcons#tile}。
     */
    public static FrameLayout glassTile(Context c, int res, int tint,
                                        int tileSizeDp, int iconSizeDp, int bgColor) {
        return MiuixGlassIcons.tile(c, res, tint, tileSizeDp, iconSizeDp);
    }
}
