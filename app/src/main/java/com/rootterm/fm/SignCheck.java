package com.rootterm.fm;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.security.MessageDigest;

/**
 * 安装包签名校验：确认当前 APK 是由本项目密钥签名的。
 *
 * <p>用于提高重打包/二次分发成本——被换签名重新打包后无法正常启动。
 * 期望的证书指纹由构建脚本在编译前生成到 {@link BuildCert}。</p>
 */
public final class SignCheck {

    private SignCheck() {
    }

    /** 返回 null 表示校验通过，否则返回原因。 */
    public static String verify(Context ctx) {
        String expected = BuildCert.SHA256;
        if (expected == null || expected.isEmpty() || "unknown".equals(expected)) {
            return null; // 未注入指纹（未加固构建）时跳过
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(),
                    PackageManager.GET_SIGNATURES);
            if (pi == null || pi.signatures == null || pi.signatures.length == 0) {
                return "无法读取签名";
            }
            Signature sig = pi.signatures[0];
            String actual = sha256(sig.toByteArray());
            if (expected.equalsIgnoreCase(actual)) {
                return null;
            }
            return "签名不匹配（可能被重新打包）";
        } catch (Throwable t) {
            return "校验异常：" + t.getClass().getSimpleName();
        }
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(data);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
