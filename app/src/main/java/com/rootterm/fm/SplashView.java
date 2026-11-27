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

        setLayerType(LAYER_TYPE_HARDWARE, null);
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

    // ------------------------------------------------------------ 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        final float cx = w / 2f;
        final float cy = h * 0.44f;          // 图标中心略高于正中
        final float iconSize = Math.min(w, h) * 0.235f;
        final float radius = iconSize * 0.30f;

        // 1) 背景：上下渐变，随进度淡入
        float bgA = clamp(mProgress / 0.15f);
        int bgTop = withAlpha(BG_TOP, (int) (255 * bgA));
        int bgBottom = withAlpha(BG_BOTTOM, (int) (255 * bgA));
        mBgPaint.setShader(new LinearGradient(0, 0, 0, h, bgTop, bgBottom, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, mBgPaint);

        // 2) 中心光晕
        float glowA = clamp((mProgress - 0.10f) / 0.30f);
        if (glowA > 0) {
            float glowR = iconSize * (1.6f + 0.5f * glowA);
            mGlowPaint.setShader(new RadialGradient(cx, cy, glowR,
                    new int[]{withAlpha(BRAND, (int) (58 * glowA)),
                            0x002A6BE0},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, glowR, mGlowPaint);
        }

        // 3) 扩散光环（两圈，错开时间）
        drawRing(canvas, cx, cy, iconSize, 0.18f, 0.55f);
        drawRing(canvas, cx, cy, iconSize, 0.32f, 0.55f);

        // 4) 图标：缩放 + 回弹 + 淡入
        float iconIn = clamp((mProgress - 0.10f) / 0.32f);
        if (iconIn > 0) {
            float scale = new OvershootInterpolator(1.7f).getInterpolation(iconIn);
            scale = 0.55f + 0.45f * scale;
            int alpha = (int) (255 * clamp(iconIn * 1.8f));

            canvas.save();
            canvas.translate(cx, cy);
            canvas.scale(scale, scale);
            canvas.rotate(-4f * (1f - iconIn));

            float half = iconSize / 2f;
            mIconRect.set(-half, -half, half, half);
            mIconPath = Squircle.path(iconSize, iconSize, radius);
            canvas.save();
            canvas.translate(-half, -half);

            // 真实 App 图标：裁到圆角里绘制
            Drawable appIcon = appIcon();
            if (appIcon != null) {
                int layer = canvas.saveLayerAlpha(0, 0, iconSize, iconSize, alpha);
                canvas.clipPath(mIconPath);
                appIcon.setBounds(0, 0, (int) iconSize, (int) iconSize);
                appIcon.draw(canvas);
                canvas.restoreToCount(layer);
            } else {
                // 兜底：取不到图标时画品牌色方块
                mIconPaint.setAlpha(alpha);
                mIconPaint.setShader(new LinearGradient(0, 0, iconSize, iconSize,
                        withAlpha(BRAND, alpha), withAlpha(BRAND_DEEP, alpha),
                        Shader.TileMode.CLAMP));
                canvas.drawPath(mIconPath, mIconPaint);
                mGlyphPaint.setAlpha(alpha);
                mGlyphPaint.setTextSize(iconSize * 0.42f);
                Paint.FontMetrics fm = mGlyphPaint.getFontMetrics();
                float baseline = iconSize / 2f - (fm.ascent + fm.descent) / 2f;
                canvas.drawText("TF", iconSize / 2f, baseline, mGlyphPaint);
            }

            // 深色细描边：浅底上让图标边缘清楚
            mRingPaint.setAlpha((int) (alpha * 0.28f));
            mRingPaint.setColor(0xFF141820);
            mRingPaint.setStrokeWidth(dp(1.2f));
            canvas.drawPath(mIconPath, mRingPaint);
            mRingPaint.setStrokeWidth(dp(1.6f));

            // 高光斜扫
            float sweep = clamp((mProgress - 0.34f) / 0.26f);
            if (sweep > 0 && sweep < 1) {
                float x = -iconSize + sweep * iconSize * 2.4f;
                mSweepPaint.setShader(new LinearGradient(x - iconSize * 0.35f, 0, x + iconSize * 0.35f, iconSize * 0.7f,
                        new int[]{0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawPath(mIconPath, mSweepPaint);
            }
            canvas.restore();
            canvas.restore();
        }

        // 5) 名称与副标题
        float textIn = clamp((mProgress - 0.46f) / 0.26f);
        if (textIn > 0) {
            int ta = (int) (255 * textIn);
            float dy = dp(18f) * (1f - textIn);
            mTitlePaint.setAlpha(ta);
            mTitlePaint.setTextSize(Math.min(w * 0.075f, dp(30f)));
            canvas.drawText("TF管理终端", cx, cy + iconSize * 0.95f + dy, mTitlePaint);

            mSubPaint.setAlpha((int) (ta * 0.72f));
            mSubPaint.setTextSize(Math.min(w * 0.036f, dp(14f)));
            canvas.drawText("文件 · 终端 · 分区", cx,
                    cy + iconSize * 0.95f + dp(26f) + dy, mSubPaint);
        }

        // 6) 收尾：整体放大淡出
        float out = clamp((mProgress - 0.82f) / 0.18f);
        if (out > 0) {
            int veil = (int) (255 * out);
            mBgPaint.setShader(null);
            mBgPaint.setColor(withAlpha(0xFFFAFAFC, veil));
            canvas.drawRect(0, 0, w, h, mBgPaint);
        }
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
