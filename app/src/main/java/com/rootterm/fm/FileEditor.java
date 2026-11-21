package com.rootterm.fm;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * 文本查看 / 编辑对话框。
 *
 * <p>纯 framework 实现：深色 {@link Dialog} + 等宽 {@link EditText} + 「保存」「取消」。
 * 读取与写回全部在后台线程走 {@link Shell}（root 时自动 su），写回优先把内容落到 app 私有
 * 临时文件后由 shell {@code cat > target}，兜底再用分块 base64 传输，避免引号 / {@code $}
 * 等字符的转义问题。</p>
 *
 * <p>超过 1MB 或 20000 行的文件只做只读预览。</p>
 */
public final class FileEditor {

    /** 可直接编辑的上限：1MB 或 20000 行，超出只读预览。 */
    private static final long MAX_EDIT_BYTES = 1024L * 1024L;
    private static final int MAX_EDIT_LINES = 20000;
    /** 只读预览读取的字节数。 */
    private static final int PREVIEW_BYTES = 256 * 1024;
    /** 深色 Material 对话框主题（Theme.Material 即暗色）。 */
    private static final int DIALOG_THEME = android.R.style.Theme_Material_Dialog;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    private FileEditor() {
    }

    /** 打开编辑器。读取在后台线程完成，{@code onSaved} 在主线程、保存成功后回调。 */
    public static void open(final Activity a, final String path, final Runnable onSaved) {
        if (a == null || path == null || path.isEmpty()) {
            return;
        }
        final Dialog loading = showLoading(a, path);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                TextFile loaded;
                try {
                    loaded = read(path);
                } catch (Throwable t) {
                    loaded = null;
                }
                final TextFile tf = loaded;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        dismiss(loading);
                        if (a.isFinishing()) {
                            return;
                        }
                        if (tf == null) {
                            toast(a, "读取失败（文件不存在或需要 root）：" + path);
                            return;
                        }
                        showEditor(a, path, tf, onSaved);
                    }
                });
            }
        }, "file-editor-read");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------ 读取

    private static TextFile read(String path) {
        final String q = Shell.quote(path);
        long size = -1;
        String wc = Shell.execSync("wc -c < " + q + " 2>/dev/null", 8000);
        size = parseLong(firstToken(wc), -1);
        if (size < 0) {
            size = parseLong(firstToken(Shell.execSync("stat -c %s " + q + " 2>/dev/null", 8000)), -1);
        }
        if (size < 0) {
            String chk = Shell.execSync("test -e " + q + " && echo __E__ || echo __N__", 8000);
            if (chk == null || !chk.contains("__E__")) {
                return null;
            }
        }
        long lines = -1;
        if (size >= 0 && size <= MAX_EDIT_BYTES) {
            lines = parseLong(firstToken(Shell.execSync("wc -l < " + q + " 2>/dev/null", 8000)), -1);
        }
        TextFile tf = new TextFile();
        if (size > MAX_EDIT_BYTES || lines > MAX_EDIT_LINES) {
            tf.readOnly = true;
            tf.note = "文件过大（" + humanSize(size) + (lines > 0 ? "，" + lines + " 行" : "")
                    + "），只读预览前 " + humanSize(PREVIEW_BYTES) + "，不能在此编辑。";
            String raw = Shell.execSync("head -c " + PREVIEW_BYTES + " " + q + " 2>/dev/null", 20000);
            tf.text = raw == null ? "" : raw;
            return tf;
        }
        byte[] bytes = readBytes(path);
        if (bytes != null) {
            tf.text = decodeUtf8(bytes);
            return tf;
        }
        StringBuilder out = new StringBuilder();
        int code = Shell.execSyncCode("cat " + q + " 2>/dev/null", 30000, out);
        if (code != 0) {
            return null;
        }
        tf.text = out.toString();
        return tf;
    }

    /** 用 base64 读取原始字节（避免逐行处理破坏内容），失败返回 null。 */
    private static byte[] readBytes(String path) {
        StringBuilder out = new StringBuilder();
        int code = Shell.execSyncCode("base64 < " + Shell.quote(path), 40000, out);
        if (code != 0) {
            return null;
        }
        return decodeBase64Lenient(out.toString());
    }

    /**
     * 宽松解码：忽略空白，出现非 base64 字符或长度不合法时返回 null（调用方走 cat 兜底）。
     */
    private static byte[] decodeBase64Lenient(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') {
                continue;
            }
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '=') {
                sb.append(c);
            } else {
                return null;
            }
        }
        if (sb.length() == 0) {
            return new byte[0];
        }
        if (sb.length() % 4 != 0) {
            return null;
        }
        try {
            return Base64.decode(sb.toString(), Base64.DEFAULT);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String decodeUtf8(byte[] b) {
        try {
            return new String(b, "UTF-8");
        } catch (Throwable e) {
            return new String(b);
        }
    }

    // ------------------------------------------------------------ 写入

    /**
     * 写回文件。返回 null 表示成功，否则是错误描述。
     *
     * <p>方案 1：内容写入 app 私有临时文件，shell 执行 {@code cat tmp > target}（无转义问题，
     * 目标权限 / 属主不受影响）。方案 2（兜底）：分块 base64，{@code echo <b64> | base64 -d >> target}，
     * 每块 60KB，规避单个参数长度限制。</p>
     */
    private static String writeFile(Activity a, String path, String content) {
        byte[] bytes;
        try {
            bytes = content.getBytes("UTF-8");
        } catch (Throwable e) {
            bytes = content.getBytes();
        }
        final String q = Shell.quote(path);
        File tmp = null;
        boolean tmpSaved = false;
        try {
            tmp = new File(a.getFilesDir(), "rootterm-edit-" + System.nanoTime() + ".tmp");
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                fos.write(bytes);
                fos.flush();
            } finally {
                fos.close();
            }
            tmpSaved = true;
        } catch (Throwable e) {
            tmpSaved = false;
        }
        if (tmpSaved && tmp != null) {
            String tp = Shell.quote(tmp.getAbsolutePath());
            StringBuilder out = new StringBuilder();
            int code = Shell.execSyncCode("cat " + tp + " > " + q + " && rm -f " + tp, 120000, out);
            if (code == 0) {
                return null;
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
        if (b64.isEmpty()) {
            StringBuilder out = new StringBuilder();
            int code = Shell.execSyncCode(": > " + q, 30000, out);
            return code == 0 ? null : "写入失败: " + safeLine(out.toString(), code);
        }
        final int chunk = 60000;
        for (int i = 0; i < b64.length(); i += chunk) {
            String part = b64.substring(i, Math.min(b64.length(), i + chunk));
            String redir = (i == 0) ? ">" : ">>";
            StringBuilder out = new StringBuilder();
            int code = Shell.execSyncCode("echo " + Shell.quote(part) + " | base64 -d "
                    + redir + " " + q, 120000, out);
            if (code != 0) {
                return "写入失败(" + code + "): " + safeLine(out.toString(), code);
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 界面

    private static void showEditor(final Activity a, final String path, final TextFile tf,
                                   final Runnable onSaved) {
        final Dialog d = new Dialog(a, DIALOG_THEME);
        try {
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        } catch (Throwable ignored) {
        }
        int pad = UiConfig.dp(a, 14);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(UiConfig.SURFACE);

        TextView title = UiConfig.mono(a, (tf.readOnly ? "查看 " : "编辑 ") + path, 12f, UiConfig.TEXT);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        root.addView(title, new LinearLayout.LayoutParams(MATCH, WRAP));

        if (!tf.note.isEmpty()) {
            TextView note = UiConfig.text(a, tf.note, 11f, UiConfig.WARN);
            note.setPadding(0, UiConfig.dp(a, 4), 0, 0);
            root.addView(note, new LinearLayout.LayoutParams(MATCH, WRAP));
        }

        final EditText et;
        if (tf.readOnly) {
            et = null;
            ScrollView sv = new ScrollView(a);
            sv.setBackgroundColor(UiConfig.BG);
            TextView tv = UiConfig.mono(a, tf.text, 11.5f, UiConfig.TEXT);
            tv.setTextIsSelectable(true);
            int p2 = UiConfig.dp(a, 10);
            tv.setPadding(p2, p2, p2, p2);
            sv.addView(tv, new ScrollView.LayoutParams(MATCH, WRAP));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(MATCH, 0, 1f);
            slp.topMargin = UiConfig.dp(a, 8);
            root.addView(sv, slp);
        } else {
            et = new EditText(a);
            et.setTypeface(Typeface.MONOSPACE);
            et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            et.setTextColor(UiConfig.TEXT);
            et.setBackgroundColor(UiConfig.BG);
            int p2 = UiConfig.dp(a, 10);
            et.setPadding(p2, UiConfig.dp(a, 8), p2, UiConfig.dp(a, 8));
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            et.setGravity(Gravity.TOP | Gravity.START);
            et.setHorizontallyScrolling(false);
            et.setText(tf.text);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(MATCH, 0, 1f);
            elp.topMargin = UiConfig.dp(a, 8);
            root.addView(et, elp);
        }

        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(MATCH, WRAP);
        blp.topMargin = UiConfig.dp(a, 10);
        Button cancel = UiConfig.button(a, tf.readOnly ? "关闭" : "取消", false);
        btns.addView(cancel, new LinearLayout.LayoutParams(WRAP, WRAP));
        final Button save = tf.readOnly ? null : UiConfig.button(a, "保存", true);
        if (save != null) {
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(WRAP, WRAP);
            slp.leftMargin = UiConfig.dp(a, 8);
            btns.addView(save, slp);
        }
        root.addView(btns, blp);
        d.setContentView(root);

        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss(d);
            }
        });
        if (save != null && et != null) {
            save.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final String content = et.getText() == null ? "" : et.getText().toString();
                    save.setEnabled(false);
                    save.setText("保存中…");
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            String res;
                            try {
                                res = writeFile(a, path, content);
                            } catch (Throwable t) {
                                res = "异常：" + t.getClass().getSimpleName();
                            }
                            final String err = res;
                            MAIN.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (a.isFinishing()) {
                                        return;
                                    }
                                    if (err == null) {
                                        toast(a, "已保存");
                                        dismiss(d);
                                        if (onSaved != null) {
                                            onSaved.run();
                                        }
                                    } else {
                                        save.setEnabled(true);
                                        save.setText("保存");
                                        toast(a, "保存失败: " + err);
                                    }
                                }
                            });
                        }
                    }, "file-editor-write");
                    t.setDaemon(true);
                    t.start();
                }
            });
        }

        try {
            d.show();
        } catch (Throwable e) {
            return;
        }
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(UiConfig.SURFACE));
            int width = a.getResources().getDisplayMetrics().widthPixels - UiConfig.dp(a, 20);
            int height = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.86f);
            w.setLayout(width, height);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private static Dialog showLoading(Activity a, String path) {
        Dialog d = new Dialog(a, DIALOG_THEME);
        try {
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        } catch (Throwable ignored) {
        }
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int p = UiConfig.dp(a, 16);
        box.setPadding(p, UiConfig.dp(a, 14), p, UiConfig.dp(a, 14));
        box.setBackgroundColor(UiConfig.SURFACE);
        ProgressBar pb = new ProgressBar(a);
        pb.setIndeterminate(true);
        box.addView(pb, new LinearLayout.LayoutParams(UiConfig.dp(a, 24), UiConfig.dp(a, 24)));
        TextView tv = UiConfig.mono(a, "读取中…\n" + path, 11f, UiConfig.TEXT);
        tv.setMaxLines(3);
        tv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        tlp.leftMargin = UiConfig.dp(a, 12);
        box.addView(tv, tlp);
        d.setContentView(box);
        try {
            d.show();
        } catch (Throwable e) {
            return null;
        }
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(UiConfig.SURFACE));
            w.setLayout(UiConfig.dp(a, 270), WRAP);
        }
        return d;
    }

    // ------------------------------------------------------------ 工具

    private static void dismiss(Dialog d) {
        if (d == null) {
            return;
        }
        try {
            if (d.isShowing()) {
                d.dismiss();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void toast(Activity a, String msg) {
        try {
            Toast.makeText(a, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    private static String firstToken(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        int i = 0;
        while (i < t.length() && !Character.isWhitespace(t.charAt(i))) {
            i++;
        }
        return t.substring(0, i);
    }

    private static long parseLong(String s, long def) {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable e) {
            return def;
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 0) {
            return "未知大小";
        }
        if (bytes < 1024) {
            return bytes + "B";
        }
        double v = bytes;
        String[] units = {"K", "M", "G", "T", "P"};
        int i = -1;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.US, "%.1f%s", v, units[i]);
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return "";
        }
        int i = t.indexOf('\n');
        if (i >= 0) {
            t = t.substring(0, i).trim();
        }
        if (t.length() > 240) {
            t = t.substring(0, 239) + "...";
        }
        return t;
    }

    private static String safeLine(String out, int code) {
        String l = firstLine(out);
        return l.isEmpty() ? ("exit " + code) : l;
    }

    /** 读取结果。 */
    private static final class TextFile {
        String text = "";
        boolean readOnly;
        String note = "";
    }
}
