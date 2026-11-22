package com.miuix.icons;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.miuix.squircle.Squircle;
import com.miuix.ui.MiuixTheme;

/**
 * miuix-icons / miuix-glass-icons：图标。
 *
 * <p>Miuix 有两套图标：线条图标（miuix-icons）与玻璃底图标（miuix-glass-icons，
 * 图标放在半透明圆角玻璃块里）。这里提供两者的构建工具，图标资源复用工程内的矢量图。</p>
 */
public final class MiuixIcons {

    private MiuixIcons() {
    }

    /** 纯图标。 */
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

    /** 玻璃底图标块（miuix-glass-icons 风格）。 */
    public static FrameLayout glassTile(Context c, int res, int tint,
                                        int tileSizeDp, int iconSizeDp, int bgColor) {
        FrameLayout tile = new FrameLayout(c);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                MiuixTheme.dp(c, tileSizeDp), MiuixTheme.dp(c, tileSizeDp));
        tile.setLayoutParams(lp);
        tile.setBackground(squircleBg(bgColor, tileSizeDp / 4f));
        Squircle.clipChildren(tile, MiuixTheme.dp(c, tileSizeDp / 4f));

        ImageView iv = icon(c, res, tint, iconSizeDp);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(
                MiuixTheme.dp(c, iconSizeDp), MiuixTheme.dp(c, iconSizeDp));
        ip.gravity = Gravity.CENTER;
        tile.addView(iv, ip);
        return tile;
    }

    /** 生成超椭圆纯色背景。 */
    public static android.graphics.drawable.GradientDrawable squircleBg(int color, float radiusDp) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * 3f);
        return g;
    }
}