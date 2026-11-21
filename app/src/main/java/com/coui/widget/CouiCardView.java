package com.coui.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import com.coui.CouiUtils;
import com.rootterm.fm.R;
import com.rootterm.fm.ThemeColors;

/**
 * coui 圆角卡片容器（按 KernelSU-Style-UI-Kit 的 Miuix 规格）。
 *
 * <p>继承 {@link FrameLayout}，可以直接往里塞内容；圆角背景 / 描边 / 可选阴影
 * 都在 {@link #onDraw} 里绘制，子 View 绘制在其上。</p>
 */
public class CouiCardView extends FrameLayout {

    private final Paint mBgPaint;
    private final Paint mBorderPaint;
    private final RectF mRect;

    private int mBackgroundColor = 0xFFFFFFFF;
    private int mBorderColor = 0xFFEDEFF2;
    private float mBorderWidth;
    private float mCornerRadius;
    private boolean mHasShadow;
    private int mShadowColor = 0x14000000;
    private float mShadowDx;
    private float mShadowDy;

    public CouiCardView(Context context) {
        this(context, null);
    }

    public CouiCardView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CouiCardView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(false);
        mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mRect = new RectF();

        // Miuix 默认：16dp 圆角、0.8dp 描边、无阴影
        mCornerRadius = CouiUtils.dp2px(context, 16);
        mBorderWidth = Math.max(1, CouiUtils.dp2px(context, 0.8f));
        mBorderColor = 0xFFEDEFF2;

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.CouiCardView);
            try {
                mBackgroundColor = a.getColor(R.styleable.CouiCardView_cardBackgroundColor, mBackgroundColor);
                mBorderColor = a.getColor(R.styleable.CouiCardView_cardBorderColor, mBorderColor);
                mBorderWidth = a.getDimension(R.styleable.CouiCardView_cardBorderWidth, mBorderWidth);
                mCornerRadius = a.getDimension(R.styleable.CouiCardView_cardCornerRadius, mCornerRadius);
                mHasShadow = a.getBoolean(R.styleable.CouiCardView_cardHasShadow, mHasShadow);
                mShadowColor = a.getColor(R.styleable.CouiCardView_cardShadowColor, mShadowColor);
                mShadowDx = a.getDimension(R.styleable.CouiCardView_cardShadowDx, mShadowDx);
                mShadowDy = a.getDimension(R.styleable.CouiCardView_cardShadowDy, mShadowDy);
            } finally {
                a.recycle();
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 子 View 裁到圆角内（Miuix 卡片会裁掉超出的对勾水印）
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), mCornerRadius);
            }
        });
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        mRect.set(0, 0, w, h);

        if (mHasShadow) {
            Paint sp = new Paint(mBgPaint);
            sp.setColor(mShadowColor);
            sp.setShadowLayer(CouiUtils.dp2px(getContext(), 8), mShadowDx, mShadowDy, mShadowColor);
            canvas.drawRoundRect(mRect, mCornerRadius, mCornerRadius, sp);
        }

        mBgPaint.setColor(mBackgroundColor);
        canvas.drawRoundRect(mRect, mCornerRadius, mCornerRadius, mBgPaint);

        if (mBorderWidth > 0) {
            mBorderPaint.setColor(mBorderColor);
            mBorderPaint.setStrokeWidth(mBorderWidth);
            canvas.drawRoundRect(
                    new RectF(mBorderWidth / 2f, mBorderWidth / 2f,
                            w - mBorderWidth / 2f, h - mBorderWidth / 2f),
                    mCornerRadius - mBorderWidth / 2f, mCornerRadius - mBorderWidth / 2f,
                    mBorderPaint);
        }
        super.onDraw(canvas);
    }

    /** 用当前 Monet 主题色做一张「强调卡」（如状态卡）。 */
    public void useAccentSurface(boolean accent) {
        if (accent) {
            mBackgroundColor = ThemeColors.ACCENT_CONTAINER;
            mBorderColor = ThemeColors.blend(ThemeColors.ACCENT, 0xFFFFFFFF, 0.6f);
        } else {
            mBackgroundColor = 0xFFFFFFFF;
            mBorderColor = 0xFFEDEFF2;
        }
        invalidate();
    }

    // ---------------------------------------------------------------- getters/setters

    public void setCardBackgroundColor(int color) {
        mBackgroundColor = color;
        invalidate();
    }

    public int getCardBackgroundColor() {
        return mBackgroundColor;
    }

    public void setCardBorderColor(int color) {
        mBorderColor = color;
        invalidate();
    }

    public void setCardBorderWidth(float widthPx) {
        mBorderWidth = widthPx;
        invalidate();
    }

    public void setCardCornerRadius(float radiusPx) {
        mCornerRadius = radiusPx;
        invalidate();
    }

    public float getCardCornerRadius() {
        return mCornerRadius;
    }

    /** dp 版圆角设置。 */
    public void setCardCornerRadiusDp(float radiusDp) {
        setCardCornerRadius(CouiUtils.dp2px(getContext(), radiusDp));
    }

    public void setCardHasShadow(boolean hasShadow) {
        mHasShadow = hasShadow;
        invalidate();
    }

    public void setCardShadowColor(int color) {
        mShadowColor = color;
        invalidate();
    }

    public void setCardShadowOffset(float dxPx, float dyPx) {
        mShadowDx = dxPx;
        mShadowDy = dyPx;
        invalidate();
    }
}