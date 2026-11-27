package com.rootterm.fm;

/** 版本信息（唯一来源，改版本时只改这里）。 */
public final class AppVersion {

    /**
     * 语义化版本编码：major*10000 + minor*100 + patch。
     * 例：4.0.1 → 40001，与更新源 tag（v4.0.1）的解析规则完全一致。
     */
    public static final int CODE = 60412;
    public static final String NAME = "6.4.12";

    private AppVersion() {
    }
}
