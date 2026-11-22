package com.miuix.window;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.miuix.blur.MiuixBlur;
import com.miuix.glass.MiuixGlass;
import com.miuix.ui.MiuixTheme;

/**
 * miuix-window：玻璃对话框窗口。
 *
 * <p>Miuix 的对话框是「悬浮玻璃卡」：系统级窗口模糊 + 超椭圆玻璃 + 圆角按钮区。
 * 这里用 {@link Dialog} 承载，Android 12+ 自动启用真实背景模糊。</p>
 */
public final class MiuixWindow {

    /** 按钮点击。 */
    public interface OnAction {
        void onClick(Dialog d);
    }

    private MiuixWindow() {
    }

    public static Builder builder(Context c) {
        return new Builder(c);
    }

    /** 构建器。 */
    public static final class Builder {

        private final Context ctx;
        private String title;
        private String message;
        private View content;
        private boolean scrollable = true;
        private String positiveText;
        private OnAction positiveAction;
        private String negativeText;
        private OnAction negativeAction;
        private String neutralText;
        private OnAction neutralAction;
        private boolean cancelable = true;

        Builder(Context c) {
            ctx = c;
        }

        public Builder title(String t) {
            title = t;
            return this;
        }

        public Builder message(String m) {
            message = m;
            return this;
        }

        public Builder content(View v) {
            content = v;
            return this;
        }

        public Builder scrollable(boolean s) {
            scrollable = s;
            return this;
        }

        public Builder positive(String text, OnAction a) {
            positiveText = text;
            positiveAction = a;
            return this;
        }

        public Builder negative(String text, OnAction a) {
            negativeText = text;
            negativeAction = a;
            return this;
        }

        public Builder neutral(String text, OnAction a) {
            neutralText = text;
            neutralAction = a;
            return this;
        }

        public Builder cancelable(boolean c) {
            cancelable = c;
            return this;
        }

        public Dialog show() {
            final Dialog d = new Dialog(ctx);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);

            // ---------------- 玻璃卡内容 ----------------
            MiuixGlass glass = new MiuixGlass(ctx);
            glass.radiusDp(26);
            glass.tint(0xF7FFFFFF, 0xE8FFFFFF);
            glass.paddingDp(20, 18, 20, 14);

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            glass.addView(col, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            if (title != null && !title.isEmpty()) {
                TextView t = new TextView(ctx);
                t.setText(title);
                t.setTextSize(19f);
                t.setTextColor(MiuixTheme.onSurface());
                t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                col.addView(t);
            }

            if (message != null && !message.isEmpty()) {
                TextView m = new TextView(ctx);
                m.setText(message);
                m.setTextSize(13.5f);
                m.setTextColor(MiuixTheme.onSurfaceVariant());
                m.setLineSpacing(MiuixTheme.dp(ctx, 3), 1f);
                m.setTextIsSelectable(true);
                LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                mp.topMargin = MiuixTheme.dp(ctx, 10);
                View target = m;
                if (scrollable) {
                    ScrollView sv = new ScrollView(ctx);
                    sv.addView(m);
                    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
                    sp.topMargin = MiuixTheme.dp(ctx, 10);
                    sv.setLayoutParams(sp);
                    col.addView(sv);
                    target = sv;
                } else {
                    col.addView(m, mp);
                }
            }

            if (content != null) {
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                cp.topMargin = MiuixTheme.dp(ctx, 12);
                col.addView(content, cp);
            }

            // ---------------- 按钮区 ----------------
            LinearLayout btns = new LinearLayout(ctx);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setGravity(Gravity.END);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.topMargin = MiuixTheme.dp(ctx, 16);
            col.addView(btns, bp);

            if (neutralText != null) {
                btns.addView(button(neutralText, false, new OnAction() {
                    @Override
                    public void onClick(Dialog dlg) {
                        if (neutralAction != null) {
                            neutralAction.onClick(dlg);
                        } else {
                            dlg.dismiss();
                        }
                    }
                }, d));
            }
            if (negativeText != null) {
                btns.addView(button(negativeText, false, new OnAction() {
                    @Override
                    public void onClick(Dialog dlg) {
                        if (negativeAction != null) {
                            negativeAction.onClick(dlg);
                        }
                        dlg.dismiss();
                    }
                }, d));
            }
            if (positiveText != null) {
                btns.addView(button(positiveText, true, new OnAction() {
                    @Override
                    public void onClick(Dialog dlg) {
                        if (positiveAction != null) {
                            positiveAction.onClick(dlg);
                        }
                        dlg.dismiss();
                    }
                }, d));
            }

            // ---------------- 窗口 ----------------
            d.setContentView(glass);
            d.setCanceledOnTouchOutside(cancelable);
            d.setCancelable(cancelable);

            Window w = d.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.width = (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.88f);
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                lp.dimAmount = 0.28f;
                w.setAttributes(lp);
                // 系统级玻璃模糊（API 31+）
                MiuixBlur.applyWindowBlur(w, MiuixTheme.dp(ctx, 20));
            }

            d.show();
            return d;
        }

        private TextView button(String text, boolean primary, final OnAction a,
                                final Dialog dlg) {
            TextView b = new TextView(ctx);
            b.setText(text);
            b.setTextSize(14f);
            b.setGravity(Gravity.CENTER);
            b.setPadding(MiuixTheme.dp(ctx, 16), MiuixTheme.dp(ctx, 9),
                    MiuixTheme.dp(ctx, 16), MiuixTheme.dp(ctx, 9));
            b.setClickable(true);

            android.graphics.drawable.GradientDrawable g =
                    new android.graphics.drawable.GradientDrawable();
            g.setCornerRadius(MiuixTheme.dp(ctx, 14));
            if (primary) {
                g.setColor(MiuixTheme.primary());
                b.setTextColor(MiuixTheme.onPrimary());
                b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            } else {
                g.setColor(0x14000000);
                b.setTextColor(MiuixTheme.onSurface());
            }
            b.setBackground(g);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = MiuixTheme.dp(ctx, 8);
            b.setLayoutParams(lp);

            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.onClick(dlg);
                }
            });
            return b;
        }
    }
}