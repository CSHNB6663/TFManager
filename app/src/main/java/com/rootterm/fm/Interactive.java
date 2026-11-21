package com.rootterm.fm;

import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/**
 * 交互式命令执行：启动一个长期存活的进程，把 stdin 交给终端输入行。
 *
 * <p>用途：内核驱动加载器等程序会弹菜单等输入（例如「(1 加载) / (2 不加载)」），
 * 普通一次性执行（stdin 立即 EOF）会让它们直接退出，表现为「跑一下就停」。
 * 这个类保持进程存活，{@link #write(String)} 写入标准输入，{@link #closeStdin()} 发送 EOF。</p>
 *
 * <p>有 root 时通过 {@code su -c} 启动，命令内部再 {@code exec sh} 让程序成为前台进程，
 * 从而能直接读写终端的 stdin/stdout。</p>
 */
public final class Interactive {

    /** 输出回调，全部在主线程。 */
    public interface Callback {
        void onStart();

        void onLine(String line);

        void onDone(int exitCode);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private java.lang.Process proc;
    private OutputStream stdin;
    private Thread worker;
    private volatile boolean done;
    private Callback cb;
    private final StringBuilder carry = new StringBuilder();
    private int emitted;

    private Interactive() {
    }

    /**
     * 启动交互式命令（非阻塞，内部起读线程）。
     *
     * @param cmd     要执行的命令（可带 LD_LIBRARY_PATH=... 前缀）
     * @param cb      输出回调（主线程）
     * @param timeout 无输出超时毫秒（0 = 永不超时）
     * @return 会话对象（可用于 write/closeStdin/kill）；启动失败返回 null
     */
    public static Interactive start(final String cmd, final Callback cb, int timeout) {
        final Interactive it = new Interactive();
        it.cb = cb;
        try {
            String[] argv;
            if (Shell.isRoot()) {
                argv = new String[]{Shell.suPath(), "-c", cmd};
            } else {
                argv = new String[]{"/system/bin/sh", "-c", cmd};
            }
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(true);
            java.lang.Process p = pb.start();
            it.proc = p;
            try {
                it.stdin = p.getOutputStream();
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            emit(cb, "[错误] " + t, it.emitted);
            return null;
        }

        if (cb != null) {
            MAIN.post(new Runnable() {
                public void run() {
                    cb.onStart();
                }
            });
        }

        it.worker = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop(it, timeout);
            }
        }, "interactive");
        it.worker.setDaemon(true);
        it.worker.start();
        return it;
    }

    private static void readLoop(Interactive it, int timeout) {
        InputStream in = null;
        int code = -1;
        boolean timedOut = false;
        long lastData = System.currentTimeMillis();
        try {
            if (it.proc != null) {
                in = it.proc.getInputStream();
            }
            byte[] buf = new byte[2048];
            while (true) {
                boolean got = false;
                if (in != null && in.available() > 0) {
                    int n = in.read(buf, 0, Math.min(buf.length, in.available()));
                    if (n > 0) {
                        got = true;
                        lastData = System.currentTimeMillis();
                        it.carry.append(new String(buf, 0, n, "UTF-8"));
                        drain(it);
                    }
                }

                // 关键：程序打印提示符（如「请输入选择: 」）时没有换行，
                // 这里在短暂空闲后把残留内容先刷出去，避免“输入数字后才看到菜单”。
                if (!it.carry.toString().isEmpty()
                        && System.currentTimeMillis() - lastData > 120) {
                    final String partial = it.carry.toString();
                    it.carry.setLength(0);
                    emit(it.cb, partial, it.emitted++);
                }

                if (!got && (timeout > 0 && System.currentTimeMillis() - lastData > timeout)) {
                    timedOut = true;
                    break;
                }
                if (!got && it.proc != null && !it.proc.isAlive()) {
                    drain(it);
                    try {
                        code = it.proc.exitValue();
                    } catch (Throwable ignored) {
                    }
                    break;
                }
                if (!got) {
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (it.carry.length() > 0) {
            emit(it.cb, it.carry.toString(), it.emitted++);
        }
        if (timedOut) {
            emit(it.cb, "[超时] 等待程序输入超时，已结束进程", it.emitted++);
            code = -1;
        }
        final int fc = code;
        it.done = true;
        if (it.cb != null) {
            MAIN.post(new Runnable() {
                public void run() {
                    it.cb.onDone(fc);
                }
            });
        }
    }

    /** 从残留缓冲里切出完整行；'\r' 生成「覆盖上一行」标记。 */
    private static void drain(Interactive it) {
        String s = it.carry.toString();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                emit(it.cb, s.substring(start, i), it.emitted++);
                start = i + 1;
            } else if (c == '\r') {
                boolean nextIsLf = (i + 1 < s.length() && s.charAt(i + 1) == '\n');
                if (!nextIsLf) {
                    // 进度条：覆盖上一行
                    emit(it.cb, "\u0001" + s.substring(start, i), it.emitted++);
                    start = i + 1;
                }
            }
        }
        it.carry.setLength(0);
        if (start < s.length()) {
            it.carry.append(s.substring(start));
        }
    }

    private static void emit(Callback cb, String line, int n) {
        if (cb == null || n > 20000) {
            return;
        }
        final String s = line;
        MAIN.post(new Runnable() {
            public void run() {
                cb.onLine(s);
            }
        });
    }

    /** 向标准输入写一行（自动补换行并 flush）。 */
    public boolean write(String line) {
        if (done) {
            return false;
        }
        OutputStream os = stdin;
        if (os == null) {
            return false;
        }
        try {
            os.write((line == null ? "" : line).getBytes("UTF-8"));
            os.write('\n');
            os.flush();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 发送 EOF（告诉程序没有更多输入）。 */
    public boolean closeStdin() {
        if (done) {
            return false;
        }
        OutputStream os = stdin;
        if (os == null) {
            return false;
        }
        try {
            os.flush();
            os.close();
            stdin = null;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 进程是否仍在运行。 */
    public boolean isAlive() {
        return !done && proc != null && proc.isAlive();
    }

    /** 结束进程。 */
    public void kill() {
        try {
            if (proc != null) {
                proc.destroy();
            }
        } catch (Throwable ignored) {
        }
    }
}