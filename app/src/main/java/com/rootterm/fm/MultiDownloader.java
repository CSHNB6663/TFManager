package com.rootterm.fm;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多线程分段下载（HTTP Range）。
 *
 * <p>对支持 {@code Accept-Ranges: bytes} 的服务器（GitHub Release CDN 支持）
 * 把文件切成 N 段并发下载，显著提速；不支持时自动回退单线程。</p>
 */
public final class MultiDownloader {

    /** 进度回调（可能来自任意线程）。 */
    public interface Progress {
        void onProgress(long done, long total);
    }

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;

    private MultiDownloader() {
    }

    /**
     * 下载到文件。
     *
     * @param url      下载地址
     * @param out      输出文件（会被覆盖）
     * @param threads  并发线程数（1~8，建议 4~6）
     * @param p        进度回调（可为 null）
     * @return 是否成功
     */
    public static boolean download(String url, File out, int threads, Progress p)
            throws Exception {
        int n = Math.max(1, Math.min(threads, 8));

        // 1) 探测：是否支持 Range，以及总大小
        long total = -1;
        boolean rangeOk = false;
        HttpURLConnection probe = open(url, "bytes=0-0");
        try {
            int code = probe.getResponseCode();
            if (code == 206) {
                rangeOk = true;
                total = parseTotal(probe.getHeaderField("Content-Range"));
            } else if (code == 200) {
                total = probe.getContentLength();
            } else if (code >= 300 && code < 400) {
                // 手动跟随后仍 3xx，视为失败
                throw new Exception("HTTP " + code);
            } else {
                throw new Exception("HTTP " + code);
            }
        } finally {
            try {
                probe.disconnect();
            } catch (Throwable ignored) {
            }
        }

        if (total <= 0) {
            rangeOk = false;
        }

        // 2) 不支持 Range 或文件太小 → 单线程
        if (!rangeOk || total < 2 * 1024 * 1024 || n == 1) {
            return single(url, out, p);
        }

        // 3) 分段并发
        final long fTotal = total;
        long seg = total / n;
        final AtomicLong done = new AtomicLong(0);
        final long[] segDone = new long[n];
        Thread[] ts = new Thread[n];
        final Throwable[] err = new Throwable[n];

        // 预分配文件，避免多线程写入时互相阻塞
        RandomAccessFile raf = new RandomAccessFile(out, "rw");
        try {
            raf.setLength(total);
        } finally {
            try {
                raf.close();
            } catch (Throwable ignored) {
            }
        }

        for (int i = 0; i < n; i++) {
            final int idx = i;
            final long start = i * seg;
            final long end = (i == n - 1) ? (total - 1) : (start + seg - 1);
            ts[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        fetchRange(url, out, start, end, new Progress() {
                            @Override
                            public void onProgress(long d, long t) {
                                long delta = d - segDone[idx];
                                segDone[idx] = d;
                                long all = done.addAndGet(delta);
                                if (p != null) {
                                    p.onProgress(all, fTotal);
                                }
                            }
                        });
                    } catch (Throwable t) {
                        err[idx] = t;
                    }
                }
            }, "dl-" + i);
            ts[i].start();
        }

        for (Thread t : ts) {
            try {
                t.join();
            } catch (InterruptedException ignored) {
            }
        }

        for (Throwable t : err) {
            if (t != null) {
                throw new Exception("分片下载失败: " + t.getMessage());
            }
        }

        if (out.length() != total) {
            throw new Exception("文件大小不符（" + out.length() + "/" + total + "）");
        }
        if (p != null) {
            p.onProgress(total, total);
        }
        AppLog.log("DL", "多线程完成 " + n + " 线程 · " + total + " 字节");
        return true;
    }

    // ---------------------------------------------------------------- 分片

    private static void fetchRange(String url, File out, long start, long end,
                                   Progress p) throws Exception {
        HttpURLConnection conn = open(url, "bytes=" + start + "-" + end);
        try {
            int code = conn.getResponseCode();
            if (code != 206 && code != 200) {
                throw new Exception("HTTP " + code);
            }
            InputStream in = conn.getInputStream();
            RandomAccessFile raf = new RandomAccessFile(out, "rw");
            try {
                raf.seek(start);
                byte[] buf = new byte[65536];
                long done = 0;
                int r;
                while ((r = in.read(buf)) > 0) {
                    raf.write(buf, 0, r);
                    done += r;
                    if (p != null) {
                        p.onProgress(done, end - start + 1);
                    }
                }
            } finally {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                }
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        } finally {
            try {
                conn.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- 单线程兜底

    private static boolean single(String url, File out, Progress p) throws Exception {
        HttpURLConnection conn = open(url, null);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code);
            }
            long total = conn.getContentLength();
            InputStream in = conn.getInputStream();
            FileOutputStream fo = new FileOutputStream(out);
            try {
                byte[] buf = new byte[65536];
                long done = 0;
                int r;
                long lastUi = 0;
                while ((r = in.read(buf)) > 0) {
                    fo.write(buf, 0, r);
                    done += r;
                    long now = System.currentTimeMillis();
                    if (p != null && now - lastUi > 120) {
                        lastUi = now;
                        p.onProgress(done, total);
                    }
                }
                if (p != null) {
                    p.onProgress(done, total);
                }
            } finally {
                try {
                    fo.close();
                } catch (Throwable ignored) {
                }
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            AppLog.log("DL", "单线程完成 " + out.length() + " 字节");
            return true;
        } finally {
            try {
                conn.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 打开连接，手动跟随重定向（保留 Range 头）。 */
    private static HttpURLConnection open(String url, String range) throws Exception {
        String cur = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(cur).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "TFManager/" + AppVersion.NAME);
            if (range != null) {
                conn.setRequestProperty("Range", range);
            }
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
                if (loc == null || loc.isEmpty()) {
                    throw new Exception("重定向缺少 Location");
                }
                cur = loc.startsWith("http") ? loc
                        : new URL(new URL(cur), loc).toString();
                continue;
            }
            return conn;
        }
        throw new Exception("重定向次数过多");
    }

    /** 从 Content-Range: bytes 0-0/12345 里取总大小。 */
    private static long parseTotal(String contentRange) {
        if (contentRange == null) {
            return -1;
        }
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0) {
            return -1;
        }
        try {
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (Throwable t) {
            return -1;
        }
    }
}
