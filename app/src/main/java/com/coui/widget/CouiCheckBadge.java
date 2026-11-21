package com.coui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * coui 大对勾徽标（KernelSU/Miuix 首页状态卡右上角那种大对勾水印）。
 *
 * <p>画一个空心圆 + 对勾，通常放大后放在卡片右侧，超出卡片边缘被裁掉。</p>
 */
public class CouiCheckBadge extends View {

    private final Paint mPaint;
    private final Path mPath = new Path();
    private final RectF mRect = new RectF();
    private int mColor = 0xFF34C759;

    public CouiCheckBadge(Context context) {
        super(context);
        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setBadgeColor(int color) {
        mColor = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float size = Math.min(w, h);
        float stroke = size * 0.075f;
        mPaint.setStrokeWidth(stroke);
        mPaint.setColor(mColor);

        float inset = stroke / 2f + size * 0.02f;
        mRect.set(inset, inset, size - inset, size - inset);
        canvas.drawOval(mRect, mPaint);

        // 对勾
        mPath.reset();
        mPath.moveTo(size * 0.28f, size * 0.52f);
        mPath.lineTo(size * 0.44f, size * 0.68f);
        mPath.lineTo(size * 0.74f, size * 0.36f);
        canvas.drawPath(mPath, mPaint);
    }
}