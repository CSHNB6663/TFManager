package com.coui;

/**
 * coui 组件库基础工具类
 * 提供通用的 UI 工具方法和常量
 */
public class CouiUtils {

    /**
     * dp 转 px
     */
    public static int dp2px(android.content.Context context, float dp) {
        final float scale = context.getResources().getDisplayMetrics().density;
        return (int) (dp * scale + 0.5f);
    }

    /**
     * sp 转 px
     */
    public static int sp2px(android.content.Context context, float sp) {
        final float scale = context.getResources().getDisplayMetrics().scaledDensity;
        return (int) (sp * scale + 0.5f);
    }

    /**
     * 获取屏幕宽度
     */
    public static int getScreenWidth(android.content.Context context) {
        return context.getResources().getDisplayMetrics().widthPixels;
    }

    /**
     * 获取屏幕高度
     */
    public static int getScreenHeight(android.content.Context context) {
        return context.getResources().getDisplayMetrics().heightPixels;
    }

    /**
     * 获取状态栏高度
     */
    public static int getStatusBarHeight(android.content.Context context) {
        int result = 0;
        int resourceId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            result = context.getResources().getDimensionPixelSize(resourceId);
        }
        return result;
    }

    /**
     * 获取导航栏高度
     */
    public static int getNavigationBarHeight(android.content.Context context) {
        int result = 0;
        int resourceId = context.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (resourceId > 0) {
            result = context.getResources().getDimensionPixelSize(resourceId);
        }
        return result;
    }

    /**
     * 判断是否为深色模式
     */
    public static boolean isDarkMode(android.content.Context context) {
        int nightModeFlags = context.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * 获取主题色（兼容 Material Design 3）
     */
    public static int getThemeColor(android.content.Context context, int attrRes) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        context.getTheme().resolveAttribute(attrRes, typedValue, true);
        return typedValue.data;
    }

    /**
     * 设置 View 圆角背景
     */
    public static void setRoundBackground(android.view.View view, int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp2px(view.getContext(), radiusDp));
        view.setBackground(drawable);
    }

    /**
     * 设置 View 描边
     */
    public static void setStrokeBackground(android.view.View view, int strokeColor, int strokeWidthDp, int radiusDp, int backgroundColor) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(backgroundColor);
        drawable.setCornerRadius(dp2px(view.getContext(), radiusDp));
        drawable.setStroke(dp2px(view.getContext(), strokeWidthDp), strokeColor);
        view.setBackground(drawable);
    }

    /**
     * 设置 Ripple 效果（Android 5.0+）
     */
    public static void setRippleEffect(android.view.View view, int rippleColor) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            android.content.res.ColorStateList colorStateList = android.content.res.ColorStateList.valueOf(rippleColor);
            android.graphics.drawable.RippleDrawable rippleDrawable = new android.graphics.drawable.RippleDrawable(
                    colorStateList, null, null);
            view.setBackground(rippleDrawable);
        }
    }

    /**
     * 设置 View 的点击反馈（缩放动画）
     */
    public static void setClickScaleAnimation(android.view.View view) {
        view.setOnTouchListener(new android.view.View.OnTouchListener() {
            @Override
            public boolean onTouch(android.view.View v, android.view.MotionEvent event) {
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start();
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                        break;
                }
                return false;
            }
        });
    }
}