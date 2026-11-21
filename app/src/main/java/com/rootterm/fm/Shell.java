package com.rootterm.fm;

import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 唯一的命令执行引擎。
 *
 * <p>有 root 时以 {@code su -c} 执行，无 root 时降级为 {@code /system/bin/sh -c}。
 * stdout 与 stderr 已合并。耗时操作全部在后台线程执行，回调统一投递到主线程。</p>
 */
public final class Shell {

    /** 命令执行回调，全部方法都在主线程调用。 */
    public interface Callback {
        void onStart();

        void onLine(String line);

        void onDone(int exitCode);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final String[] KNOWN_SU = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/debug_ramdisk/su",
    };

    private static volatile boolean sRoot = false;
    private static volatile boolean sProbed = false;
    private static volatile String sSuBin = "su";

    private Shell() {
    }

    // ---------------------------------------------------------------- root 探测

    /** 读取缓存结果，不阻塞，可在主线程调用。未探测前返回 false。 */
    public static boolean isRoot() {
        return sRoot;
    }

    /** 当前执行身份：{@code "root"} 或 {@code "shell"}。 */
    public static String identity() {
        return sRoot ? "root" : "shell";
    }

    public static boolean hasProbeResult() {
        return sProbed;
    }

    /** 阻塞探测 root（最长约 12 秒），刷新缓存。禁止在主线程调用。 */
    public static boolean detectRoot() {
        if (sRoot) {
            return true;
        }
        // 进程本身就是 uid 0（例如 adb root 起的环境）
        String id = execRaw(new String[]{"/system/bin/sh", "-c", "id"}, 2500);
        if (id != null && id.contains("uid=0")) {
            sRoot = true;
            sSuBin = "/system/bin/sh";
            sProbed = true;
            return true;
        }
        List<String> candidates = new ArrayList<>();
        candidates.add("su");
        for (String p : KNOWN_SU) {
            if (new File(p).exists()) {
                candidates.add(p);
            }
        }
        for (String bin : candidates) {
            String out = execRaw(new String[]{bin, "-c", "id"}, 4000);
            if (out != null && out.contains("uid=0")) {
                sSuBin = bin;
                sRoot = true;
                sProbed = true;
                return true;
            }
        }
        sRoot = false;
        sSuBin = "su";
        sProbed = true;
        return false;
    }

    /** 后台探测 root，完成后在主线程回调。 */
    public static void detectRootAsync(final Runnable doneOnMain) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                detectRoot();
                if (doneOnMain != null) {
                    MAIN.post(doneOnMain);
                }
            }
        }, "root-detect");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- 命令执行

    /** 异步流式执行一条命令。 */
    public static void exec(final String cmd, final Callback cb) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                execBlocking(cmd, cb);
            }
        }, "shell-exec");
        t.setDaemon(true);
        t.start();
    }

    private static void execBlocking(String cmd, Callback cb) {
        if (cb != null) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    cb.onStart();
                }
            });
        }
        final int TIMEOUT_MS = 180000;
        Process p = null;
        int code = -1;
        try {
            p = start(cmd);
            final Process proc = p;
            final StringBuilder carry = new StringBuilder();
            final int[] emitted = {0};
            final boolean[] truncated = {false};
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    InputStream in = proc.getInputStream();
                    byte[] buf = new byte[2048];
                    int n;
                    try {
                        while ((n = in.read(buf)) > 0) {
                            carry.append(new String(buf, 0, n, "UTF-8"));
                            int idx;
                            while ((idx = carry.indexOf("\n")) >= 0) {
                                final String line = carry.substring(0, idx);
                                carry.delete(0, idx + 1);
                                emit(cb, line, emitted, truncated);
                            }
                        }
                    } catch (IOException ignored) {
                    }
                    if (carry.length() > 0) {
                        emit(cb, carry.toString(), emitted, truncated);
                    }
                }
            }, "shell-reader");
            reader.setDaemon(true);
            reader.start();
            if (p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                code = p.exitValue();
                reader.join(1500);
            } else {
                p.destroy();
                code = -1;
                emit(cb, "[超时] 命令执行超过 " + (TIMEOUT_MS / 1000) + " 秒，已终止", emitted, truncated);
            }
        } catch (Throwable e) {
            code = -1;
            emit(cb, "[错误] " + describe(e), new int[]{0}, new boolean[]{false});
            if (p != null) {
                p.destroy();
            }
        }
        final int fcode = code;
        if (cb != null) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    cb.onDone(fcode);
                }
            });
        }
    }

    private static void emit(Callback cb, String line, int[] emitted, boolean[] truncated) {
        if (cb == null) {
            return;
        }
        if (truncated[0]) {
            return;
        }
        emitted[0]++;
        if (emitted[0] > 20000) {
            truncated[0] = true;
            final String warn = "[输出过多] 已截断，仅显示前 20000 行";
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    cb.onLine(warn);
                }
            });
            return;
        }
        final String s = line;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                cb.onLine(s);
            }
        });
    }

    /** 阻塞执行，返回合并后的输出（stdout+stderr + 超时标记）。 */
    public static String execSync(String cmd) {
        return execSync(cmd, 20000);
    }

    public static String execSync(String cmd, int timeoutMs) {
        ExecResult r = execSyncResult(cmd, timeoutMs);
        return r.output;
    }

    /** 阻塞执行，返回退出码；输出写入 {@code out}（可为 null）。 */
    public static int execSyncCode(String cmd, int timeoutMs, StringBuilder out) {
        ExecResult r = execSyncResult(cmd, timeoutMs);
        if (out != null) {
            out.append(r.output);
        }
        return r.code;
    }

    /** 阻塞执行的结果。 */
    public static final class ExecResult {
        public final String output;
        public final int code;

        ExecResult(String output, int code) {
            this.output = output;
            this.code = code;
        }
    }

    public static ExecResult execSyncResult(String cmd, int timeoutMs) {
        Process p = null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int code = -1;
        boolean timeout = false;
        try {
            p = start(cmd);
            final Process proc = p;
            final ByteArrayOutputStream sink = bos;
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        copyAll(proc.getInputStream(), sink);
                    } catch (IOException ignored) {
                    }
                }
            }, "shell-sync-reader");
            reader.setDaemon(true);
            reader.start();
            if (p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                code = p.exitValue();
                reader.join(1500);
            } else {
                timeout = true;
                p.destroy();
                reader.join(500);
            }
        } catch (Throwable e) {
            if (p != null) {
                p.destroy();
            }
            String s = bos.toString();
            return new ExecResult(s + "\n[错误] " + describe(e), -1);
        }
        String out = bos.toString();
        if (timeout) {
            out = out + "\n[超时] 命令超过 " + (timeoutMs / 1000) + " 秒未结束，已终止";
        }
        return new ExecResult(out, code);
    }

    // ---------------------------------------------------------------- 工具

    /** 以 root（或当前身份）身份返回可执行命令的路径，用于提示。 */
    public static String suPath() {
        return sSuBin;
    }

    private static Process start(String cmd) throws IOException {
        String[] argv;
        if (sRoot) {
            argv = new String[]{sSuBin, "-c", cmd};
        } else {
            argv = new String[]{"/system/bin/sh", "-c", cmd};
        }
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        Process p = pb.start();
        try {
            OutputStream os = p.getOutputStream();
            os.write("/system/bin/sh /dev/null\n".getBytes("UTF-8"));
            os.flush();
        } catch (Throwable ignored) {
            // stdin 不可用不影响绝大多数命令
        }
        return p;
    }

    private static String execRaw(String[] argv, int timeoutMs) {
        Process p = null;
        String out = null;
        try {
            p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            copyAll(p.getInputStream(), bos);
            if (p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                out = bos.toString();
            } else {
                p.destroy();
                out = bos.toString();
            }
        } catch (Throwable ignored) {
            if (p != null) {
                p.destroy();
            }
        }
        return out;
    }

    private static void copyAll(InputStream in, ByteArrayOutputStream out) throws IOException {
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    private static String describe(Throwable e) {
        String msg = e.getMessage();
        return e.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    /** 用 POSIX 单引号安全包裹单个参数。 */
    public static String quote(String arg) {
        if (arg == null) {
            return "''";
        }
        return "'" + arg.replace("'", "'\\''") + "'";
    }
}
