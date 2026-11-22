package com.miuix.glass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;
import android.widget.FrameLayout;

import com.miuix.blur.MiuixBlur;
import com.miuix.squircle.Squircle;
import com.miuix.ui.MiuixTheme;

/**
 * miuix-glass：液态玻璃面板。
 *
 * <p>分层模拟 Miuix 的 glass 材质：</p>
 * <ol>
 *   <li><b>磨砂底</b>：可选，把背后内容截图降采样模糊后铺底（{@link #setBackdrop(Bitmap)}）；</li>
 *   <li><b>半透明染色</b>：竖向渐变，营造厚度；</li>
 *   <li><b>高光边</b>：左上亮、右下暗的 1px 渐变描边（玻璃棱）；</li>
 *   <li><b>镜面反光</b>：顶部斜向白色渐变（液态玻璃特有的光带）；</li>
 *   <li><b>超椭圆裁剪</b>：连续圆角。</li>
 * </ol>
 */
public class MiuixGlass extends FrameLayout {

    private final Paint mTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSpecPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private Path mPath;
    private float mRadiusPx;

    private int mTintTop = 0xF2FFFFFF;
    private int mTintBottom = 0xCCFFFFFF;
    private int mRimLight = 0x99FFFFFF;
    private int mRimDark = 0x1A000000;
    private int mSpecColor = 0x2EFFFFFF;

    private Bitmap mBackdrop;
    private final Rect mSrc = new Rect();
    private boolean mShowSpecular = false;
    private boolean mShowRim = true;

    public MiuixGlass(Context c) {
        super(c);
        setWillNotDraw(false);
        mRadiusPx = MiuixTheme.dp(c, MiuixTheme.RADIUS_CARD);
        mRimPaint.setStyle(Paint.Style.STROKE);
        Squircle.clipChildren(this, mRadiusPx);
    }

    // ---------------------------------------------------------------- 配置

    public MiuixGlass radiusDp(float dp) {
        mRadiusPx = MiuixTheme.dp(getContext(), dp);
        Squircle.clipChildren(this, mRadiusPx);
        invalidate();
        return this;
    }

    /** 玻璃染色（上、下两个颜色）。 */
    public MiuixGlass tint(int top, int bottom) {
        mTintTop = top;
        mTintBottom = bottom;
        updateShaders();
        invalidate();
        return this;
    }

    /** 强调色玻璃（主题色半透明）。 */
    public MiuixGlass accentTint() {
        int a = MiuixTheme.primary();
        mTintTop = withAlpha(a, 0xF0);
        mTintBottom = withAlpha(a, 0xC0);
        mRimLight = 0x80FFFFFF;
        mRimDark = 0x22000000;
        updateShaders();
        invalidate();
        return this;
    }

    public MiuixGlass rim(int light, int dark) {
        mRimLight = light;
        mRimDark = dark;
        invalidate();
        return this;
    }

    public MiuixGlass specular(boolean on) {
        mShowSpecular = on;
        invalidate();
        return this;
    }

    public MiuixGlass rimEnabled(boolean on) {
        mShowRim = on;
        invalidate();
        return this;
    }

    public MiuixGlass paddingDp(int l, int t, int r, int b) {
        setPadding(MiuixTheme.dp(getContext(), l), MiuixTheme.dp(getContext(), t),
                MiuixTheme.dp(getContext(), r), MiuixTheme.dp(getContext(), b));
        return this;
    }

    /** 设置磨砂背景（由 {@link MiuixBlur#backdrop} 生成）。 */
    public void setBackdrop(Bitmap bmp) {
        if (mBackdrop != null && mBackdrop != bmp && !mBackdrop.isRecycled()) {
            mBackdrop.recycle();
        }
        mBackdrop = bmp;
        invalidate();
    }

    /** 抓取指定 View 的内容做磨砂底。 */
    public void captureBackdrop(final android.view.View src) {
        if (src == null) {
            return;
        }
        src.post(new Runnable() {
            @Override
            public void run() {
                Bitmap b = MiuixBlur.backdrop(src, 10f);
                if (b != null) {
                    setBackdrop(b);
                }
            }
        });
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        mPath = Squircle.path(w, h, mRadiusPx);
        updateShaders();
        invalidateOutline();
    }

    /** 重建渐变着色器（尺寸或颜色变化时调用）。 */
    private void updateShaders() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        mTintPaint.setShader(new LinearGradient(0, 0, 0, h, mTintTop, mTintBottom,
                Shader.TileMode.CLAMP));
        mSpecPaint.setShader(new LinearGradient(0, 0, w * 0.9f, h * 0.55f,
                mSpecColor, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (mPath == null) {
            mPath = Squircle.path(w, h, mRadiusPx);
        }

        // 1) 磨砂底
        if (mBackdrop != null && !mBackdrop.isRecycled()) {
            mSrc.set(0, 0, mBackdrop.getWidth(), mBackdrop.getHeight());
            canvas.drawBitmap(mBackdrop, mSrc, new Rect(0, 0, w, h), mBitmapPaint);
        }

        // 2) 半透明染色
        canvas.drawPath(mPath, mTintPaint);

        // 3) 镜面反光（只在上半部）
        if (mShowSpecular) {
            canvas.save();
            canvas.clipPath(mPath);
            canvas.drawRect(0, 0, w, h * 0.55f, mSpecPaint);
            canvas.restore();
        }

        // 4) 高光边（左上亮 → 右下暗）
        if (mShowRim) {
            float sw = Math.max(1f, MiuixTheme.dp(getContext(), 0.9f));
            mRimPaint.setStrokeWidth(sw);
            mRimPaint.setShader(new LinearGradient(0, 0, w, h,
                    mRimLight, mRimDark, Shader.TileMode.CLAMP));
            canvas.save();
            canvas.translate(sw / 2f, sw / 2f);
            canvas.drawPath(Squircle.path(w - sw, h - sw, Math.max(1f, mRadiusPx - sw / 2f)),
                    mRimPaint);
            canvas.restore();
        }

        super.onDraw(canvas);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    // ---------------------------------------------------------------- 便捷构造

    /** 标准玻璃面板。 */
    public static MiuixGlass create(Context c) {
        MiuixGlass g = new MiuixGlass(c);
        g.paddingDp(MiuixTheme.SPACE_CARD, MiuixTheme.SPACE_CARD,
                MiuixTheme.SPACE_CARD, MiuixTheme.SPACE_CARD);
        return g;
    }

    /** 强调色玻璃（主色半透明）。 */
    public static MiuixGlass accent(Context c) {
        MiuixGlass g = new MiuixGlass(c);
        g.accentTint();
        return g;
    }

    /** 胶囊玻璃（导航条用）。 */
    public static MiuixGlass pill(Context c) {
        MiuixGlass g = new MiuixGlass(c);
        g.radiusDp(MiuixTheme.RADIUS_PILL);
        g.tint(0xF7FFFFFF, 0xE6FFFFFF);
        g.specular(true);
        return g;
    }
}