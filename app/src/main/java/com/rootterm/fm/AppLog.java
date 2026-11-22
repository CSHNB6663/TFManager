package com.rootterm.fm;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 应用日志 + 崩溃捕获。
 *
 * <p>日志写到 {@code /sdcard/Download/TF管理终端-日志/}（无权限时回退到应用私有目录）：</p>
 * <ul>
 *   <li>{@code app.log} —— 运行日志（自动截断，最大约 256KB）</li>
 *   <li>{@code crash-<时间>.txt} —— 未捕获异常堆栈 + 设备信息</li>
 * </ul>
 *
 * <p>所有方法内部吞掉异常，日志本身绝不导致崩溃。</p>
 */
public final class AppLog {

    private static final Object LOCK = new Object();
    private static final long MAX_BYTES = 256 * 1024L;
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat FILE_TS =
            new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);

    private static File sDir;
    private static volatile boolean sInited;

    private AppLog() {
    }

    /** 在 Application/Activity 最早时机调用。 */
    public static void init(Context ctx) {
        if (sInited) {
            return;
        }
        sInited = true;
        try {
            sDir = resolveDir(ctx);
            installCrashHandler();
            log("APP", "==== 启动 v" + AppVersion.NAME + " (" + AppVersion.CODE + ") ====");
            log("APP", "设备: " + Build.MANUFACTURER + " " + Build.MODEL
                    + " · Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
            log("APP", "日志目录: " + (sDir == null ? "不可用" : sDir.getAbsolutePath()));
        } catch (Throwable ignored) {
        }
    }

    /** 日志目录（可能为 null）。 */
    public static File dir(Context ctx) {
        if (sDir == null) {
            sDir = resolveDir(ctx);
        }
        return sDir;
    }

    private static File resolveDir(Context ctx) {
        // 优先外部「下载」目录，方便用户直接取出
        try {
            File d = new File("/sdcard/Download/TF管理终端-日志");
            if (!d.exists()) {
                d.mkdirs();
            }
            File probe = new File(d, ".w");
            FileOutputStream fo = new FileOutputStream(probe);
            fo.write(1);
            fo.close();
            probe.delete();
            return d;
        } catch (Throwable ignored) {
        }
        // 回退：应用私有目录
        try {
            File d = new File(ctx.getFilesDir(), "logs");
            d.mkdirs();
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 写一行日志。 */
    public static void log(String tag, String msg) {
        if (sDir == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                File f = new File(sDir, "app.log");
                if (f.length() > MAX_BYTES) {
                    // 简单轮转：保留后半段
                    byte[] keep = new byte[(int) (MAX_BYTES / 2)];
                    RandomAccessFile raf = new RandomAccessFile(f, "r");
                    try {
                        raf.seek(f.length() - keep.length);
                        raf.readFully(keep);
                    } finally {
                        try {
                            raf.close();
                        } catch (Throwable ignored) {
                        }
                    }
                    FileOutputStream fo = new FileOutputStream(f, false);
                    try {
                        fo.write("--- 日志已轮转 ---\n".getBytes("UTF-8"));
                        fo.write(keep);
                    } finally {
                        try {
                            fo.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                String line = TS.format(new Date()) + " [" + tag + "] " + msg + "\n";
                FileOutputStream fo = new FileOutputStream(f, true);
                try {
                    fo.write(line.getBytes("UTF-8"));
                } finally {
                    try {
                        fo.close();
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 记录异常。 */
    public static void log(String tag, String msg, Throwable t) {
        StringBuilder sb = new StringBuilder(msg == null ? "" : msg);
        if (t != null) {
            sb.append(" | ").append(t.getClass().getSimpleName())
                    .append(": ").append(t.getMessage());
        }
        log(tag, sb.toString());
    }

    /** 读取最近 maxLines 行。 */
    public static String tail(int maxLines) {
        if (sDir == null) {
            return "（日志目录不可用）";
        }
        File f = new File(sDir, "app.log");
        if (!f.isFile()) {
            return "（暂无日志）";
        }
        try {
            java.util.ArrayList<String> lines = new java.util.ArrayList<String>();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                            new java.io.FileInputStream(f), "UTF-8"));
            try {
                String l;
                while ((l = br.readLine()) != null) {
                    lines.add(l);
                    if (lines.size() > maxLines * 4) {
                        // 防止超大文件占内存
                        lines.subList(0, lines.size() - maxLines).clear();
                    }
                }
            } finally {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
            int from = Math.max(0, lines.size() - maxLines);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < lines.size(); i++) {
                sb.append(lines.get(i)).append('\n');
            }
            return sb.length() == 0 ? "（暂无日志）" : sb.toString();
        } catch (Throwable t) {
            return "（读取失败：" + t.getMessage() + "）";
        }
    }

    /** 崩溃文件列表（最新在前）。 */
    public static String crashSummary() {
        if (sDir == null) {
            return "";
        }
        try {
            File[] fs = sDir.listFiles();
            if (fs == null) {
                return "";
            }
            java.util.ArrayList<File> list = new java.util.ArrayList<File>();
            for (File f : fs) {
                if (f.getName().startsWith("crash-")) {
                    list.add(f);
                }
            }
            if (list.isEmpty()) {
                return "（无崩溃记录）";
            }
            java.util.Collections.sort(list, new java.util.Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
            StringBuilder sb = new StringBuilder();
            int n = Math.min(10, list.size());
            for (int i = 0; i < n; i++) {
                File f = list.get(i);
                sb.append(f.getName()).append("  (").append(f.length()).append(" B)\n");
            }
            if (list.size() > n) {
                sb.append("… 共 ").append(list.size()).append(" 个\n");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "（读取失败）";
        }
    }

    /** 最新崩溃内容。 */
    public static String latestCrash() {
        if (sDir == null) {
            return "";
        }
        try {
            File[] fs = sDir.listFiles();
            if (fs == null) {
                return "";
            }
            File best = null;
            for (File f : fs) {
                if (f.getName().startsWith("crash-")
                        && (best == null || f.lastModified() > best.lastModified())) {
                    best = f;
                }
            }
            if (best == null) {
                return "";
            }
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                            new java.io.FileInputStream(best), "UTF-8"));
            try {
                StringBuilder sb = new StringBuilder();
                String l;
                int n = 0;
                while ((l = br.readLine()) != null && n < 120) {
                    sb.append(l).append('\n');
                    n++;
                }
                return sb.toString();
            } finally {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            return "";
        }
    }

    /** 清空日志与崩溃文件。 */
    public static void clear() {
        if (sDir == null) {
            return;
        }
        try {
            File[] fs = sDir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.getName().endsWith(".log") || f.getName().startsWith("crash-")) {
                        f.delete();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        log("APP", "日志已清空");
    }

    // ---------------------------------------------------------------- 崩溃捕获

    private static void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread th, Throwable ex) {
                try {
                    writeCrash(th, ex);
                } catch (Throwable ignored) {
                }
                if (prev != null) {
                    prev.uncaughtException(th, ex);
                }
            }
        });
    }

    private static void writeCrash(Thread th, Throwable ex) throws Exception {
        if (sDir == null) {
            return;
        }
        String name = "crash-" + FILE_TS.format(new Date()) + ".txt";
        File f = new File(sDir, name);
        StringBuilder sb = new StringBuilder();
        sb.append("时间: ").append(TS.format(new Date())).append('\n');
        sb.append("版本: v").append(AppVersion.NAME)
                .append(" (").append(AppVersion.CODE).append(")\n");
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("系统: Android ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("线程: ").append(th == null ? "?" : th.getName()).append('\n');
        sb.append("---- 堆栈 ----\n");
        java.io.StringWriter sw = new java.io.StringWriter();
        ex.printStackTrace(new java.io.PrintWriter(sw));
        sb.append(sw.toString()).append('\n');
        FileOutputStream fo = new FileOutputStream(f);
        try {
            fo.write(sb.toString().getBytes("UTF-8"));
        } finally {
            try {
                fo.close();
            } catch (Throwable ignored) {
            }
        }
        log("CRASH", "已记录崩溃: " + name);
    }
}
