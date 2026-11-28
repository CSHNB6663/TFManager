package com.miuix.window;

import android.app.Activity;
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
import com.miuix.glassicons.MiuixGlassIcons;
import com.miuix.material.MiuixMaterial;
import com.miuix.ui.MiuixTheme;

/**
 * miuix-window：桌面窗口 / 标题栏 + 液态玻璃对话框。
 *
 * <p>上游 {@code miuix-window} 负责 Desktop JVM 的窗口与 caption bar 样式。
 * 本工程在 Android 上把同一套语义映射为：</p>
 * <ul>
 *   <li>{@link #captionBar}：桌面式玻璃标题栏（标题 + 最小化 / 最大化 / 关闭）；</li>
 *   <li>{@link Builder}：整块 {@link MiuixGlass} 液态玻璃窗口，按钮也是玻璃胶囊，
 *       Android 12+ 叠加系统级背景模糊；</li>
 *   <li>{@link #freeform}：参考附件 MIUI 小窗启动器，尝试以自由窗口启动 Activity。</li>
 * </ul>
 */
public final class MiuixWindow {

    public interface OnAction {
        void onClick(Dialog d);
    }

    public interface OnCaptionAction {
        void onClick();
    }

    private MiuixWindow() {
    }

    public static Builder builder(Context c) {
        return new Builder(c);
    }

    // ------------------------------------------------------------ 桌面标题栏

    /** 桌面式玻璃标题栏：标题 + 三枚玻璃窗口按钮（回调为 null 时隐藏对应按钮）。 */
    public static MiuixGlass captionBar(Context c, String title,
                                         final OnCaptionAction onMinimize,
                                         final OnCaptionAction onMaximize,
                                         final OnCaptionAction onClose) {
        MiuixGlass bar = MiuixGlass.pill(c);
        bar.radiusDp(18);
        bar.paddingDp(12, 8, 10, 8);
        bar.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        MiuixGlass dot = MiuixGlassIcons.clearTile(c, 0,
                MiuixTheme.primary(), 22, 12);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                MiuixTheme.dp(c, 22), MiuixTheme.dp(c, 22));
        dlp.rightMargin = MiuixTheme.dp(c, 10);
        row.addView(dot, dlp);

        TextView tv = new TextView(c);
        tv.setText(title == null ? "" : title);
        tv.setTextSize(13.5f);
        tv.setTextColor(MiuixTheme.onSurface());
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setSingleLine(true);
        row.addView(tv, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (onMinimize != null) {
            row.addView(captionButton(c, "—", onMinimize));
        }
        if (onMaximize != null) {
            row.addView(captionButton(c, "▢", onMaximize));
        }
        if (onClose != null) {
            row.addView(captionButton(c, "✕", onClose));
        }
        return bar;
    }

    private static View captionButton(Context c, String symbol,
                                      final OnCaptionAction action) {
        MiuixGlass b = MiuixGlass.pill(c);
        b.radiusDp(13);
        b.specular(true);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                MiuixTheme.dp(c, 30), MiuixTheme.dp(c, 26)));
        ((LinearLayout.LayoutParams) b.getLayoutParams()).leftMargin =
                MiuixTheme.dp(c, 6);

        TextView t = new TextView(c);
        t.setText(symbol);
        t.setTextSize(12f);
        t.setTextColor(MiuixTheme.onSurface());
        t.setGravity(Gravity.CENTER);
        b.addView(t, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        b.setClickable(true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.onClick();
            }
        });
        return b;
    }

    /** 尝试以自由窗口（桌面小窗）启动 Activity；不支持时返回 false。 */
    public static boolean freeform(Activity a, int x, int y, int w, int h, float scale) {
        if (a == null) {
            return false;
        }
        try {
            android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
            opts.getClass().getMethod("setLaunchWindowingMode", int.class).invoke(opts, 5);
            opts.setLaunchBounds(new android.graphics.Rect(x, y, x + w, y + h));
            try {
                opts.getClass().getMethod("setFreeformScale", float.class)
                        .invoke(opts, scale);
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------ 对话框窗口

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
        private boolean captionBar = false;

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

        /** 用桌面式玻璃标题栏替代普通标题（关闭按钮 = dismiss）。 */
        public Builder captionBar(boolean on) {
            captionBar = on;
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

            // 整块液态玻璃窗口
            final MiuixGlass glass = new MiuixGlass(ctx);
            MiuixMaterial wm = MiuixMaterial.preset(ctx, MiuixMaterial.Preset.LIQUID);
            wm.tintTop = 0xEEFFFFFF;
            wm.tintBottom = 0xDCEBFFFF;
            wm.radiusDp = 26f;
            glass.material(wm);
            glass.paddingDp(20, 18, 20, 14);

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            glass.addView(col, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            if (captionBar) {
                MiuixGlass bar = MiuixWindow.captionBar(ctx, title, null, null,
                        new OnCaptionAction() {
                            @Override
                            public void onClick() {
                                d.dismiss();
                            }
                        });
                LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                cbp.bottomMargin = MiuixTheme.dp(ctx, 12);
                col.addView(bar, cbp);
            } else if (title != null && !title.isEmpty()) {
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
                if (scrollable) {
                    ScrollView sv = new ScrollView(ctx);
                    sv.addView(m);
                    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
                    sp.topMargin = MiuixTheme.dp(ctx, 10);
                    sv.setLayoutParams(sp);
                    col.addView(sv);
                } else {
                    LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    mp.topMargin = MiuixTheme.dp(ctx, 10);
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

            // 玻璃按钮区
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
                MiuixBlur.applyWindowBlur(w, MiuixTheme.dp(ctx, 20));
            }

            d.show();
            // 澎湃风格出现动画：0.92 → 1.0 弹性放大 + 淡入 + 轻微上浮
            com.rootterm.fm.Motion.dialogEnter(glass);
            if (ctx instanceof Activity) {
                glass.captureBackdrop(((Activity) ctx).getWindow().getDecorView());
            }
            return d;
        }

        /** 玻璃胶囊按钮：主按钮强调色玻璃，次按钮清透玻璃。 */
        private View button(String text, boolean primary, final OnAction a,
                            final Dialog dlg) {
            MiuixGlass g = MiuixGlass.pill(ctx);
            MiuixMaterial bm = MiuixMaterial.preset(ctx,
                    primary ? MiuixMaterial.Preset.ACCENT : MiuixMaterial.Preset.CLEAR);
            bm.radiusDp = 14f;
            g.material(bm);
            g.paddingDp(16, 9, 16, 9);

            TextView b = new TextView(ctx);
            b.setText(text);
            b.setTextSize(14f);
            b.setGravity(Gravity.CENTER);
            b.setTextColor(primary ? MiuixTheme.onPrimary() : MiuixTheme.onSurface());
            if (primary) {
                b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            }
            g.addView(b, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = MiuixTheme.dp(ctx, 8);
            g.setLayoutParams(lp);
            g.setClickable(true);
            g.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.onClick(dlg);
                }
            });
            return g;
        }
    }
}
