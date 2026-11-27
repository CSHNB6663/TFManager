package com.rootterm.fm;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** 常用 .sh 脚本：最近使用记录 + 常见目录扫描。 */
public final class ShFiles {

    private static final String PREF = "sh_files";
    private static final String KEY_RECENT = "recent";
    private static final int MAX_RECENT = 12;

    private ShFiles() {
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 最近使用列表（新→旧）。 */
    public static List<String> recent(Context c) {
        List<String> out = new ArrayList<String>();
        String raw = p(c).getString(KEY_RECENT, "");
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** 记住一次使用。 */
    public static void remember(Context c, String path) {
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        String p2 = path.trim();
        List<String> list = recent(c);
        list.remove(p2);
        list.add(0, p2);
        while (list.size() > MAX_RECENT) {
            list.remove(list.size() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(s);
        }
        p(c).edit().putString(KEY_RECENT, sb.toString()).apply();
    }

    /** 上次使用的脚本（用于自动填充）。 */
    public static String last(Context c) {
        List<String> list = recent(c);
        return list.isEmpty() ? "" : list.get(0);
    }

    /**
     * 扫描常见目录里的 .sh（后台线程调用）。
     */
    public static List<String> scan() {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        String cmd = "for d in /sdcard /sdcard/Download /sdcard/Documents "
                + "/sdcard/Download/TF提权 /data/local/tmp /data/adb/JN; do "
                + "  [ -d \"$d\" ] || continue; "
                + "  find \"$d\" -maxdepth 3 -type f \\( -name '*.sh' -o -name '*.bash' \\) "
                + "2>/dev/null | head -40; "
                + "done | head -60";
        String res = Shell.execSync(cmd, 12000);
        if (res != null) {
            for (String line : res.split("\n")) {
                String t = line.trim();
                if (!t.isEmpty() && t.startsWith("/")) {
                    out.add(t);
                }
            }
        }
        return new ArrayList<String>(out);
    }

    /** 文件是否存在。 */
    public static boolean exists(String path) {
        try {
            return new File(path).isFile();
        } catch (Throwable t) {
            return false;
        }
    }
}
