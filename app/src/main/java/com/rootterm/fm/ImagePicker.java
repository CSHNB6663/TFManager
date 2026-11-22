package com.rootterm.fm;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.miuix.ui.MiuixTheme;
import com.miuix.window.MiuixWindow;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * 内置镜像选择器：浏览本机目录，选 .img / .bin 文件。
 *
 * <p>不依赖系统文件选择器，也不需要额外权限（App 已具备所有文件访问权限）。</p>
 */
public final class ImagePicker {

    /** 选中回调。 */
    public interface OnPick {
        void onPick(File file);
    }

    private ImagePicker() {
    }

    /** 弹出选择器。 */
    public static void show(final Context ctx, final String title,
                            String startDir, final OnPick cb) {
        show(ctx, title, startDir, new String[]{".img", ".bin"}, "镜像文件（.img / .bin）", cb);
    }

    /** 通用版本：自定义扩展名与提示。 */
    public static void show(final Context ctx, final String title, String startDir,
                            final String[] exts, final String hint, final OnPick cb) {
        final String dir = startDir == null || startDir.isEmpty()
                ? "/sdcard/Download" : startDir;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);

        final TextView pathView = new TextView(ctx);
        pathView.setText(dir);
        pathView.setTextSize(11f);
        pathView.setTextColor(MiuixTheme.onSurfaceVariant());
        pathView.setSingleLine(true);
        pathView.setEllipsize(android.text.TextUtils.TruncateAt.START);
        box.addView(pathView);

        TextView tip = new TextView(ctx);
        tip.setText(hint);
        tip.setTextSize(11f);
        tip.setTextColor(MiuixTheme.onSurfaceVariant());
        tip.setPadding(0, dp(ctx, 4), 0, dp(ctx, 6));
        box.addView(tip);

        ScrollView sv = new ScrollView(ctx);
        final LinearLayout listBox = new LinearLayout(ctx);
        listBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listBox);
        box.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 320)));

        final android.app.Dialog dlg = MiuixWindow.builder(ctx)
                .title(title)
                .content(box)
                .scrollable(false)
                .negative("取消", null)
                .show();

        fill(ctx, listBox, pathView, dir, dlg, cb, exts);
    }

    private static void fill(final Context ctx, final LinearLayout listBox,
                             final TextView pathView, final String dir,
                             final android.app.Dialog dlg, final OnPick cb,
                             final String[] exts) {
        listBox.removeAllViews();
        pathView.setText(dir);

        if (!"/".equals(dir) && !"/sdcard".equals(dir)) {
            listBox.addView(row(ctx, "⬆  上级目录", true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int i = dir.lastIndexOf('/');
                    fill(ctx, listBox, pathView, i <= 0 ? "/" : dir.substring(0, i), dlg, cb, exts);
                }
            }));
        }
        if (!"/sdcard/Download".equals(dir)) {
            listBox.addView(row(ctx, "📥 下载目录", true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    fill(ctx, listBox, pathView, "/sdcard/Download", dlg, cb, exts);
                }
            }));
        }

        File d = new File(dir);
        File[] fs = d.listFiles();
        if (fs == null) {
            listBox.addView(row(ctx, "（无法读取该目录）", false, null));
            return;
        }
        Arrays.sort(fs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });

        int shown = 0;
        for (final File f : fs) {
            String n = f.getName();
            if (n.startsWith(".")) {
                continue;
            }
            if (f.isDirectory()) {
                if (shown++ > 200) {
                    break;
                }
                listBox.addView(row(ctx, "📁 " + n, true, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        fill(ctx, listBox, pathView, f.getAbsolutePath(), dlg, cb, exts);
                    }
                }));
            } else {
                String ln = n.toLowerCase(Locale.US);
                boolean ok = false;
                for (String e : exts) {
                    if (ln.endsWith(e.toLowerCase(Locale.US))) {
                        ok = true;
                        break;
                    }
                }
                if (!ok) {
                    continue;
                }
                if (shown++ > 200) {
                    break;
                }
                listBox.addView(row(ctx, "💾 " + n + "   " + human(f.length()), true,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                dlg.dismiss();
                                if (cb != null) {
                                    cb.onPick(f);
                                }
                            }
                        }));
            }
        }
        if (shown == 0) {
            listBox.addView(row(ctx, "（此目录没有符合条件的文件）", false, null));
        }
    }

    private static View row(Context ctx, String text, boolean clickable,
                            View.OnClickListener l) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(13f);
        t.setTextColor(clickable ? MiuixTheme.onSurface() : MiuixTheme.onSurfaceVariant());
        t.setPadding(dp(ctx, 6), dp(ctx, 10), dp(ctx, 6), dp(ctx, 10));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        if (clickable && l != null) {
            t.setClickable(true);
            t.setOnClickListener(l);
        }
        return t;
    }

    private static int dp(Context c, float v) {
        return MiuixTheme.dp(c, v);
    }

    private static String human(long b) {
        if (b <= 0) {
            return "0 B";
        }
        double d = b;
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (d >= 1024 && i < u.length - 1) {
            d /= 1024;
            i++;
        }
        return String.format(Locale.US, d >= 100 ? "%.0f %s" : "%.1f %s", d, u[i]);
    }
}
