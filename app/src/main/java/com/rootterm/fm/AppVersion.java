package com.rootterm.fm;

/** 版本信息（唯一来源，改版本时只改这里）。 */
public final class AppVersion {

    /**
     * 语义化版本编码：major*10000 + minor*100 + patch。
     * 例：2.7.2 → 20702；与更新源 tag（如 v2.7.2）解析规则一致，避免误判。
     */
    public static final int CODE = 21000;
    public static final String NAME = "3.0.0";

    private AppVersion() {
    }
}
