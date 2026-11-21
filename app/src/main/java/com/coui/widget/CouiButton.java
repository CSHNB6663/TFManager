package com.coui.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;

import com.coui.CouiUtils;
import com.rootterm.fm.R;
import com.rootterm.fm.ThemeColors;

/**
 * coui 按钮组件
 * 支持多种样式：填充、描边、文字、图标
 * 支持 Ripple 点击效果、加载状态、禁用状态
 */
public class CouiButton extends View {

    private final TextPaint mTextPaint;
    private final Paint mBackgroundPaint;
    private final Paint mBorderPaint;
    private final Rect mTextBounds;

    // 样式常量
    public static final int STYLE_FILLED = 0;      // 填充样式
    public static final int STYLE_OUTLINED = 1;    // 描边样式
    public static final int STYLE_TEXT = 2;        // 文字按钮
    public static final int STYLE_TONAL = 3;       // 色调样式

    // 状态
    private int mStyle = STYLE_FILLED;
    private String mText = "";
    private int mTextColor;
    private int mBackgroundColor;
    private int mBorderColor;
    private float mBorderWidth;
    private float mCornerRadius;
    private int mIconRes = 0;
    private int mIconGravity = Gravity.START;
    private int mIconSize;
    private int mIconPadding;
    private boolean mEnabled = true;
    private boolean mLoading = false;
    private int mLoadingProgress = 0;
    private boolean mRippleEnabled = true;

    // 绘制
    private int mTextWidth;
    private int mTextHeight;
    private Rect mTextRect;
    private int mIconLeft;
    private int mIconTop;

    // 点击监听
    private OnClickListener mOnClickListener;
    private boolean mPressed = false;

    public CouiButton(Context context) {
        this(context, null);
    }

    public CouiButton(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CouiButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        
        mTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeCap(Paint.Cap.ROUND);
        mBorderPaint.setStrokeJoin(Paint.Join.ROUND);
        mTextBounds = new Rect();
        mTextRect = new Rect();

        // 默认值（跟随 Monet 主题色）
        mTextColor = ThemeColors.ON_ACCENT;
        mBackgroundColor = ThemeColors.ACCENT;
        mBorderColor = ThemeColors.ACCENT;
        mBorderWidth = CouiUtils.dp2px(getContext(), 1);
        mCornerRadius = CouiUtils.dp2px(getContext(), 20);
        mIconSize = CouiUtils.dp2px(getContext(), 20);
        mIconPadding = CouiUtils.dp2px(getContext(), 8);
        mTextPaint.setTextSize(CouiUtils.sp2px(getContext(), 14));

        if (attrs != null) {
            TypedArray a = getContext().obtainStyledAttributes(attrs, R.styleable.CouiButton);
            try {
                mStyle = a.getInt(R.styleable.CouiButton_buttonStyle, mStyle);
                mText = a.getString(R.styleable.CouiButton_android_text);
                if (mText == null) mText = "";
                mTextColor = a.getColor(R.styleable.CouiButton_android_textColor, mTextColor);
                mBackgroundColor = a.getColor(R.styleable.CouiButton_cardBackgroundColor, mBackgroundColor);
                mBorderColor = a.getColor(R.styleable.CouiButton_cardBorderColor, mBorderColor);
                mBorderWidth = a.getDimension(R.styleable.CouiButton_cardBorderWidth, mBorderWidth);
                mCornerRadius = a.getDimension(R.styleable.CouiButton_cardCornerRadius, mCornerRadius);
                mIconRes = a.getResourceId(R.styleable.CouiButton_iconSrc, mIconRes);
                mIconGravity = a.getInt(R.styleable.CouiButton_iconGravity, mIconGravity);
                mIconSize = (int) a.getDimension(R.styleable.CouiButton_iconSize, mIconSize);
                mIconPadding = (int) a.getDimension(R.styleable.CouiButton_iconPadding, mIconPadding);
                mEnabled = a.getBoolean(R.styleable.CouiButton_android_enabled, mEnabled);
            } finally {
                a.recycle();
            }
        }

        measureText();
        setClickable(true);
        setFocusable(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();

        // 绘制背景
        if (mStyle == STYLE_FILLED || mStyle == STYLE_TONAL) {
            // 填充背景
            Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgPaint.setColor(mPressed ? darkenColor(mBackgroundColor) : mBackgroundColor);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), CouiUtils.dp2px(getContext(), 20), CouiUtils.dp2px(getContext(), 20), bgPaint);
        } else if (mStyle == STYLE_OUTLINED) {
            // 描边背景
            Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgPaint.setColor(mPressed ? darkenColor(mBackgroundColor) : mBackgroundColor);
            bgPaint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), CouiUtils.dp2px(getContext(), 20), CouiUtils.dp2px(getContext(), 20), bgPaint);
            
            // 绘制描边
            Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(CouiUtils.dp2px(getContext(), 1));
            borderPaint.setColor(mBorderColor);
            canvas.drawRect(new Rect(1, 1, getWidth()-1, getHeight()-1), borderPaint);
        }
        // STYLE_TEXT 无背景

        // 绘制图标
        if (mIconRes != 0) {
            android.graphics.drawable.Drawable icon = getResources().getDrawable(mIconRes);
            if (icon != null) {
                int iconSize = mIconSize;
                int iconLeft, iconTop;
                
                if (mIconGravity == Gravity.START) {
                    iconLeft = (getWidth() - getContentWidth()) / 2;
                    iconTop = (getHeight() - mIconSize) / 2;
                } else {
                    iconLeft = getWidth() - (getWidth() - getContentWidth()) / 2 - mIconSize;
                    iconTop = (getHeight() - mIconSize) / 2;
                }
                
                icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
                icon.draw(canvas);
            }
        }

        // 绘制文字
        if (!mText.isEmpty()) {
            Paint textPaint = new Paint(mTextPaint);
            textPaint.setColor(mEnabled ? mTextColor : 0x80FFFFFF);
            canvas.drawText(mText, (getWidth() - mTextWidth) / 2, (getHeight() + mTextHeight) / 2, textPaint);
        }

        // 加载动画
        if (mLoading) {
            drawLoading(canvas);
        }
    }

    private int getContentWidth() {
        int width = mTextWidth;
        if (mIconRes != 0) {
            width += mIconSize + mIconPadding;
        }
        return width;
    }

    private void measureText() {
        mTextBounds.setEmpty();
        mTextPaint.getTextBounds(mText, 0, mText.length(), mTextBounds);
        mTextWidth = mTextBounds.width();
        mTextHeight = mTextBounds.height();
        mTextRect.set(0, 0, mTextWidth, mTextHeight);
    }

    private void drawLoading(Canvas canvas) {
        // 简单的加载动画：旋转的圆弧
        Paint loadingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        loadingPaint.setStyle(Paint.Style.STROKE);
        loadingPaint.setStrokeWidth(CouiUtils.dp2px(getContext(), 3));
        loadingPaint.setColor(mTextColor);
        loadingPaint.setStrokeCap(Paint.Cap.ROUND);
        
        int centerX = getWidth() / 2;
        int centerY = getHeight() / 2;
        int radius = Math.min(getWidth(), getHeight()) / 4;
        
        canvas.drawArc(new RectF(centerX - radius, centerY - radius,
                        centerX + radius, centerY + radius),
                mLoadingProgress, 90, false, loadingPaint);
    }

    private int darkenColor(int color) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(color, hsv);
        hsv[2] = hsv[2] * 0.8f; // 降低亮度
        return android.graphics.Color.HSVToColor(hsv);
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        if (!mEnabled || mLoading) {
            return true;
        }

        switch (event.getAction()) {
            case android.view.MotionEvent.ACTION_DOWN:
                mPressed = true;
                invalidate();
                return true;
            case android.view.MotionEvent.ACTION_UP:
                if (mPressed) {
                    mPressed = false;
                    invalidate();
                    if (mOnClickListener != null) {
                        mOnClickListener.onClick(this);
                    }
                    performClick();
                }
                return true;
            case android.view.MotionEvent.ACTION_CANCEL:
                mPressed = false;
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    // Getter/Setter

    public int getStyle() {
        return mStyle;
    }

    public void setStyle(int style) {
        mStyle = style;
        updateColors();
        invalidate();
    }

    public String getText() {
        return mText;
    }

    public void setText(String text) {
        mText = text == null ? "" : text;
        measureText();
        invalidate();
    }

    public int getTextColor() {
        return mTextColor;
    }

    public void setTextColor(int color) {
        mTextColor = color;
        invalidate();
    }

    public int getBackgroundColor() {
        return mBackgroundColor;
    }

    public void setBackgroundColor(int color) {
        mBackgroundColor = color;
        updateColors();
        invalidate();
    }

    public int getBorderColor() {
        return mBorderColor;
    }

    public void setBorderColor(int color) {
        mBorderColor = color;
        invalidate();
    }

    public float getBorderWidth() {
        return mBorderWidth;
    }

    public void setBorderWidth(float width) {
        mBorderWidth = width;
        invalidate();
    }

    public float getCornerRadius() {
        return mCornerRadius;
    }

    public void setCornerRadius(float radius) {
        mCornerRadius = radius;
        invalidate();
    }

    public int getIconRes() {
        return mIconRes;
    }

    public void setIconRes(int resId) {
        mIconRes = resId;
        invalidate();
    }

    public int getIconGravity() {
        return mIconGravity;
    }

    public void setIconGravity(int gravity) {
        mIconGravity = gravity;
        invalidate();
    }

    public int getIconSize() {
        return mIconSize;
    }

    public void setIconSize(int size) {
        mIconSize = size;
        invalidate();
    }

    public int getIconPadding() {
        return mIconPadding;
    }

    public void setIconPadding(int padding) {
        mIconPadding = padding;
        invalidate();
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
        invalidate();
    }

    public boolean isLoading() {
        return mLoading;
    }

    public void setLoading(boolean loading) {
        mLoading = loading;
        if (loading) {
            // 启动加载动画
            new Thread(new Runnable() {
                public void run() {
                    while (mLoading) {
                        mLoadingProgress = (mLoadingProgress + 10) % 360;
                        postInvalidate();
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }).start();
        } else {
            mLoadingProgress = 0;
        }
        invalidate();
    }

    public void setOnClickListener(OnClickListener listener) {
        mOnClickListener = listener;
    }

    public void setRippleEnabled(boolean enabled) {
        mRippleEnabled = enabled;
    }

    private void updateColors() {
        switch (mStyle) {
            case STYLE_FILLED:
                mTextColor = ThemeColors.ON_ACCENT;
                mBackgroundColor = ThemeColors.ACCENT;
                mBorderColor = mBackgroundColor;
                break;
            case STYLE_OUTLINED:
                mTextColor = ThemeColors.ACCENT;
                mBackgroundColor = 0x00000000;
                mBorderColor = ThemeColors.ACCENT;
                break;
            case STYLE_TEXT:
                mTextColor = ThemeColors.ACCENT;
                mBackgroundColor = 0x00000000;
                mBorderColor = 0x00000000;
                break;
            case STYLE_TONAL:
                mTextColor = ThemeColors.ON_ACCENT_CONTAINER;
                mBackgroundColor = ThemeColors.ACCENT_CONTAINER;
                mBorderColor = mBackgroundColor;
                break;
        }
    }
}