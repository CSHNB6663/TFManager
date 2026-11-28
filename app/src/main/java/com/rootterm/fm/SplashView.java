package com.rootterm.fm;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;

import com.miuix.squircle.Squircle;

/**
 * 首次启动的「激活动画」。
 *
 * <p>观感参考 HyperOS 的激活动画：暗底 + 中心光晕 → 图标带回弹地浮现 →
 * 两圈光环向外扩散 → 一道高光斜扫过图标 → 名称与副标题上浮 → 整体放大淡出。</p>
 *
 * <p>只在装好后第一次启动播放（SharedPreferences 里记一个标记，卸载重装会重置）。</p>
 */
public class SplashView extends View {

    /** 动画结束回调。 */
    public interface OnFinish {
        void onFinish();
    }

    private static final String PREF = "ui";
    private static final String KEY_DONE = "splash_played_v1";

    /** 整段动画时长（毫秒）。 */
    private static final long DURATION = 2000L;

    private static final int BG_TOP = 0xFFFAFAFC;
    private static final int BG_BOTTOM = 0xFFE9ECF2;
        private static final int BRAND = 0xFF3482FF;
    private static final int BRAND_DEEP = 0xFF1B4FCC;
    private static final int TITLE = 0xFF1A1D24;
    private static final int SUBTITLE = 0xFF6B7280;

    private final Paint mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Path mIconPath;
    /** 一次性栅格化的图标（含描边），每帧只 drawBitmap，避免重复裁切与大对象分配。 */
    private android.graphics.Bitmap mIconBmp;
    private Shader mBgShader;
    private Shader mGlowShader;
    private Shader mSweepShader;
    private float mIconSize;
    private float mCx;
    private float mCy;
    /** 标题每个字的宽度与总宽，避免每帧 measureText。 */
    private float[] mCharW;
    private float mTitleW;
    private static final float TITLE_SIZE_RATIO = 0.075f;
    private static final float ICON_SIZE_RATIO = 0.235f;
    private static final Interpolator OVERSHOOT = new OvershootInterpolator(1.7f);
    private final RectF mIconRect = new RectF();

    private float mProgress;
    private boolean mStarted;
    private boolean mFinished;
    private OnFinish mOnFinish;

    private final float mDensity;

    public SplashView(Context c) {
        super(c);
        mDensity = getResources().getDisplayMetrics().density;

        mRingPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setStrokeWidth(dp(1.6f));

        mGlyphPaint.setColor(0xFFFFFFFF);
        mGlyphPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        mGlyphPaint.setTextAlign(Paint.Align.CENTER);

        mTitlePaint.setColor(TITLE);
        mTitlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        mTitlePaint.setTextAlign(Paint.Align.CENTER);

        mSubPaint.setColor(SUBTITLE);
        mSubPaint.setTextAlign(Paint.Align.CENTER);

        // 不做全屏硬件层：内容每帧变化，反复上传纹理反而更慢；
        // 关键开销已通过图标位图化 + 着色器缓存消除
        setLayerType(LAYER_TYPE_NONE, null);
    }

    // ------------------------------------------------------------ 开关

    /** 只有装好后第一次启动才播放。 */
    public static boolean shouldPlay(Context c) {
        return !prefs(c).getBoolean(KEY_DONE, false);
    }

    public static void markPlayed(Context c) {
        prefs(c).edit().putBoolean(KEY_DONE, true).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public void setOnFinish(OnFinish l) {
        mOnFinish = l;
    }

    // ------------------------------------------------------------ 播放

    public void start() {
        if (mStarted) {
            return;
        }
        mStarted = true;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(DURATION);
        a.setInterpolator(new DecelerateInterpolator(1.1f));
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mProgress = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                finish();
            }
        });
        a.start();
    }

    private void finish() {
        if (mFinished) {
            return;
        }
        mFinished = true;
        if (mOnFinish != null) {
            mOnFinish.onFinish();
        }
    }

    // ------------------------------------------------------------ 预计算

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) {
            return;
        }
        mCx = w / 2f;
        mCy = h * 0.44f;
        mIconSize = Math.min(w, h) * ICON_SIZE_RATIO;
        float radius = mIconSize * 0.30f;

        // 背景渐变：只建一次，淡入用 Paint.setAlpha 控制
        mBgShader = new LinearGradient(0, 0, 0, h, BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP);

        // 光晕：以最大半径建一次，缩放画布代替重建
        float glowMax = mIconSize * 2.1f;
        mGlowShader = new RadialGradient(0, 0, glowMax,
                new int[]{withAlpha(BRAND, 58), 0x002A6BE0},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);

        // 高光带：固定尺寸，靠平移画布扫过
        mSweepShader = new LinearGradient(-mIconSize * 0.35f, 0, mIconSize * 0.35f, mIconSize * 0.7f,
                new int[]{0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);

        // 图标路径 + 栅格化位图（只做一次，之后每帧只贴图）
        mIconPath = Squircle.path(mIconSize, mIconSize, radius);
        mIconBmp = rasterizeIcon(mIconSize, radius);

        // 标题字宽缓存
        mTitlePaint.setTextSize(Math.min(w * TITLE_SIZE_RATIO, dp(30f)));
        String title = "TF管理终端";
        mCharW = new float[title.length()];
        float total = 0f;
        for (int i = 0; i < title.length(); i++) {
            mCharW[i] = mTitlePaint.measureText(title.substring(i, i + 1));
            total += mCharW[i];
        }
        mTitleW = total;
    }

    /** 把图标（含深色描边）渲染进一张位图，避免每帧 clipPath + saveLayerAlpha。 */
    private android.graphics.Bitmap rasterizeIcon(float size, float radius) {
        int px = Math.max(1, (int) size);
        try {
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    px, px, android.graphics.Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Drawable appIcon = appIcon();
            if (appIcon != null) {
                Path clip = Squircle.path(size, size, radius);
                c.save();
                c.clipPath(clip);
                appIcon.setBounds(0, 0, px, px);
                appIcon.draw(c);
                c.restore();
            } else {
                mIconPaint.setShader(new LinearGradient(0, 0, size, size,
                        BRAND, BRAND_DEEP, Shader.TileMode.CLAMP));
                c.drawPath(Squircle.path(size, size, radius), mIconPaint);
                mIconPaint.setShader(null);
                mGlyphPaint.setColor(0xFFFFFFFF);
                mGlyphPaint.setTextSize(size * 0.42f);
                Paint.FontMetrics fm = mGlyphPaint.getFontMetrics();
                c.drawText("TF", size / 2f,
                        size / 2f - (fm.ascent + fm.descent) / 2f, mGlyphPaint);
            }
            // 深色细描边
            mRingPaint.setColor(0xFF141820);
            mRingPaint.setStrokeWidth(dp(1.2f));
            c.drawPath(Squircle.path(size, size, radius), mRingPaint);
            mRingPaint.setStrokeWidth(dp(1.6f));
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        // 收束阶段：整体内容轻微缩小，配合背景淡出，形成「收进应用」的观感
        float converge = clamp((mProgress - 0.80f) / 0.20f);
        int saveCount = -1;
        if (converge > 0f) {
            saveCount = canvas.save();
            float cs = 1f - 0.06f * converge;
            canvas.scale(cs, cs, w / 2f, h * 0.44f);
        }

        final float cx = mCx;
        final float cy = mCy;
        final float iconSize = mIconSize;

        // 1) 背景：缓存的渐变 + Paint 透明度控制淡入（不重建 Shader）
        float bgA = clamp(mProgress / 0.15f);
        mBgPaint.setShader(mBgShader);
        mBgPaint.setAlpha((int) (255 * bgA));
        canvas.drawRect(0, 0, w, h, mBgPaint);
        mBgPaint.setAlpha(255);

        // 2) 中心光晕：缩放画布复用同一个 RadialGradient
        float glowA = clamp((mProgress - 0.10f) / 0.30f);
        if (glowA > 0) {
            float k = (1.6f + 0.5f * glowA) / 2.1f;   // 相对最大半径的比例
            int sc = canvas.save();
            canvas.scale(k, k, cx, cy);
            mGlowPaint.setShader(mGlowShader);
            mGlowPaint.setAlpha((int) (255 * glowA));
            canvas.drawCircle(cx, cy, iconSize * 2.1f, mGlowPaint);
            canvas.restoreToCount(sc);
            mGlowPaint.setAlpha(255);
        }

        // 3) 扩散光环（两圈，错开时间）
        drawRing(canvas, cx, cy, iconSize, 0.18f, 0.55f);
        drawRing(canvas, cx, cy, iconSize, 0.32f, 0.55f);

        // 4) 图标：位图贴图 + 缩放回弹（零分配）
        float iconIn = clamp((mProgress - 0.10f) / 0.32f);
        if (iconIn > 0 && mIconBmp != null && !mIconBmp.isRecycled()) {
            float scl = 0.55f + 0.45f * OVERSHOOT.getInterpolation(iconIn);
            int alpha = (int) (255 * clamp(iconIn * 1.8f));

            int sc2 = canvas.save();
            canvas.translate(cx, cy);
            canvas.scale(scl, scl);
            canvas.rotate(-4f * (1f - iconIn));

            float half = iconSize / 2f;
            mIconPaint.setAlpha(alpha);
            canvas.drawBitmap(mIconBmp, -half, -half, mIconPaint);
            mIconPaint.setAlpha(255);

            // 高光斜扫：固定着色器 + 平移画布
            float sweep = clamp((mProgress - 0.34f) / 0.26f);
            if (sweep > 0 && sweep < 1) {
                float x = -iconSize + sweep * iconSize * 2.4f;
                int sc3 = canvas.save();
                canvas.translate(x, 0);
                canvas.clipPath(mIconPath);
                canvas.translate(-half, -half);
                mSweepPaint.setShader(mSweepShader);
                canvas.drawRect(0, 0, iconSize, iconSize, mSweepPaint);
                canvas.restoreToCount(sc3);
            }
            canvas.restoreToCount(sc2);
        }

        // 5) 名称：逐字浮现（用缓存字宽，避免每帧 measureText）
        final String title = "TF管理终端";
        mTitlePaint.setTextSize(Math.min(w * TITLE_SIZE_RATIO, dp(30f)));
        float tx = cx - mTitleW / 2f;
        for (int i = 0; i < title.length(); i++) {
            float t = clamp((mProgress - (0.44f + i * 0.035f)) / 0.18f);
            float cw = mCharW == null || i >= mCharW.length ? 0f : mCharW[i];
            if (t > 0f) {
                int ta = (int) (255 * t);
                float dy = dp(14f) * (1f - t);
                mTitlePaint.setAlpha(ta);
                canvas.drawText(title.substring(i, i + 1), tx + cw / 2f,
                        cy + iconSize * 0.95f + dy, mTitlePaint);
            }
            tx += cw;
        }

        // 副标题稍后整体浮入
        float subIn = clamp((mProgress - 0.62f) / 0.20f);
        if (subIn > 0) {
            mSubPaint.setAlpha((int) (255 * subIn * 0.72f));
            mSubPaint.setTextSize(Math.min(w * 0.036f, dp(14f)));
            canvas.drawText("文件 · 终端 · 分区", cx,
                    cy + iconSize * 0.95f + dp(26f) + dp(12f) * (1f - subIn), mSubPaint);
        }

        // 6) 收束：内容整体轻微缩小 + 淡化，背景随之落定（澎湃的「收进应用」）
        float out = clamp((mProgress - 0.80f) / 0.20f);
        if (out > 0) {
            int veil = (int) (235 * out * out);
            mBgPaint.setShader(null);
            mBgPaint.setColor(withAlpha(0xFFFAFAFC, veil));
            canvas.drawRect(0, 0, w, h, mBgPaint);
        }
        restoreConverge(canvas, saveCount);
    }

    private Drawable mAppIcon;
    private boolean mIconTried;

    /** 取应用自身的启动图标（自适应图标会带上系统形状）。 */
    private Drawable appIcon() {
        if (!mIconTried) {
            mIconTried = true;
            try {
                mAppIcon = getContext().getPackageManager()
                        .getApplicationIcon(getContext().getPackageName());
            } catch (Throwable ignored) {
                mAppIcon = null;
            }
        }
        return mAppIcon;
    }

    private void restoreConverge(Canvas canvas, int saveCount) {
        if (saveCount >= 0) {
            try {
                canvas.restoreToCount(saveCount);
            } catch (Throwable ignored) {
            }
        }
    }

    private void drawRing(Canvas canvas, float cx, float cy, float iconSize,
                          float startAt, float span) {
        float t = clamp((mProgress - startAt) / span);
        if (t <= 0f || t >= 1f) {
            return;
        }
        float r = iconSize * (0.55f + 2.4f * t);
        int alpha = (int) (120 * (1f - t) * (1f - t));
        mRingPaint.setColor(withAlpha(BRAND, alpha));
        canvas.drawCircle(cx, cy, r, mRingPaint);
    }

    // ------------------------------------------------------------ 工具

    private float dp(float v) {
        return v * mDensity;
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
