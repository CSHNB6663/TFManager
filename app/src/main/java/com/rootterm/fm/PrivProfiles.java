package com.rootterm.fm;

/**
 * 提权兼容档案（仅元数据：机型代号 + 内核 release 字符串）。
 *
 * <p><b>本类不含任何内核符号偏移地址</b>，也不含 exploit 载荷。
 * 仅用于在「提权」页判断当前内核是否属于已知可提权的构建，给用户参考。</p>
 */
public final class PrivProfiles {

    /** {机型代号, 内核 release}。 */
    public static final String[][] PROFILES = {
            {"ace6t", "6.12.69-android16-6-g242e1a07f878-ab15865446-4k"},
            {"annibale", "6.6.118-android15-8-g0908354dac1b-ab16143355-4k"},
            {"athens", "6.12.69-android16-6-g0d80ee00f747-ab15461283-4k"},
            {"athens4040", "6.12.69-android16-6-g0a1dc0413ec7-ab16271006-4k"},
            {"byron", "6.12.69-android16-6-g43b5ab7e247d-abogki561813157-4k"},
            {"f72a", "6.6.118-android15-8-gf72a02f47f4b-abogki551678766-4k"},
            {"findn5", "6.6.118-android15-8-gc4127a25dcf3-ab15863337-4k"},
            {"k15pro", "6.6.118-android15-8-g93e223c276e7-abogki500782043-4k"},
            {"k66_127", "6.6.127-android15-8-g3b723d0e8507-abogki558077879-4k"},
            {"miro", "6.6.118-android15-8-gb9cc6ec16bc8-abogki536571621-4k"},
            {"neo7se", "6.6.118-android15-8-ge58033dc8ea6-abogki498046332-4k"},
            {"pkj110", "6.6.118-android15-8-g9bc34d5b0c79-abogki537459655-4k"},
            {"popsicle", "6.12.69-android16-6-g43b5ab7e247d-abogki558343811-4k"},
            {"shuntian", "6.12.69-android16-6-g586bfab1b9c5-abogki536749445-4k"},
            {"zorn", "6.1.157-android14-11-g2de7246565ac-mi"}
    };

    private PrivProfiles() {
    }

    /** 按内核 release 精确匹配，返回机型代号；无匹配返回 null。 */
    public static String match(String kernelRelease) {
        if (kernelRelease == null || kernelRelease.trim().isEmpty()) {
            return null;
        }
        String k = kernelRelease.trim();
        for (String[] p : PROFILES) {
            if (p[1].equalsIgnoreCase(k)) {
                return p[0];
            }
        }
        return null;
    }

    public static int size() {
        return PROFILES.length;
    }
}
