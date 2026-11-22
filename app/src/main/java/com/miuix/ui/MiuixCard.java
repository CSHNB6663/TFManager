package com.miuix.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.widget.FrameLayout;

import com.miuix.squircle.Squircle;

/**
 * miuix-ui：卡片（超椭圆圆角表面）。
 *
 * <p>对应 Miuix 的 {@code Card} / {@code Surface}：连续圆角、可选描边、可选内嵌底色。
 * 子 View 会被裁剪到超椭圆内。</p>
 */
public class MiuixCard extends FrameLayout {

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Path mPath;

    private float mRadiusPx;
    private int mFillColor = MiuixTheme.surface();
    private int mStrokeColor;
    private float mStrokeWidth;

    public MiuixCard(Context c) {
        super(c);
        setWillNotDraw(false);
        mRadiusPx = MiuixTheme.dp(c, MiuixTheme.RADIUS_CARD);
        mFill.setStyle(Paint.Style.FILL);
        mStroke.setStyle(Paint.Style.STROKE);
        applyClip();
    }

    /** 圆角（dp）。 */
    public MiuixCard radiusDp(float dp) {
        mRadiusPx = MiuixTheme.dp(getContext(), dp);
        applyClip();
        invalidate();
        return this;
    }

    public MiuixCard fill(int color) {
        mFillColor = color;
        invalidate();
        return this;
    }

    public MiuixCard stroke(int color, float widthDp) {
        mStrokeColor = color;
        mStrokeWidth = MiuixTheme.dp(getContext(), widthDp);
        invalidate();
        return this;
    }

    public MiuixCard paddingDp(int l, int t, int r, int b) {
        setPadding(MiuixTheme.dp(getContext(), l), MiuixTheme.dp(getContext(), t),
                MiuixTheme.dp(getContext(), r), MiuixTheme.dp(getContext(), b));
        return this;
    }

    private void applyClip() {
        Squircle.clipChildren(this, mRadiusPx);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        mPath = Squircle.path(w, h, mRadiusPx);
        invalidateOutline();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mPath == null) {
            mPath = Squircle.path(getWidth(), getHeight(), mRadiusPx);
        }
        mFill.setColor(mFillColor);
        canvas.drawPath(mPath, mFill);

        if (mStrokeWidth > 0) {
            mStroke.setColor(mStrokeColor);
            mStroke.setStrokeWidth(mStrokeWidth);
            float inset = mStrokeWidth / 2f;
            canvas.save();
            canvas.translate(inset, inset);
            canvas.drawPath(Squircle.path(getWidth() - mStrokeWidth,
                    getHeight() - mStrokeWidth, mRadiusPx - inset), mStroke);
            canvas.restore();
        }
        super.onDraw(canvas);
    }

    // ---------------------------------------------------------------- 便捷构造

    /** 生成一个默认卡片（白底 + 细描边 + 20dp 圆角 + 内边距）。 */
    public static MiuixCard create(Context c) {
        MiuixCard card = new MiuixCard(c);
        card.fill(MiuixTheme.surface());
        card.stroke(MiuixTheme.outline(), 0.8f);
        card.paddingDp(MiuixTheme.SPACE_CARD, MiuixTheme.SPACE_CARD,
                MiuixTheme.SPACE_CARD, MiuixTheme.SPACE_CARD);
        return card;
    }

    /** 无描边的纯色块（用于分组内部）。 */
    public static MiuixCard plain(Context c, int color, float radiusDp) {
        MiuixCard card = new MiuixCard(c);
        card.fill(color);
        card.radiusDp(radiusDp);
        return card;
    }

    /** 旧代码兼容：把 GradientDrawable 背景转成超椭圆填充。 */
    public void setSquircleBackground(int color, float radiusDp) {
        fill(color);
        radiusDp(radiusDp);
        setBackground((GradientDrawable) null);
    }
}