package com.rootterm.fm;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * 内置资源完整性校验。
 *
 * <p>构建时把 {@code assets/tfbash/*} 的 SHA-256 写进 {@link BuildAssets}；
 * 运行时读取 assets 逐个比对。被替换（二次打包塞后门）则拒绝使用。</p>
 */
public final class AssetCheck {

    private AssetCheck() {
    }

    /** 返回 null 表示通过；否则返回失败原因。 */
    public static String verify(Context ctx) {
        String[][] map = BuildAssets.MAP;
        if (map == null || map.length == 0) {
            return null; // 未注入（未加固构建）时跳过
        }
        for (String[] e : map) {
            String name = e[0];
            String expected = e[1];
            try {
                String actual = sha256Asset(ctx, "tfbash/" + name);
                if (actual == null) {
                    return "缺少资源：" + name;
                }
                if (!expected.equalsIgnoreCase(actual)) {
                    return "资源被篡改：" + name;
                }
            } catch (Throwable t) {
                return "校验异常：" + name + " " + t.getClass().getSimpleName();
            }
        }
        return null;
    }

    /**
     * 校验 /data/local/tmp/tfbash 里的副本。
     *
     * <p>{@link #verify(Context)} 只管 APK 内的 assets（防二次打包）。
     * 但真正以 root 执行的是释放到临时目录的那份 —— 该目录 shell 可写，
     * 任何能写它的进程都能掉包成后门。这里比对运行时的实际文件。</p>
     *
     * @return null 表示通过；否则返回失败原因
     */
    public static String verifyInstalled(String dir) {
        String[][] map = BuildAssets.MAP;
        if (map == null || map.length == 0) {
            return null;
        }
        for (String[] e : map) {
            String name = e[0];
            String expected = e[1];
            String out = Shell.execSync(
                    "sha256sum " + Shell.quote(dir + "/" + name) + " 2>/dev/null | cut -d' ' -f1",
                    8000);
            String actual = out == null ? "" : out.trim();
            if (actual.isEmpty()) {
                return "副本缺失：" + name;
            }
            if (!expected.equalsIgnoreCase(actual)) {
                return "副本被替换：" + name;
            }
        }
        return null;
    }

    private static String sha256Asset(Context ctx, String path) throws Exception {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(path);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return hex(md.digest());
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 计算某个 asset 的哈希（供调试输出）。 */
    public static String hashOf(Context ctx, String name) {
        try {
            return sha256Asset(ctx, "tfbash/" + name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读取全部字节（备用）。 */
    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
