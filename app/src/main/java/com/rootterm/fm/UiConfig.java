package com.rootterm.fm;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 统一的深色配色与常用控件工厂。 */
public final class UiConfig {

    // MIUIX / HyperOS 浅色（终端页也改成白底）
    public static final int BG = 0xFFFFFFFF;        // 终端页背景：白
    public static final int SURFACE = 0xFFF7F8FA;   // 状态条 / 输入区
    public static final int SURFACE_2 = 0xFFF0F1F3; // 按钮 / 输入胶囊
    public static final int TEXT = 0xFF191919;
    public static final int TEXT_DIM = 0xFF8A8F98;
    public static int ACCENT = 0xFF3482FF;
    public static int ON_ACCENT = 0xFFFFFFFF;
    public static final int WARN = 0xFFF0A23C;
    public static final int ERROR = 0xFFE8481F;
    public static final int BORDER = 0xFFEDEFF2;
    public static final int OK = 0xFF1FA35B;

    private UiConfig() {
    }

    /** 脚本运行兼容模式：不注入 LD_LIBRARY_PATH/TMPDIR，用系统 sh。 */
    public static boolean compatMode(Context c) {
        return c.getSharedPreferences("ui", Context.MODE_PRIVATE)
                .getBoolean("run_compat", false);
    }

    public static void setCompatMode(Context c, boolean on) {
        c.getSharedPreferences("ui", Context.MODE_PRIVATE)
                .edit().putBoolean("run_compat", on).apply();
    }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    public static TextView text(Context c, String s, float sizeSp, int color) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(color);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    public static TextView mono(Context c, String s, float sizeSp, int color) {
        TextView tv = text(c, s, sizeSp, color);
        tv.setTypeface(Typeface.MONOSPACE);
        return tv;
    }

    public static Button button(Context c, String s, boolean primary) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        b.setTextColor(primary ? ON_ACCENT : TEXT);
        b.setPadding(dp(c, 12), dp(c, 4), dp(c, 12), dp(c, 4));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? ACCENT : SURFACE_2);
        bg.setCornerRadius(dp(c, 12));
        b.setBackground(bg);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.7f)));
        v.setLayoutParams(lp);
        return v;
    }

    /** 圆角小徽标，例如 ROOT / USER。 */
    public static TextView badge(Context c, String s, int fg, int bg) {
        TextView tv = text(c, s, 10.5f, fg);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setPadding(dp(c, 7), dp(c, 2), dp(c, 7), dp(c, 2));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(c, 8));
        tv.setBackground(g);
        return tv;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpWeight(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }
}
