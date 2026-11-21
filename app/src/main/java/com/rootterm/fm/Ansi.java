package com.rootterm.fm;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简 ANSI 转义解析：把脚本输出里的颜色码（如 {@code \033[1;32m}）渲染成彩色文字。
 *
 * <p>只处理 SGR（{@code ESC[...m}）里的 0/1 与 30~37、90~97；其它控制序列直接去掉。
 * 背景是浅色终端，所以 30/37（黑/白）都映射成深色，避免看不见。</p>
 */
public final class Ansi {

    private static final Pattern SGR = Pattern.compile("\u001B\\[([0-9;]*)m");
    private static final Pattern OTHER = Pattern.compile("\u001B\\[[0-9;?]*[A-Za-z]");

    private Ansi() {
    }

    public static CharSequence parse(String text) {
        return parse(text, 0xFF191919);
    }

    public static CharSequence parse(String text, int defaultColor) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.indexOf('\u001B') < 0) {
            return text;
        }
        String cleaned = OTHER.matcher(text).replaceAll("");
        SpannableStringBuilder sb = new SpannableStringBuilder();
        Matcher m = SGR.matcher(cleaned);
        int last = 0;
        int color = defaultColor;
        boolean bold = false;
        while (m.find()) {
            append(sb, cleaned.substring(last, m.start()), color, bold);
            int[] state = applySgr(m.group(1), color, bold);
            color = state[0];
            bold = state[1] != 0;
            last = m.end();
        }
        append(sb, cleaned.substring(last), color, bold);
        return sb;
    }

    private static void append(SpannableStringBuilder sb, String part, int color, boolean bold) {
        if (part == null || part.isEmpty()) {
            return;
        }
        int start = sb.length();
        sb.append(part);
        if (color != 0) {
            sb.setSpan(new ForegroundColorSpan(color), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (bold) {
            sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** 返回 {color, bold(0/1)}。 */
    private static int[] applySgr(String codes, int color, boolean bold) {
        if (codes == null || codes.isEmpty()) {
            return new int[]{0xFF191919, 0};
        }
        for (String part : codes.split(";")) {
            int c;
            try {
                c = Integer.parseInt(part);
            } catch (Throwable t) {
                continue;
            }
            if (c == 0) {
                color = 0xFF191919;
                bold = false;
            } else if (c == 1) {
                bold = true;
            } else if (c == 22) {
                bold = false;
            } else if (c >= 30 && c <= 37) {
                color = fg(c - 30);
            } else if (c >= 90 && c <= 97) {
                color = bright(c - 90);
            } else if (c == 39) {
                color = 0xFF191919;
            }
        }
        return new int[]{color, bold ? 1 : 0};
    }

    private static int fg(int i) {
        switch (i) {
            case 0:
                return 0xFF191919;   // 黑 → 深色（浅底可读）
            case 1:
                return 0xFFE8481F;   // 红
            case 2:
                return 0xFF1FA35B;   // 绿
            case 3:
                return 0xFFB8860B;   // 黄（深一点）
            case 4:
                return 0xFF3482FF;   // 蓝
            case 5:
                return 0xFFAF52DE;   // 品红
            case 6:
                return 0xFF00838F;   // 青
            default:
                return 0xFF191919;   // 白 → 深色
        }
    }

    private static int bright(int i) {
        switch (i) {
            case 0:
                return 0xFF6B7075;
            case 1:
                return 0xFFFF5A36;
            case 2:
                return 0xFF34C759;
            case 3:
                return 0xFFF0A23C;
            case 4:
                return 0xFF5B9DFF;
            case 5:
                return 0xFFC86BFF;
            case 6:
                return 0xFF00B8B8;
            default:
                return 0xFF191919;
        }
    }
}
