package com.rootterm.fm;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 内置 bash 运行环境（从 Termux 官方仓库打包，GPL-3.0 / 依赖库同样来自 Termux 仓库）。
 *
 * <p>首次需要时：把 assets/tfbash 里的 bash + 依赖 .so 释放到 App 私有目录，
 * 再用 root 复制到 {@code /data/local/tmp/tfbash}（该目录 root 可执行，避免 app 数据目录的
 * noexec/SELinux 限制），并用 {@code LD_LIBRARY_PATH} 指向同目录运行。</p>
 *
 * <p>之所以内置：很多内核/驱动脚本用 bash 语法（数组、function、(( ))），
 * 而 Android 自带的 /system/bin/sh 是 mksh，会报 {@code unexpected '('}。</p>
 */
public final class BashKit {

    /** 运行目录（root 可执行）。 */
    public static final String DIR = "/data/local/tmp/tfbash";
    /** 内置 bash 路径。 */
    public static final String BASH = DIR + "/bash";

    private static final String ASSET_DIR = "tfbash";
    private static final String[] FILES = {
            "bash",
            "libandroid-support.so",
            "libiconv.so",
            "libreadline.so.8",
            "libncursesw.so.6",
    };

    private BashKit() {
    }

    /** bash 启动必须带 LD_LIBRARY_PATH（.so 与 bash 同目录），否则会 CANNOT LINK。 */
    private static String envPrefix() {
        return "LD_LIBRARY_PATH=" + DIR + " ";
    }

    public static boolean isInstalled() {
        String out = Shell.execSync(
                "[ -x " + BASH + " ] && " + envPrefix() + BASH
                        + " --version 2>/dev/null | head -n 1", 8000).trim();
        return out.contains("GNU bash");
    }

    /**
     * 确保内置 bash 可用；成功返回 bash 路径，失败返回 null。
     * <b>阻塞调用，必须在后台线程。</b>
     */
    public static String ensure(Context ctx) {
        // 内置 bash 完整性校验：被替换则拒绝使用
        String bad = AssetCheck.verify(ctx);
        if (bad != null) {
            return null;
        }
        // 已释放过：先校验临时副本有没有被掉包，再决定复用还是重装
        if (isInstalled()) {
            String stale = AssetCheck.verifyInstalled(DIR);
            if (stale == null) {
                return BASH;
            }
            AppLog.log("BASH", "临时目录校验失败，重新释放: " + stale);
        }
        try {
            File dir = new File(ctx.getFilesDir(), ASSET_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            for (String name : FILES) {
                File out = new File(dir, name);
                copyAsset(ctx, ASSET_DIR + "/" + name, out);
            }
            // 用 root 复制到 /data/local/tmp/tfbash 并给执行权限。
            // 目录收紧到本应用 uid：避免 shell 等其它进程在同一目录掉包二进制。
            int uid = android.os.Process.myUid();
            String cmd = "rm -rf " + DIR + " && mkdir -p " + DIR
                    + " && cp -f " + Shell.quote(dir.getAbsolutePath()) + "/* " + DIR + "/"
                    + " && chmod 755 " + DIR + "/*"
                    + " && chown -R " + uid + ":" + uid + " " + DIR
                    + " && chmod 700 " + DIR
                    + " && " + envPrefix() + BASH + " --version 2>/dev/null | head -n 1";
            String result = Shell.execSync(cmd, 30000);
            if (result != null && result.contains("GNU bash")) {
                // 释放后立刻复核哈希，确保落地内容与 APK 内一致
                String fresh = AssetCheck.verifyInstalled(DIR);
                if (fresh != null) {
                    AppLog.log("BASH", "释放后校验失败: " + fresh);
                    Shell.execSync("rm -rf " + DIR, 8000);
                    return null;
                }
                return BASH;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 运行时给 bash 加上库路径前缀（.so 与 bash 同目录）。 */
    public static String command(String bashPath, String scriptPath) {
        if (bashPath != null && bashPath.startsWith(DIR)) {
            return "LD_LIBRARY_PATH=" + DIR + " "
                    + Shell.quote(bashPath) + " " + Shell.quote(scriptPath) + " 2>&1";
        }
        return Shell.quote(bashPath) + " " + Shell.quote(scriptPath) + " 2>&1";
    }

    private static void copyAsset(Context ctx, String asset, File out) throws Exception {
        if (out.exists() && out.length() > 1024) {
            return;
        }
        InputStream in = null;
        FileOutputStream os = null;
        try {
            in = ctx.getAssets().open(asset);
            os = new FileOutputStream(out);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.flush();
        } finally {
            try {
                if (os != null) {
                    os.close();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
