package com.miuix.nav;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.miuix.glass.MiuixGlass;
import com.miuix.squircle.Squircle;
import com.miuix.ui.MiuixTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * miuix-nav：底部玻璃导航条。
 *
 * <p>Miuix 风格：居中胶囊、液态玻璃材质、选中项下方/背后有滑动的超椭圆指示块。
 * 支持点击切换与「按住左右拖动、松手停在哪就切到哪」。</p>
 */
public class MiuixNavBar extends FrameLayout {

    /** 选择回调。 */
    public interface Listener {
        void onSelect(int index);
    }

    private static final class Item {
        LinearLayout view;
        ImageView icon;
        TextView label;
        String title;
        int iconRes;
    }

    private final List<Item> mItems = new ArrayList<Item>();
    private final MiuixGlass mPill;
    private final FrameLayout mInner;
    private final LinearLayout mRow;
    private final View mIndicator;

    private Listener mListener;
    private int mSelected;
    private int mPreview = -1;

    private int mIndicatorColor;
    private int mActiveColor;
    private int mInactiveColor;

    public MiuixNavBar(Context c) {
        super(c);
        setClipToPadding(false);

        mIndicatorColor = MiuixTheme.primaryContainer();
        mActiveColor = MiuixTheme.primary();
        mInactiveColor = 0xFF3A3F45;

        mPill = MiuixGlass.pill(c);
        mPill.paddingDp(8, 7, 8, 7);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        addView(mPill, plp);

        mInner = new FrameLayout(c);
        mPill.addView(mInner, new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        mIndicator = new View(c);
        mIndicator.setVisibility(View.INVISIBLE);
        mInner.addView(mIndicator, new FrameLayout.LayoutParams(0, 0));

        mRow = new LinearLayout(c);
        mRow.setOrientation(LinearLayout.HORIZONTAL);
        mRow.setGravity(Gravity.CENTER);
        mInner.addView(mRow, new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    /** 磨砂底：抓取页面内容。 */
    public void captureBackdrop(View src) {
        mPill.captureBackdrop(src);
    }

    public MiuixNavBar listener(Listener l) {
        mListener = l;
        return this;
    }

    public MiuixNavBar colors(int indicator, int active, int inactive) {
        mIndicatorColor = indicator;
        mActiveColor = active;
        mInactiveColor = inactive;
        styleAll();
        return this;
    }

    /** 添加一个导航项。 */
    public MiuixNavBar addItem(String title, int iconRes) {
        Item it = new Item();
        it.title = title;
        it.iconRes = iconRes;

        LinearLayout item = new LinearLayout(getContext());
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(MiuixTheme.dp(getContext(), 14), MiuixTheme.dp(getContext(), 6),
                MiuixTheme.dp(getContext(), 14), MiuixTheme.dp(getContext(), 6));
        item.setMinimumWidth(MiuixTheme.dp(getContext(), 74));

        ImageView icon = new ImageView(getContext());
        if (iconRes != 0) {
            icon.setImageResource(iconRes);
        }
        item.addView(icon, new LinearLayout.LayoutParams(
                MiuixTheme.dp(getContext(), 22), MiuixTheme.dp(getContext(), 22)));

        TextView label = new TextView(getContext());
        label.setText(title);
        label.setTextSize(10.5f);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        tlp.topMargin = MiuixTheme.dp(getContext(), 3);
        item.addView(label, tlp);

        it.view = item;
        it.icon = icon;
        it.label = label;

        final int index = mItems.size();
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                select(index, true);
            }
        });

        mItems.add(it);
        mRow.addView(item, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        if (mItems.size() == 1) {
            mSelected = 0;
        }
        post(new Runnable() {
            @Override
            public void run() {
                styleAll();
                moveIndicator(false);
            }
        });
        return this;
    }

    /** 选中某项。 */
    public void select(int index, boolean notify) {
        if (index < 0 || index >= mItems.size()) {
            return;
        }
        boolean changed = index != mSelected;
        mSelected = index;
        styleAll();
        moveIndicator(true);
        if (notify && mListener != null && changed) {
            mListener.onSelect(index);
        }
    }

    public int selected() {
        return mSelected;
    }

    // ---------------------------------------------------------------- 指示块

    private void moveIndicator(boolean animate) {
        if (mSelected < 0 || mSelected >= mItems.size()) {
            return;
        }
        final View target = mItems.get(mSelected).view;
        final int l = target.getLeft();
        final int t = target.getTop();
        final int r = target.getRight();
        final int b = target.getBottom();
        if (r - l <= 0 || b - t <= 0) {
            return;
        }
        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) mIndicator.getLayoutParams();
        final int cl = lp.leftMargin;
        final int ct = lp.topMargin;
        final int cr = cl + lp.width;
        final int cb = ct + lp.height;

        mIndicator.setVisibility(View.VISIBLE);
        if (!animate) {
            applyIndicator(l, t, r, b);
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(220);
        a.setInterpolator(new DecelerateInterpolator(1.6f));
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator anim) {
                float f = anim.getAnimatedFraction();
                applyIndicator(
                        (int) (cl + (l - cl) * f),
                        (int) (ct + (t - ct) * f),
                        (int) (cr + (r - cr) * f),
                        (int) (cb + (b - cb) * f));
            }
        });
        a.start();
    }

    private void applyIndicator(int l, int t, int r, int b) {
        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) mIndicator.getLayoutParams();
        lp.width = Math.max(1, r - l);
        lp.height = Math.max(1, b - t);
        lp.leftMargin = l;
        lp.topMargin = t;
        mIndicator.setLayoutParams(lp);

        GradientDrawable g = new GradientDrawable();
        g.setColor(mIndicatorColor);
        g.setCornerRadius(MiuixTheme.dp(getContext(), 22));
        mIndicator.setBackground(g);
    }

    // ---------------------------------------------------------------- 样式

    private void styleAll() {
        for (int i = 0; i < mItems.size(); i++) {
            boolean active = i == mSelected;
            Item it = mItems.get(i);
            int color = active ? mActiveColor : mInactiveColor;
            if (it.icon != null) {
                it.icon.setColorFilter(color);
            }
            if (it.label != null) {
                it.label.setTextColor(color);
                it.label.setTypeface(null, active
                        ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }
        }
    }

    private int indexAt(float x) {
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < mItems.size(); i++) {
            View v = mItems.get(i).view;
            if (x >= v.getLeft() && x <= v.getRight()) {
                return i;
            }
            float cx = (v.getLeft() + v.getRight()) / 2f;
            float d = Math.abs(x - cx);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** 拖动切页：按住左右滑，松手停在哪就切到哪。 */
    public void installDrag() {
        mPill.setOnTouchListener(new OnTouchListener() {
            private float downX;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        dragging = false;
                        mPreview = mSelected;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        if (Math.abs(e.getX() - downX) > MiuixTheme.dp(getContext(), 6)) {
                            dragging = true;
                        }
                        if (dragging) {
                            int idx = indexAt(e.getX());
                            if (idx >= 0 && idx != mPreview) {
                                mPreview = idx;
                                // 预览：指示块跟着走
                                int save = mSelected;
                                mSelected = idx;
                                styleAll();
                                moveIndicator(true);
                                mSelected = save;
                                v.performHapticFeedback(
                                        android.view.HapticFeedbackConstants.CLOCK_TICK);
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP: {
                        int idx = indexAt(e.getX());
                        if (idx >= 0) {
                            select(idx, true);
                        } else {
                            styleAll();
                            moveIndicator(true);
                        }
                        mPreview = -1;
                        dragging = false;
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        styleAll();
                        moveIndicator(true);
                        mPreview = -1;
                        dragging = false;
                        return true;
                    default:
                        return false;
                }
            }
        });
    }
}