package com.rootterm.fm;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.miuix.ui.MiuixTheme;

/**
 * 下拉选项浮层：从被点击的那一行下方展开。
 *
 * <p>与全屏居中弹窗不同，这个浮层贴着触发控件出现，宽度与触发控件一致，
 * 高度从 0 展开到内容高度，同时淡入并做极轻微缩放 —— 常见于系统设置里
 * 「主题模式 → 跟随系统 / 浅色 / 深色」这类单选展开。</p>
 */
public final class OptionSheet {

    /** 选择回调。 */
    public interface OnPick {
        void onPick(int index);
    }

    private OptionSheet() {
    }

    /**
     * 弹出选项浮层。
     *
     * @param act      宿主 Activity
     * @param anchor   触发控件（浮层对齐它，宽度跟随）
     * @param items    选项文字
     * @param selected 当前选中下标，-1 表示无
     * @param onPick   选择回调（在收起动画开始前调用）
     */
    public static void show(final Activity act, final View anchor,
                            final String[] items, final int selected,
                            final OnPick onPick) {
        if (act == null || anchor == null || items == null || items.length == 0) {
            return;
        }
        final ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
        final float density = act.getResources().getDisplayMetrics().density;

        // ---------------- 遮罩 ----------------
        final FrameLayout scrim = new FrameLayout(act);
        scrim.setBackgroundColor(0xFF000000);
        scrim.setAlpha(0f);
        scrim.setClickable(true);
        decor.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---------------- 浮层本体 ----------------
        final LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(panelBackground(act));
        panel.setClipToOutline(true);
        panel.setElevation(dp(density, 8f));

        int rowH = (int) dp(density, 46f);
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) dp(density, 16f), 0, (int) dp(density, 16f), 0);

            TextView t = new TextView(act);
            t.setText(items[i]);
            t.setTextSize(14f);
            t.setTextColor(i == selected ? ThemeColors.ACCENT : MiuixTheme.onSurface());
            row.addView(t, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (i == selected) {
                TextView check = new TextView(act);
                check.setText("✓");
                check.setTextSize(15f);
                check.setTextColor(ThemeColors.ACCENT);
                check.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                row.addView(check);
            }

            if (i > 0) {
                View line = new View(act);
                line.setBackgroundColor(0x14000000);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) dp(density, 0.6f)));
                lp.leftMargin = (int) dp(density, 16f);
                lp.rightMargin = (int) dp(density, 16f);
                panel.addView(line, lp);
            }

            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(final View v) {
                    // 按下态：轻微回弹，给一次可感知的反馈
                    v.animate().scaleX(0.985f).scaleY(0.985f).setDuration(60)
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    v.animate().scaleX(1f).scaleY(1f).setDuration(90).start();
                                }
                            }).start();
                    collapse(act, decor, scrim, panel, anchor);
                    if (onPick != null) {
                        onPick.onPick(idx);
                    }
                }
            });
            panel.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowH));
        }

        // ---------------- 定位 ----------------
        int anchorW = anchor.getWidth();
        if (anchorW <= 0) {
            anchorW = (int) (act.getResources().getDisplayMetrics().widthPixels * 0.8f);
        }
        // 先按目标宽度测量出内容高度
        panel.measure(View.MeasureSpec.makeMeasureSpec(anchorW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int fullH = panel.getMeasuredHeight();

        final int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        final int[] dloc = new int[2];
        decor.getLocationInWindow(dloc);

        int left = loc[0] - dloc[0];
        int top = loc[1] - dloc[1] + anchor.getHeight() + (int) dp(density, 6f);

        final FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                anchorW, 0);
        plp.leftMargin = left;
        plp.topMargin = top;
        panel.setLayoutParams(plp);
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleX(0.98f);
        panel.setScaleY(0.9f);
        decor.addView(panel);

        // 动画关闭时直接显示，不做展开
        if (Motion.LEVEL <= 0) {
            plp.height = fullH;
            panel.setLayoutParams(plp);
            panel.setAlpha(1f);
            panel.setScaleX(1f);
            panel.setScaleY(1f);
            scrim.setAlpha(0.16f);
            scrim.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    collapse(act, decor, scrim, panel, anchor);
                }
            });
            return;
        }

        // ---------------- 展开 ----------------
        final int[] heights = {0, fullH};
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(200);
        va.setInterpolator(Motion.EMPHASIZED);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (Float) a.getAnimatedValue();
                plp.height = (int) (fullH * t);
                panel.setLayoutParams(plp);
                panel.setAlpha(t);
                panel.setScaleY(0.9f + 0.1f * t);
                panel.setScaleX(0.98f + 0.02f * t);
                scrim.setAlpha(0.16f * t);
            }
        });
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapse(act, decor, scrim, panel, anchor);
            }
        });
        va.start();
    }

    /** 收起并移除浮层。 */
    private static void collapse(final Activity act, final ViewGroup decor,
                                 final FrameLayout scrim, final LinearLayout panel,
                                 final View anchor) {
        final FrameLayout.LayoutParams plp =
                (FrameLayout.LayoutParams) panel.getLayoutParams();
        final int fromH = plp.height;
        if (Motion.LEVEL <= 0) {
            try {
                decor.removeView(panel);
                decor.removeView(scrim);
            } catch (Throwable ignored) {
            }
            return;
        }
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(140);
        va.setInterpolator(Motion.DECEL);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (Float) a.getAnimatedValue();
                plp.height = (int) (fromH * (1f - t));
                panel.setLayoutParams(plp);
                panel.setAlpha(1f - t);
                scrim.setAlpha(0.16f * (1f - t));
            }
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                try {
                    decor.removeView(panel);
                    decor.removeView(scrim);
                } catch (Throwable ignored) {
                }
            }
        });
        va.start();
    }

    private static GradientDrawable panelBackground(Activity act) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFFDFDFF);
        g.setCornerRadius(dp(act.getResources().getDisplayMetrics().density, 16f));
        g.setStroke(Math.max(1, (int) dp(act.getResources().getDisplayMetrics().density, 0.7f)),
                0x1A000000);
        return g;
    }

    private static float dp(float density, float v) {
        return v * density;
    }
}
