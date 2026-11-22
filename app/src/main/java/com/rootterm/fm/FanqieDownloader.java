package com.rootterm.fm;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 番茄小说下载（网页公开内容）。
 *
 * <p><b>能力边界（务必知晓）：</b></p>
 * <ul>
 *   <li>书籍页公开：书名、作者、章节 ID 列表可解析；</li>
 *   <li>正文：未登录只能拿到<b>试读片段</b>，且番茄对部分字符做了
 *       <b>自定义字体混淆</b>（形近字替换），因此导出的 TXT 可能缺字/错字；</li>
 *   <li>完整正文需在番茄小说 App 内阅读，本工具不绕过鉴权。</li>
 * </ul>
 */
public final class FanqieDownloader {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final int TIMEOUT = 20000;

    /** 书籍信息。 */
    public static final class Book {
        public String bookId = "";
        public String title = "";
        public String author = "";
        public int total;
        public final List<String> chapterIds = new ArrayList<String>();
        /** 章节标题（与 chapterIds 一一对应，可能为空）。 */
        public final List<String> chapterTitles = new ArrayList<String>();
        /** 是否锁定/付费（true 表示未解锁）。 */
        public final List<Boolean> chapterLocked = new ArrayList<Boolean>();
    }

    /** 章节。 */
    public static final class Chapter {
        public String id = "";
        public String title = "";
        public String text = "";
    }

    /** 进度回调（后台线程调用）。 */
    public interface Progress {
        void onProgress(int done, int total, String message);
    }

    private FanqieDownloader() {
    }

    // ---------------------------------------------------------------- 解析

    /** 从分享链接/纯 ID 里取出书籍 ID（支持番茄短链，必要时联网解析）。 */
    public static String parseBookId(String link) {
        String id = parseBookIdLocal(link);
        if (id != null) {
            return id;
        }
        // 短链（changdunovel.com/t/xxx、fqnovel 分享链等）：联网跟随跳转取 book_id
        try {
            return resolveShortLink(link);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 本地规则解析（不联网）。 */
    private static String parseBookIdLocal(String link) {
        if (link == null) {
            return null;
        }
        String s = link.trim();
        Matcher m = Pattern.compile("fanqienovel\\.com/page/(\\d+)").matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        m = Pattern.compile("book_id=(\\d+)").matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        m = Pattern.compile("^(\\d{10,})$").matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        // 兜底：取最长的一串数字
        m = Pattern.compile("(\\d{15,25})").matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /** 跟随短链跳转，从最终 URL 或页面内容里找 book_id。 */
    private static String resolveShortLink(String link) throws Exception {
        if (link == null || !link.startsWith("http")) {
            return null;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(link.trim()).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) {
                return null;
            }
            // 先看最终 URL
            String finalUrl = conn.getURL() == null ? "" : conn.getURL().toString();
            Matcher m = Pattern.compile("book_id=(\\d+)").matcher(finalUrl);
            if (m.find()) {
                return m.group(1);
            }
            m = Pattern.compile("/page/(\\d+)").matcher(finalUrl);
            if (m.find()) {
                return m.group(1);
            }
            // 再读页面内容
            InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            String body = bos.toString("UTF-8");
            for (String re : new String[]{"book_id=(\\d+)", "\"bookId\":\"(\\d+)\"",
                    "/page/(\\d+)", "bookId=(\\d+)"}) {
                Matcher mm = Pattern.compile(re).matcher(body);
                if (mm.find()) {
                    return mm.group(1);
                }
            }
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 抓书籍信息（书名 / 作者 / 章节 ID 列表）。 */
    public static Book fetchBook(String bookId) throws Exception {
        String html = get("https://fanqienovel.com/page/" + bookId);
        Book b = new Book();
        b.bookId = bookId;

        String t = firstGroup(html, "<title>(.*?)</title>");
        if (t != null) {
            b.title = t.replace("完整版在线免费阅读", "").replace("在线免费阅读", "")
                    .replace("_番茄小说官网", "").replace("_番茄小说", "").trim();
        }
        String name = firstGroup(html, "\"bookName\":\"([^\"]+)\"");
        if (name != null && !name.trim().isEmpty()) {
            b.title = name.trim();
        }
        String author = firstGroup(html, "\"author\":\"([^\"]+)\"");
        if (author != null) {
            b.author = author.trim();
        }
        String total = firstGroup(html, "\"chapterTotal\":(\\d+)");
        if (total != null) {
            try {
                b.total = Integer.parseInt(total);
            } catch (Throwable ignored) {
            }
        }
        // 章节 ID 列表：优先 itemIds 数组
        Matcher m = Pattern.compile("\"itemIds\":\\[(.*?)\\]").matcher(html);
        if (m.find()) {
            Matcher id = Pattern.compile("\"(\\d{10,25})\"").matcher(m.group(1));
            while (id.find()) {
                b.chapterIds.add(id.group(1));
            }
        }
        if (b.chapterIds.isEmpty()) {
            Matcher id = Pattern.compile("/reader/(\\d{10,25})").matcher(html);
            while (id.find()) {
                if (!b.chapterIds.contains(id.group(1))) {
                    b.chapterIds.add(id.group(1));
                }
            }
        }
        if (b.title.isEmpty()) {
            b.title = "fanqie-" + bookId;
        }
        // 目录 API：拿章节名 + 是否锁定
        try {
            String dir = get("https://fanqienovel.com/api/reader/directory/detail?bookId=" + bookId);
            Matcher cm = Pattern.compile(
                    "\\{\"itemId\":\"(\\d+)\"[^}]*?\"title\":\"(.*?)\"[^}]*?\"isChapterLock\":(true|false)")
                    .matcher(dir);
            List<String> ids = new ArrayList<String>();
            List<String> titles = new ArrayList<String>();
            List<Boolean> locked = new ArrayList<Boolean>();
            while (cm.find()) {
                ids.add(cm.group(1));
                titles.add(jsonUnescape(cm.group(2)));
                locked.add("true".equals(cm.group(3)));
            }
            if (!ids.isEmpty()) {
                b.chapterIds.clear();
                b.chapterIds.addAll(ids);
                b.chapterTitles.clear();
                b.chapterTitles.addAll(titles);
                b.chapterLocked.clear();
                b.chapterLocked.addAll(locked);
            }
        } catch (Throwable ignored) {
        }
        return b;
    }

    /** 抓单章（标题 + 公开可见的正文片段）。 */
    public static Chapter fetchChapter(String chapterId) throws Exception {
        String html = get("https://fanqienovel.com/reader/" + chapterId);
        Chapter c = new Chapter();
        c.id = chapterId;

        int cd = html.indexOf("\"chapterData\":");
        String seg = cd >= 0 ? html.substring(cd, Math.min(html.length(), cd + 60000)) : html;

        String title = firstGroup(seg, "\"title\":\"(.*?)\"");
        if (title != null) {
            c.title = jsonUnescape(title);
        }
        String content = firstGroup(seg, "\"content\":\"(.*?)\",\"");
        if (content == null) {
            content = firstGroup(seg, "\"content\":\"(.*?)\"\\}");
        }
        if (content != null) {
            String raw = jsonUnescape(content);
            c.text = FanqieFontMap.fix(stripHtml(raw));   // 还原字体混淆字符
        }
        if (c.text == null) {
            c.text = "";
        }
        return c;
    }

    // ---------------------------------------------------------------- 下载

    /**
     * 下载整本书（最多 maxChapters 章）为 TXT。
     *
     * @return 生成的 txt 文件
     */
    public static File download(String link, int maxChapters, Progress p) throws Exception {
        return download(link, maxChapters, "", p);
    }

    public static File download(String link, int maxChapters, String cookieStr,
                                Progress p) throws Exception {
        setCookie(cookieStr);
        String bookId = parseBookId(link);
        if (bookId == null) {
            throw new Exception("链接里找不到书籍 ID\n请粘贴形如 https://fanqienovel.com/page/7143038691944959011 的链接");
        }
        if (p != null) {
            p.onProgress(0, 1, "读取书籍信息…");
        }
        Book book = fetchBook(bookId);
        if (book.chapterIds.isEmpty()) {
            throw new Exception("没有解析到章节列表（可能是短链或页面结构变化）");
        }
        int total = Math.min(maxChapters <= 0 ? book.chapterIds.size() : maxChapters,
                book.chapterIds.size());

        StringBuilder sb = new StringBuilder();
        sb.append(book.title).append('\n');
        if (!book.author.isEmpty()) {
            sb.append("作者：").append(book.author).append('\n');
        }
        sb.append("来源：番茄小说网（公开页面）\n");
        sb.append("说明：未登录仅能获取试读片段，部分字符受字体混淆可能错字。\n");
        sb.append("章节总数：").append(book.total > 0 ? book.total : book.chapterIds.size())
                .append("，本次导出前 ").append(total).append(" 章\n");
        sb.append("————————————\n\n");

        int ok = 0;
        for (int i = 0; i < total; i++) {
            String cid = book.chapterIds.get(i);
            if (p != null) {
                p.onProgress(i, total, "第 " + (i + 1) + "/" + total + " 章…");
            }
            boolean locked = i < book.chapterLocked.size() && book.chapterLocked.get(i);
            String name = i < book.chapterTitles.size() ? book.chapterTitles.get(i) : "";
            try {
                Chapter c = fetchChapter(cid);
                String title = c.title == null || c.title.isEmpty() ? name : c.title;
                sb.append(title.isEmpty() ? ("第" + (i + 1) + "章") : title).append("\n\n");
                if (c.text == null || c.text.trim().isEmpty()) {
                    sb.append(locked ? "（该章未解锁，需在番茄 App 内购买/开通会员）"
                            : "（该章正文为空，可能需要登录）").append("\n\n");
                } else {
                    sb.append(c.text).append("\n\n");
                    ok++;
                }
            } catch (Throwable t) {
                sb.append("（第 ").append(i + 1).append(" 章获取失败：")
                        .append(t.getMessage()).append("）\n\n");
            }
        }

        File out = new File("/sdcard/Download", safeName(book.title) + ".txt");
        FileOutputStream fo = new FileOutputStream(out);
        try {
            fo.write(sb.toString().getBytes("UTF-8"));
        } finally {
            try {
                fo.close();
            } catch (Throwable ignored) {
            }
        }
        if (p != null) {
            p.onProgress(total, total, "完成：" + ok + "/" + total + " 章");
        }
        return out;
    }

    // ---------------------------------------------------------------- 工具

    private static String cookie = "";

    /** 设置登录 cookie（来自 WebView 登录）。 */
    public static void setCookie(String c) {
        cookie = c == null ? "" : c;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            conn.setRequestProperty("Referer", "https://fanqienovel.com/");
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code);
            }
            InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            return bos.toString("UTF-8");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String firstGroup(String src, String regex) {
        if (src == null) {
            return null;
        }
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(src);
        return m.find() ? m.group(1) : null;
    }

    /** 反转义 JSON 字符串（unicode 转义、斜杠、引号、换行）。 */
    private static String jsonUnescape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char e = s.charAt(++i);
            switch (e) {
                case 'n':
                    sb.append('\n');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case '"':
                    sb.append('"');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                case '/':
                    sb.append('/');
                    break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (Throwable ignored) {
                        }
                    }
                    break;
                default:
                    sb.append(e);
            }
        }
        return sb.toString();
    }

    /** 去掉 HTML 标签，保留段落换行。 */
    private static String stripHtml(String html) {
        if (html == null) {
            return "";
        }
        String s = html.replaceAll("(?i)</p\\s*>", "\n");
        s = s.replaceAll("<[^>]+>", "");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
        s = s.replaceAll("[ \\t]+\\n", "\n");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    private static String safeName(String name) {
        String n = name == null ? "novel" : name.trim();
        n = n.replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_");
        if (n.length() > 60) {
            n = n.substring(0, 60);
        }
        return n.isEmpty() ? "novel" : n;
    }
}