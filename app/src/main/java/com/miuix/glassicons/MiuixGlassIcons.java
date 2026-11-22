package com.miuix.glassicons;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.miuix.glass.MiuixGlass;
import com.miuix.icons.MiuixIcons;
import com.miuix.material.MiuixMaterial;
import com.miuix.ui.MiuixTheme;

/**
 * miuix-glass-icons：玻璃底图标。
 *
 * <p>上游把图标拆成两套：{@code miuix-icons}（纯线条图标）与
 * {@code miuix-glass-icons}（图标嵌在一块液态玻璃方块里）。本模块只负责后者，
 * 图标本体仍交给 {@link MiuixIcons#icon} 生成，玻璃底用 {@link MiuixGlass}。</p>
 */
public final class MiuixGlassIcons {

    private MiuixGlassIcons() {
    }

    /**
     * 玻璃图标块（默认强调色玻璃）。
     *
     * @param res        图标资源 id
     * @param tint       图标着色（0 = 不着色）
     * @param tileSizeDp 玻璃块边长
     * @param iconSizeDp 图标边长
     */
    public static MiuixGlass tile(Context c, int res, int tint,
                                  int tileSizeDp, int iconSizeDp) {
        return tile(c, res, tint, tileSizeDp, iconSizeDp, MiuixMaterial.Preset.ACCENT);
    }

    /** 指定材质预置的玻璃图标块。 */
    public static MiuixGlass tile(Context c, int res, int tint,
                                  int tileSizeDp, int iconSizeDp,
                                  MiuixMaterial.Preset preset) {
        MiuixGlass glass = new MiuixGlass(c);
        glass.material(preset);
        glass.radiusDp(tileSizeDp / 3.4f);
        glass.specular(true);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                MiuixTheme.dp(c, tileSizeDp), MiuixTheme.dp(c, tileSizeDp));
        glass.setLayoutParams(glp);

        int iconColor = tint != 0 ? tint
                : (preset == MiuixMaterial.Preset.ACCENT
                    ? MiuixTheme.onPrimary() : MiuixTheme.primary());
        ImageView iv = MiuixIcons.icon(c, res, iconColor, iconSizeDp);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(
                MiuixTheme.dp(c, iconSizeDp), MiuixTheme.dp(c, iconSizeDp));
        ip.gravity = Gravity.CENTER;
        glass.addView(iv, ip);
        return glass;
    }

    /** 清透玻璃图标块（普通信息行用）。 */
    public static MiuixGlass clearTile(Context c, int res, int iconTint,
                                       int tileSizeDp, int iconSizeDp) {
        return tile(c, res, iconTint, tileSizeDp, iconSizeDp, MiuixMaterial.Preset.CLEAR);
    }

    /** 玻璃胶囊图标（带文字，用于「关于」页一类头部）。 */
    public static MiuixGlass badge(Context c, int res, int tint, int sizeDp, int iconSizeDp) {
        return tile(c, res, tint, sizeDp, iconSizeDp, MiuixMaterial.Preset.ACCENT);
    }
}
