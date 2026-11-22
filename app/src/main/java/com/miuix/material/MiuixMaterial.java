package com.miuix.material;

import android.content.Context;

/**
 * miuix-material：渲染原语与材质定义。
 *
 * <p>对应上游 {@code miuix-material} 模块：它不直接画 UI，而是定义「液态玻璃」
 * 这一种材质的物理参数——折射、色散、厚度、高光与边缘。所有玻璃组件
 * （{@code miuix-glass} / {@code miuix-nav} / {@code miuix-window} /
 * {@code miuix-glass-icons}）都从这里取参数，保证同一种玻璃在不同组件上
 * 看起来是同一块材质。</p>
 *
 * <p>调参入口是 {@link #preset(Context, Preset)}，默认 {@link Preset#LIQUID}。</p>
 */
public final class MiuixMaterial {

    /** 预置材质：清透、液态（默认）、强调色。 */
    public enum Preset {
        /** 近乎无色，适合普通卡片。 */
        CLEAR,
        /** 液态玻璃：更强的镜面光带与边缘折射（默认）。 */
        LIQUID,
        /** 主色染色玻璃，用于选中/强调态。 */
        ACCENT
    }

    /** 玻璃染色（上下两端，ARGB）。 */
    public int tintTop;
    public int tintBottom;
    /** 棱边高光（左上亮 / 右下暗）。 */
    public int rimLight;
    public int rimDark;
    /** 镜面反光色。 */
    public int specular;
    /** 内部折射色带（液态玻璃的「液体」层次）。 */
    public int refraction;
    /** 玻璃厚度：影响棱边宽度与反光范围。 */
    public float thicknessDp;
    /** 超椭圆圆角（dp）。 */
    public float radiusDp;
    /** 镜面光带高度占比（0~1）。 */
    public float specularHeight;
    /** 是否绘制棱边。 */
    public boolean rimEnabled;
    /** 是否绘制镜面光带。 */
    public boolean specularEnabled;
    /** 是否自动抓取背后内容做磨砂底。 */
    public boolean backdropEnabled;

    private static Preset sPreset = Preset.LIQUID;

    private MiuixMaterial() {
    }

    /** 当前全局预置。 */
    public static Preset preset() {
        return sPreset;
    }

    /** 切换全局材质预置。 */
    public static void setPreset(Preset p) {
        sPreset = p == null ? Preset.LIQUID : p;
    }

    /** 取当前预置的一份参数副本（可单独微调）。 */
    public static MiuixMaterial preset(Context c, Preset p) {
        MiuixMaterial m = new MiuixMaterial();
        Preset q = p == null ? sPreset : p;
        switch (q) {
            case CLEAR:
                m.tintTop = 0xEBFFFFFF;
                m.tintBottom = 0xD9FFFFFF;
                m.rimLight = 0x8CFFFFFF;
                m.rimDark = 0x14000000;
                m.specular = 0x24FFFFFF;
                m.refraction = 0x10FFFFFF;
                m.thicknessDp = 0.8f;
                m.radiusDp = 20f;
                m.specularHeight = 0.45f;
                break;
            case ACCENT:
                int a = com.miuix.ui.MiuixTheme.primary();
                m.tintTop = withAlpha(a, 0xEC);
                m.tintBottom = withAlpha(a, 0xC8);
                m.rimLight = 0x99FFFFFF;
                m.rimDark = 0x26000000;
                m.specular = 0x3DFFFFFF;
                m.refraction = 0x2BFFFFFF;
                m.thicknessDp = 1.2f;
                m.radiusDp = 20f;
                m.specularHeight = 0.60f;
                break;
            case LIQUID:
            default:
                m.tintTop = 0xD8FFFFFF;
                m.tintBottom = 0xC4F2FFFF;
                m.rimLight = 0xB3FFFFFF;
                m.rimDark = 0x1F0B4A9E;
                m.specular = 0x59FFFFFF;
                m.refraction = 0x1F8FC4FF;
                m.thicknessDp = 1.4f;
                m.radiusDp = 20f;
                m.specularHeight = 0.68f;
                break;
        }
        m.rimEnabled = true;
        m.specularEnabled = true;
        m.backdropEnabled = true;
        return m;
    }

    /** 用一块已有材质覆盖参数。 */
    public static MiuixMaterial copy(MiuixMaterial src) {
        MiuixMaterial m = new MiuixMaterial();
        m.tintTop = src.tintTop;
        m.tintBottom = src.tintBottom;
        m.rimLight = src.rimLight;
        m.rimDark = src.rimDark;
        m.specular = src.specular;
        m.refraction = src.refraction;
        m.thicknessDp = src.thicknessDp;
        m.radiusDp = src.radiusDp;
        m.specularHeight = src.specularHeight;
        m.rimEnabled = src.rimEnabled;
        m.specularEnabled = src.specularEnabled;
        m.backdropEnabled = src.backdropEnabled;
        return m;
    }

    /** 把颜色换成指定 alpha。 */
    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** 把颜色调亮/调暗（ratio>0 变亮，<0 变暗）。 */
    public static int shade(int color, float ratio) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int d = (int) (255 * ratio);
        r = clamp(r + d);
        g = clamp(g + d);
        b = clamp(b + d);
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
