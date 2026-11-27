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

    /** 当前下载使用的登录态（单次下载内有效）。 */
    private static volatile String sCookie;
    private static volatile String sReferer;

    /** 设置后续下载使用的登录态（VIP 曲目 CDN 会校验）。传 null 清除。 */
    public static void setAuth(String cookie, String referer) {
        sCookie = (cookie == null || cookie.isEmpty()) ? null : cookie;
        sReferer = (referer == null || referer.isEmpty()) ? null : referer;
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
        return download(url, out, threads, p, null, null);
    }

    /**
     * 带凭证下载。VIP 曲目的 CDN 会校验 Cookie/Referer，
     * 不带上登录态就只能拿到 403 或错误页。
     */
    public static boolean download(String url, File out, int threads, Progress p,
                                   String cookie, String referer) throws Exception {
        sCookie = (cookie == null || cookie.isEmpty()) ? null : cookie;
        sReferer = (referer == null || referer.isEmpty()) ? null : referer;
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
            String ct = probe.getContentType();
            if (isTextual(ct)) {
                throw new Exception("该地址返回的是网页/错误信息，不是音频（多半没有版权或需要会员）：" + shortType(ct));
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
            boolean okSingle = single(url, out, p);
            verifyAudioOrThrow(out);
            return okSingle;
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
        verifyAudioOrThrow(out);
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

    /** 这些 Content-Type 说明拿到的是网页/接口报文，不是音频。 */
    private static boolean isTextual(String ct) {
        if (ct == null) {
            return false;
        }
        String t = ct.toLowerCase();
        return t.startsWith("text/") || t.contains("html") || t.contains("json")
                || t.contains("xml") || t.contains("javascript");
    }

    private static String shortType(String ct) {
        if (ct == null) {
            return "未知类型";
        }
        int i = ct.indexOf(';');
        return i > 0 ? ct.substring(0, i).trim() : ct.trim();
    }

    /**
     * 下完再验一遍文件头：真正的音频要么有 ID3/FLAC/Ogg 标记，要么是 MPEG 帧同步。
     * 不是音频就删掉，别把网页存成 .mp3 留在用户目录里。
     */
    private static void verifyAudioOrThrow(File f) throws Exception {
        if (f == null || !f.exists()) {
            throw new Exception("下载失败：文件不存在");
        }
        long len = f.length();
        if (len < 4096) {
            f.delete();
            throw new Exception("下载内容过小（" + len + " 字节），不是完整音频");
        }
        byte[] head = new byte[12];
        java.io.FileInputStream in = null;
        int got;
        try {
            in = new java.io.FileInputStream(f);
            got = in.read(head);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (got < 4) {
            f.delete();
            throw new Exception("下载内容无法识别");
        }
        boolean id3 = head[0] == 'I' && head[1] == 'D' && head[2] == '3';
        boolean ftyp = head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
        boolean flac = head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C';
        boolean ogg = head[0] == 'O' && head[1] == 'g' && head[2] == 'g' && head[3] == 'S';
        boolean mpeg = (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0;
        boolean riff = head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F';
        boolean html = head[0] == '<' || (head[0] == 0xEF && head[1] == 0xBB && head[2] == 0xBF);
        if (id3 || ftyp || flac || ogg || mpeg || riff) {
            return;
        }
        f.delete();
        if (html) {
            throw new Exception("下载到的是一段网页（HTML），不是音频：该曲目可能没有版权或需要会员");
        }
        throw new Exception("下载内容不是可识别的音频格式，已删除");
    }

    /** 打开连接，手动跟随重定向（保留 Range 头；http 优先升级为 https）。 */
    private static HttpURLConnection open(String url, String range) throws Exception {
        String cur = preferHttps(url);
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection conn;
            int code;
            try {
                conn = (HttpURLConnection) new URL(cur).openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", "TFManager/" + AppVersion.NAME);
            if (sReferer != null) {
                conn.setRequestProperty("Referer", sReferer);
            }
            if (sCookie != null) {
                conn.setRequestProperty("Cookie", sCookie);
            }
                if (range != null) {
                    conn.setRequestProperty("Range", range);
                }
                code = conn.getResponseCode();
            } catch (Exception e) {
                // https 不通时退回 http（部分 CDN 只支持明文）
                if (cur.startsWith("https://")) {
                    cur = "http://" + cur.substring(8);
                    continue;
                }
                throw e;
            }
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
                if (loc == null || loc.isEmpty()) {
                    throw new Exception("重定向缺少 Location");
                }
                cur = preferHttps(loc.startsWith("http") ? loc
                        : new URL(new URL(cur), loc).toString());
                continue;
            }
            return conn;
        }
        throw new Exception("重定向次数过多");
    }

    /** http:// → https://（失败会自动回退）。 */
    private static String preferHttps(String u) {
        if (u != null && u.startsWith("http://")) {
            return "https://" + u.substring(7);
        }
        return u;
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
