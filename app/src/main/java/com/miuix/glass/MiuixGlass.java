package com.miuix.glass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;
import android.widget.FrameLayout;

import com.miuix.blur.MiuixBlur;
import com.miuix.material.MiuixMaterial;
import com.miuix.squircle.Squircle;
import com.miuix.ui.MiuixTheme;

/**
 * miuix-glass：液态玻璃组件（本工程所有 Miuix 表面的基础）。
 *
 * <p>按上游 {@code miuix-material → miuix-glass} 的分层重写：</p>
 * <ol>
 *   <li><b>磨砂底</b>：{@code miuix-blur} 把背后内容降采样后铺底，可自动跟随宿主重绘；</li>
 *   <li><b>内部折射</b>：一道斜向色带，模拟光穿过玻璃厚度时的色散（液态感的关键）；</li>
 *   <li><b>半透明染色</b>：竖向渐变；</li>
 *   <li><b>镜面反光</b>：顶部斜向白色光带，按材质厚度决定高度；</li>
 *   <li><b>玻璃棱</b>：左上亮、右下暗的超椭圆渐变描边；</li>
 *   <li><b>连续圆角</b>：{@code miuix-squircle} 超椭圆裁剪子内容。</li>
 * </ol>
 *
 * <p>用法：{@code new MiuixGlass(ctx).material(MiuixMaterial.Preset.LIQUID)}，
 * 或 {@link #pill(Context)} / {@link #create(Context)} 等快捷构造。</p>
 */
public class MiuixGlass extends FrameLayout {

    private final Paint mTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSpecPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRefractPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private Path mPath;
    private float mRadiusPx;
    private MiuixMaterial mMaterial;

    private Bitmap mBackdrop;
    private final Rect mSrc = new Rect();
    private final Rect mDst = new Rect();

    /** 自动磨砂底：背后内容变化时重新抓一帧。 */
    private View mBackdropSource;
    private boolean mAutoBackdrop;
    private long mLastCapture;
    private static final long CAPTURE_INTERVAL_MS = 420L;

    public MiuixGlass(Context c) {
        super(c);
        setWillNotDraw(false);
        mMaterial = MiuixMaterial.preset(c, MiuixMaterial.preset());
        mRadiusPx = MiuixTheme.dp(c, mMaterial.radiusDp);
        mRimPaint.setStyle(Paint.Style.STROKE);
        mRefractPaint.setStyle(Paint.Style.FILL);
        applyClip();
        updateShaders();
    }

    // ---------------------------------------------------------------- 形状

    private boolean mCapsule;

    /**
     * 真胶囊形状：两端为正半圆（圆角矩形，半径 = 高度一半）。
     *
     * <p>默认的超椭圆圆角在半径拉满时，两端是「连续曲率的方圆形」，
     * 不是真半圆；要药丸观感就打开这个。</p>
     */
    public MiuixGlass capsule(boolean on) {
        if (mCapsule == on) {
            return this;
        }
        mCapsule = on;
        mPath = null;
        applyClip();
        invalidate();
        return this;
    }

    public boolean isCapsule() {
        return mCapsule;
    }

    /** 按当前形状设置生成路径。 */
    private Path buildPath(int w, int h) {
        return buildPath(w, h, mRadiusPx);
    }

    private Path buildPath(float w, float h, float radiusPx) {
        if (w <= 0 || h <= 0) {
            return new Path();
        }
        if (mCapsule) {
            Path p = new Path();
            float r = Math.min(radiusPx, h / 2f);
            p.addRoundRect(new android.graphics.RectF(0, 0, w, h), r, r, Path.Direction.CW);
            return p;
        }
        return Squircle.path(w, h, radiusPx);
    }

    // ---------------------------------------------------------------- 材质

    /** 切换到预置材质（清透 / 液态 / 强调）。会覆盖之前手动 tint/rim 设置。 */
    public MiuixGlass material(MiuixMaterial.Preset preset) {
        mMaterial = MiuixMaterial.preset(getContext(), preset);
        mRadiusPx = MiuixTheme.dp(getContext(), mMaterial.radiusDp);
        applyClip();
        updateShaders();
        invalidate();
        return this;
    }

    /** 用自定义材质参数。 */
    public MiuixGlass material(MiuixMaterial m) {
        mMaterial = MiuixMaterial.copy(m);
        mRadiusPx = MiuixTheme.dp(getContext(), mMaterial.radiusDp);
        applyClip();
        updateShaders();
        invalidate();
        return this;
    }

    public MiuixMaterial material() {
        return mMaterial;
    }

    // ---------------------------------------------------------------- 配置

    public MiuixGlass radiusDp(float dp) {
        mMaterial.radiusDp = dp;
        mRadiusPx = MiuixTheme.dp(getContext(), dp);
        applyClip();
        mPath = null;
        invalidate();
        return this;
    }

    /** 玻璃染色（上、下两个颜色）。 */
    public MiuixGlass tint(int top, int bottom) {
        mMaterial.tintTop = top;
        mMaterial.tintBottom = bottom;
        updateShaders();
        invalidate();
        return this;
    }

    /** 强调色玻璃（主题色半透明）。等价于 {@code material(ACCENT)}。 */
    public MiuixGlass accentTint() {
        return material(MiuixMaterial.Preset.ACCENT);
    }

    public MiuixGlass rim(int light, int dark) {
        mMaterial.rimLight = light;
        mMaterial.rimDark = dark;
        invalidate();
        return this;
    }

    public MiuixGlass specular(boolean on) {
        mMaterial.specularEnabled = on;
        invalidate();
        return this;
    }

    public MiuixGlass rimEnabled(boolean on) {
        mMaterial.rimEnabled = on;
        invalidate();
        return this;
    }

    /** 折射色带开关（液态层次）。 */
    public MiuixGlass refraction(boolean on) {
        mMaterial.backdropEnabled = on || mMaterial.backdropEnabled;
        mMaterial.refraction = on ? mMaterial.refraction : 0;
        updateShaders();
        invalidate();
        return this;
    }

    public MiuixGlass paddingDp(int l, int t, int r, int b) {
        setPadding(MiuixTheme.dp(getContext(), l), MiuixTheme.dp(getContext(), t),
                MiuixTheme.dp(getContext(), r), MiuixTheme.dp(getContext(), b));
        return this;
    }

    // ---------------------------------------------------------------- 磨砂底

    /** 设置磨砂背景（由 {@link MiuixBlur#backdrop} 生成）。 */
    public void setBackdrop(Bitmap bmp) {
        if (mBackdrop != null && mBackdrop != bmp && !mBackdrop.isRecycled()) {
            mBackdrop.recycle();
        }
        mBackdrop = bmp;
        invalidate();
    }

    /** 只抓「自己背后那块」做磨砂底（空间正确，真正的磨砂玻璃）。 */
    public void captureBackdropRegion(final android.view.View src, final float downscale) {
        if (src == null) {
            return;
        }
        post(new Runnable() {
            @Override
            public void run() {
                try {
                    int[] sLoc = new int[2];
                    int[] mLoc = new int[2];
                    src.getLocationInWindow(sLoc);
                    getLocationInWindow(mLoc);
                    int left = mLoc[0] - sLoc[0];
                    int top = mLoc[1] - sLoc[1];
                    Bitmap full = MiuixBlur.backdrop(src, downscale);
                    if (full == null) {
                        return;
                    }
                    Bitmap crop = MiuixBlur.cropRegion(full, left, top,
                            getWidth(), getHeight());
                    if (crop != null) {
                        setBackdrop(crop);
                    } else {
                        full.recycle();
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 抓取指定 View 的内容做磨砂底。 */
    public void captureBackdrop(final View src) {
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

    /**
     * 自动磨砂：每次绘制前检查背后内容，间隔足够就重抓一帧。
     * 适合导航条这类长期悬浮在其他页面之上的玻璃。
     */
    public void autoBackdrop(View behind) {
        mBackdropSource = behind;
        mAutoBackdrop = behind != null;
        if (mAutoBackdrop) {
            captureBackdrop(behind);
        }
    }

    public void clearBackdrop() {
        mAutoBackdrop = false;
        mBackdropSource = null;
        setBackdrop(null);
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        mPath = buildPath(w, h);
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
        mTintPaint.setShader(new LinearGradient(0, 0, 0, h,
                mMaterial.tintTop, mMaterial.tintBottom, Shader.TileMode.CLAMP));
        mSpecPaint.setShader(new LinearGradient(0, 0, w * 0.95f, h * 0.55f,
                mMaterial.specular, 0x00FFFFFF, Shader.TileMode.CLAMP));
        mRefractPaint.setShader(new LinearGradient(0, h * 0.35f, w, h,
                0x00FFFFFF, mMaterial.refraction, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (mPath == null) {
            mPath = buildPath(w, h);
        }

        maybeCaptureBackdrop();

        // 1) 磨砂底（裁到超椭圆内）
        if (mBackdrop != null && !mBackdrop.isRecycled()) {
            canvas.save();
            canvas.clipPath(mPath);
            mSrc.set(0, 0, mBackdrop.getWidth(), mBackdrop.getHeight());
            mDst.set(0, 0, w, h);
            canvas.drawBitmap(mBackdrop, mSrc, mDst, mBitmapPaint);
            canvas.restore();
        }

        // 2) 半透明染色
        canvas.drawPath(mPath, mTintPaint);

        canvas.save();
        canvas.clipPath(mPath);

        // 3) 内部折射色带
        if (mMaterial.refraction != 0) {
            canvas.drawRect(0, 0, w, h, mRefractPaint);
        }

        // 4) 镜面反光（按材质厚度决定光带高度）
        if (mMaterial.specularEnabled) {
            float sh = h * mMaterial.specularHeight;
            canvas.drawRect(0, 0, w, sh, mSpecPaint);
            // 顶部一道更亮的高光细线
            mSpecPaint.setAlpha(90);
            canvas.drawRect(0, 0, w, Math.max(1f, MiuixTheme.dp(getContext(), 1.2f)),
                    mSpecPaint);
            mSpecPaint.setAlpha(255);
        }
        canvas.restore();

        // 5) 高光棱边（左上亮 → 右下暗）
        if (mMaterial.rimEnabled) {
            float sw = Math.max(1f,
                    MiuixTheme.dp(getContext(), mMaterial.thicknessDp));
            mRimPaint.setStrokeWidth(sw);
            mRimPaint.setShader(new LinearGradient(0, 0, w, h,
                    mMaterial.rimLight, mMaterial.rimDark, Shader.TileMode.CLAMP));
            canvas.save();
            canvas.translate(sw / 2f, sw / 2f);
            canvas.drawPath(buildPath(w - sw, h - sw, Math.max(1f, mRadiusPx - sw / 2f)),
                    mRimPaint);
            canvas.restore();
        }

        super.onDraw(canvas);
    }

    private void maybeCaptureBackdrop() {
        if (!mAutoBackdrop || mBackdropSource == null) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - mLastCapture < CAPTURE_INTERVAL_MS) {
            return;
        }
        mLastCapture = now;
        final View src = mBackdropSource;
        post(new Runnable() {
            @Override
            public void run() {
                if (mAutoBackdrop && mBackdropSource == src) {
                    Bitmap b = MiuixBlur.backdrop(src, 10f);
                    if (b != null) {
                        setBackdrop(b);
                    }
                }
            }
        });
    }

    private void applyClip() {
        if (!mCapsule) {
            Squircle.clipChildren(this, mRadiusPx);
            return;
        }
        setClipToOutline(true);
        setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline outline) {
                float r = Math.min(mRadiusPx, v.getHeight() / 2f);
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        invalidateOutline();
    }

    // ---------------------------------------------------------------- 便捷构造

    /** 标准液态玻璃面板。 */
    public static MiuixGlass create(Context c) {
        MiuixGlass g = new MiuixGlass(c);
        g.paddingDp(MiuixTheme.SPACE_CARD, MiuixTheme.SPACE_CARD,
                MiuixTheme.SPACE_CARD, MiuixTheme.SPACE_CARD);
        return g;
    }

    /** 清透玻璃（普通卡片、信息容器）。 */
    public static MiuixGlass clear(Context c) {
        return new MiuixGlass(c).material(MiuixMaterial.Preset.CLEAR);
    }

    /** 强调色玻璃（主色半透明）。 */
    public static MiuixGlass accent(Context c) {
        return new MiuixGlass(c).material(MiuixMaterial.Preset.ACCENT);
    }

    /** 胶囊液态玻璃（导航条 / 按钮用）。 */
    public static MiuixGlass pill(Context c) {
        MiuixGlass g = new MiuixGlass(c);
        g.radiusDp(MiuixTheme.RADIUS_PILL);
        g.tint(0xD9FFFFFF, 0xCBE8FFFF);
        g.specular(true);
        return g;
    }
}
