package com.miuix.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;

import com.miuix.glass.MiuixGlass;
import com.miuix.material.MiuixMaterial;

/**
 * miuix-ui：基础卡片 / Surface。
 *
 * <p>上游里 {@code miuix-ui} 是基础组件层，{@code miuix-glass} 提供玻璃表面。
 * 这里让 Card 直接建立在 {@link MiuixGlass} 之上：所有卡片默认就是一块清透
 * 液态玻璃（磨砂底 + 折射 + 镜面 + 超椭圆棱边），而不是旧的纯色圆角矩形。</p>
 *
 * <p>为兼容既有调用，保留 {@link #fill(int)} / {@link #stroke(int, float)} /
 * {@link #radiusDp(float)}：{@code fill} 现在会把颜色转成玻璃染色，
 * {@code stroke} 转成玻璃棱边颜色。</p>
 */
public class MiuixCard extends MiuixGlass {

    public MiuixCard(Context c) {
        super(c);
        material(MiuixMaterial.Preset.CLEAR);
    }

    /** 圆角（dp）——委托给玻璃的超椭圆半径。 */
    @Override
    public MiuixCard radiusDp(float dp) {
        super.radiusDp(dp);
        return this;
    }

    /** 卡面染色：把纯色转成上下一致的半透明玻璃色。 */
    public MiuixCard fill(int color) {
        int top = MiuixMaterial.withAlpha(color, 0xEC);
        int bottom = MiuixMaterial.withAlpha(MiuixMaterial.shade(color, -0.03f), 0xDA);
        tint(top, bottom);
        return this;
    }

    /** 描边 → 玻璃棱边。 */
    public MiuixCard stroke(int color, float widthDp) {
        rim(MiuixMaterial.withAlpha(0xFFFFFF, 0xB3),
                MiuixMaterial.withAlpha(color, 0x40));
        material().thicknessDp = Math.max(0.8f, widthDp);
        invalidate();
        return this;
    }

    @Override
    public MiuixCard paddingDp(int l, int t, int r, int b) {
        super.paddingDp(l, t, r, b);
        return this;
    }

    /** 生成一个默认液态玻璃卡片。 */
    public static MiuixCard create(Context c) {
        MiuixCard card = new MiuixCard(c);
        card.fill(MiuixTheme.surface());
        card.stroke(MiuixTheme.outline(), 0.8f);
        card.paddingDp(MiuixTheme.SPACE_CARD, MiuixTheme.SPACE_CARD,
                MiuixTheme.SPACE_CARD, MiuixTheme.SPACE_CARD);
        return card;
    }

    /** 无棱边的纯色玻璃块（用于分组内部）。 */
    public static MiuixCard plain(Context c, int color, float radiusDp) {
        MiuixCard card = new MiuixCard(c);
        card.fill(color);
        card.radiusDp(radiusDp);
        card.rimEnabled(false);
        card.specular(false);
        return card;
    }

    /** 旧代码兼容：把 GradientDrawable 背景转成超椭圆玻璃填充。 */
    @SuppressWarnings("deprecation")
    public void setSquircleBackground(int color, float radiusDp) {
        fill(color);
        radiusDp(radiusDp);
        setBackgroundDrawable(null);
    }
}
