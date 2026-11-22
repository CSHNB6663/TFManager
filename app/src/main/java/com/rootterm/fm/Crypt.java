package com.rootterm.fm;

import java.nio.charset.Charset;

/**
 * 运行时字符串解密（配合构建脚本加密，降低静态分析可读性）。
 *
 * <p>密文格式：base64( XOR(明文, 滚动密钥) )。密钥由构建时随机生成后注入
 * {@link BuildKey}，因此每次出包密钥都不同，反编译也拿不到明文串。</p>
 */
public final class Crypt {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private Crypt() {
    }

    /** 解密一个 base64+XOR 字符串；失败返回空串。 */
    public static String d(String cipherB64) {
        if (cipherB64 == null || cipherB64.isEmpty()) {
            return "";
        }
        try {
            byte[] data = android.util.Base64.decode(cipherB64,
                    android.util.Base64.NO_WRAP);
            byte[] key = BuildKey.get();
            byte[] out = new byte[data.length];
            for (int i = 0; i < data.length; i++) {
                out[i] = (byte) (data[i] ^ key[i % key.length]);
            }
            return new String(out, UTF8);
        } catch (Throwable t) {
            return "";
        }
    }
}
