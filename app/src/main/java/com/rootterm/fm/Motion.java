package com.rootterm.fm;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * 动画工具：仿澎湃 OS 的「弹性 + 可打断」运动曲线。
 *
 * <p>澎湃的转场不是线性淡入淡出，而是带轻微回弹与加速尾部的曲线。
 * 这里用两套实现：</p>
 * <ul>
 *   <li>{@link #EMPHASIZED} / {@link #STANDARD} —— 近似 Material 3 的强调曲线，
 *       加速进入、缓慢收尾，观感接近系统转场；</li>
 *   <li>{@link #SPRING} —— 欠阻尼弹簧，用于卡片/图标出现时的回弹。</li>
 * </ul>
 */
public final class Motion {

    /** 标准转场（240ms）：快速起步，尾段减速。 */
    public static final Interpolator STANDARD = new PathInterpolator(0.2f, 0f, 0f, 1f);

    /** 强调转场（300ms）：起步更快、收尾更长，用于页面切换。 */
    public static final Interpolator EMPHASIZED = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

    /** 回弹（用于出现类动画）：轻度过冲后回落。 */
    public static final Interpolator SPRING = new Interpolator() {
        @Override
        public float getInterpolation(float t) {
            if (t <= 0f) {
                return 0f;
            }
            if (t >= 1f) {
                return 1f;
            }
            // 阻尼正弦：衰减系数 4.8、角频率 12 时回弹幅度约 4%，接近澎湃手感
            float d = 4.8f;
            float w = 12f;
            return (float) (1.0 - Math.exp(-d * t) * Math.cos(w * t));
        }
    };

    /** 收束（用于结束类动画）：先轻微加速再落定。 */
    public static final Interpolator DECEL = new DecelerateInterpolator(1.4f);

    public static final int DUR_STANDARD = 240;
    public static final int DUR_EMPHASIZED = 300;
    public static final int DUR_ENTER = 260;

    private Motion() {
    }

    /**
     * 页面进入：从 scale + alpha 的起始态弹性落到正常态。
     *
     * @param fromScale 起始缩放，澎湃常用 0.94
     * @param fromAlpha 起始透明度
     */
    public static void enter(View v, float fromScale, float fromAlpha) {
        if (v == null) {
            return;
        }
        v.animate().cancel();
        v.setAlpha(fromAlpha);
        v.setScaleX(fromScale);
        v.setScaleY(fromScale);
        v.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setInterpolator(SPRING)
                .setDuration(DUR_ENTER)
                .start();
    }

    /** 页面退出：缩到 slightly smaller 并淡出（与 enter 交叉执行）。 */
    public static void exit(View v, float toScale, float toAlpha, int duration) {
        if (v == null) {
            return;
        }
        v.animate().cancel();
        v.animate()
                .alpha(toAlpha)
                .scaleX(toScale)
                .scaleY(toScale)
                .setInterpolator(EMPHASIZED)
                .setDuration(duration)
                .start();
    }

    /** 复位到可见的正常状态（避免动画被中断后残留半透明/缩放）。 */
    public static void reset(View v) {
        if (v == null) {
            return;
        }
        v.animate().cancel();
        v.setAlpha(1f);
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setTranslationY(0f);
        v.setTranslationX(0f);
    }

    /**
     * 弹窗卡片出现：0.92 → 1.0 弹性放大 + 淡入，同时带一点上浮。
     */
    public static void dialogEnter(View v) {
        if (v == null) {
            return;
        }
        final float dy = v.getResources().getDisplayMetrics().density * 10f;
        v.setAlpha(0f);
        v.setScaleX(0.92f);
        v.setScaleY(0.92f);
        v.setTranslationY(dy);
        v.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setInterpolator(SPRING)
                .setDuration(DUR_ENTER)
                .start();
    }

    /**
     * 逐字浮现：把一个字符串的每个字符按顺序淡入+上浮。
     *
     * @param paintLike 用于测量的画笔回调，见 SplashView 里的用法
     */
    public static ValueAnimator progress(long duration, Interpolator ip,
                                        final Ticker ticker) {
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(duration);
        a.setInterpolator(ip == null ? STANDARD : ip);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                ticker.onTick((Float) animation.getAnimatedValue());
            }
        });
        return a;
    }

    /** 动画进度回调。 */
    public interface Ticker {
        void onTick(float progress);
    }

    /** 便捷：动画结束时执行一次。 */
    public static void onEnd(ValueAnimator a, final Runnable r) {
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (r != null) {
                    r.run();
                }
            }
        });
    }
}
