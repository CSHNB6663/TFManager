package com.rootterm.fm;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 在线音乐搜索/下载（网易云音乐 + QQ音乐），走「网页接口 + 登录 Cookie」的方式。
 *
 * <p>搜索：两家都有公开的网页搜索接口，无需登录。</p>
 * <p>下载：</p>
 * <ul>
 *   <li>网易云 —— {@code /song/media/outer/url?id=X.mp3} 会 302 到 CDN 的 MP3，直接可下</li>
 *   <li>QQ音乐 —— 需要 vkey，匿名拿不到 purl，必须带登录 Cookie</li>
 * </ul>
 */
public final class MusicSearch {

    public static final String SRC_NETEASE = "netease";
    public static final String SRC_QQ = "qq";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final int TIMEOUT = 15000;

    /** 一首歌。 */
    public static final class Track {
        public String source = SRC_NETEASE;
        public String id = "";       // 网易云 song id / QQ songmid
        public String name = "";
        public String artist = "";
        public int duration;         // 秒
    }

    /** 搜索结果。 */
    public static final class Result {
        public boolean ok;
        public String error = "";
        public final List<Track> tracks = new ArrayList<Track>();
    }

    private MusicSearch() {
    }

    // ================================================================ 搜索

    /** 搜索（阻塞，后台线程调用）。 */
    public static Result search(String source, String keyword) {
        Result r = new Result();
        if (keyword == null || keyword.trim().isEmpty()) {
            r.error = "请输入关键词";
            return r;
        }
        try {
            if (SRC_QQ.equals(source)) {
                return searchQQ(keyword.trim());
            }
            return searchNetease(keyword.trim());
        } catch (Throwable t) {
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return r;
        }
    }

    private static Result searchNetease(String kw) throws Exception {
        Result r = new Result();
        String url = "https://music.163.com/api/search/get?s="
                + URLEncoder.encode(kw, "UTF-8") + "&type=1&limit=25&offset=0";
        String body = httpGet(url, "https://music.163.com/", null);
        int i = body.indexOf("\"songs\"");
        if (i < 0) {
            r.error = "网易云未返回歌曲（可能触发了风控）";
            return r;
        }
        int arrStart = body.indexOf('[', i);
        int arrEnd = matchBracket(body, arrStart);
        if (arrStart < 0 || arrEnd < 0) {
            r.error = "解析失败";
            return r;
        }
        for (String obj : splitObjects(body, arrStart + 1, arrEnd)) {
            Track t = new Track();
            t.source = SRC_NETEASE;
            t.id = topNum(obj, "id");
            t.name = topStr(obj, "name");
            t.artist = firstArtist(obj);
            long ms = parseLong(topNum(obj, "duration"));
            t.duration = (int) (ms / 1000);
            if (!t.id.isEmpty() && !t.name.isEmpty()) {
                r.tracks.add(t);
            }
        }
        r.ok = true;
        AppLog.log("MUSIC", "netease '" + kw + "' → " + r.tracks.size());
        return r;
    }

    private static Result searchQQ(String kw) throws Exception {
        Result r = new Result();
        String url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w="
                + URLEncoder.encode(kw, "UTF-8") + "&format=json&n=25&p=1";
        String body = httpGet(url, "https://y.qq.com/", null);
        int i = body.indexOf("\"list\"");
        if (i < 0) {
            r.error = "QQ音乐未返回歌曲";
            return r;
        }
        int arrStart = body.indexOf('[', i);
        int arrEnd = matchBracket(body, arrStart);
        if (arrStart < 0 || arrEnd < 0) {
            r.error = "解析失败";
            return r;
        }
        for (String obj : splitObjects(body, arrStart + 1, arrEnd)) {
            Track t = new Track();
            t.source = SRC_QQ;
            t.id = str(obj, "songmid");
            t.name = str(obj, "songname");
            t.artist = qqSinger(obj);
            t.duration = (int) parseLong(num(obj, "interval"));
            if (!t.id.isEmpty() && !t.name.isEmpty()) {
                r.tracks.add(t);
            }
        }
        r.ok = true;
        AppLog.log("MUSIC", "qq '" + kw + "' → " + r.tracks.size());
        return r;
    }

    // ================================================================ 下载地址

    /**
     * 解析可直接下载的音频地址。
     *
     * @param cookie 该平台的登录 cookie（网易云可空，QQ 必填）
     * @param uin    QQ 号（从 cookie 里取，可空）
     */
    public static String downloadUrl(Track t, String cookie, String uin) throws Exception {
        if (t == null) {
            return null;
        }
        if (SRC_QQ.equals(t.source)) {
            return resolveQQ(t.id, cookie, uin);
        }
        // 网易云：外链会 302 到 CDN，交给下载器跟随
        return "https://music.163.com/song/media/outer/url?id=" + t.id + ".mp3";
    }

    private static String resolveQQ(String mid, String cookie, String uin) throws Exception {
        String guid = "1000" + (System.currentTimeMillis() % 100000000L);
        String payload = "{\"req_0\":{\"module\":\"vkey.GetVkeyServer\",\"method\":\"CgiGetVkey\","
                + "\"param\":{\"guid\":\"" + guid + "\",\"songmid\":[\"" + mid + "\"],"
                + "\"songtype\":[0],\"uin\":\"" + (uin == null ? "0" : uin) + "\","
                + "\"loginflag\":1,\"platform\":\"20\"}}}";
        String body = httpPost("https://u.y.qq.com/cgi-bin/musicu.fcg", payload,
                "https://y.qq.com/", cookie);
        int i = body.indexOf("\"purl\"");
        if (i < 0) {
            return null;
        }
        String purl = str(body.substring(i - 1), "purl");
        String sip = "";
        int j = body.indexOf("\"sip\"");
        if (j >= 0) {
            int a = body.indexOf('[', j);
            int b = body.indexOf(']', a);
            if (a > 0 && b > a) {
                sip = str(body.substring(a, b), "");
                if (sip.isEmpty()) {
                    sip = firstQuoted(body.substring(a, b));
                }
            }
        }
        if (purl == null || purl.isEmpty()) {
            return null;
        }
        if (!sip.endsWith("/")) {
            sip = sip + "/";
        }
        return sip + purl;
    }

    // ================================================================ 网络

    private static String httpGet(String url, String referer, String cookie) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestProperty("User-Agent", UA);
            if (referer != null) {
                conn.setRequestProperty("Referer", referer);
            }
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code);
            }
            return readAll(conn.getInputStream());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String httpPost(String url, String body, String referer, String cookie)
            throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Content-Type", "application/json");
            if (referer != null) {
                conn.setRequestProperty("Referer", referer);
            }
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }
            conn.getOutputStream().write(body.getBytes("UTF-8"));
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code);
            }
            return readAll(conn.getInputStream());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toString("UTF-8");
    }

    // ================================================================ 极简 JSON

    /** 返回与 start 处 '[' 配对的 ']' 下标。 */
    private static int matchBracket(String s, int start) {
        if (start < 0 || start >= s.length() || s.charAt(start) != '[') {
            return -1;
        }
        int depth = 0;
        boolean inStr = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && inStr) {
                i++;
                continue;
            }
            if (c == '"') {
                inStr = !inStr;
                continue;
            }
            if (inStr) {
                continue;
            }
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 把 [from,to) 区间里的顶层 {...} 对象拆出来。 */
    private static List<String> splitObjects(String s, int from, int to) {
        List<String> out = new ArrayList<String>();
        int i = from;
        while (i < to) {
            int st = s.indexOf('{', i);
            if (st < 0 || st >= to) {
                break;
            }
            int depth = 0;
            boolean inStr = false;
            int end = -1;
            for (int k = st; k < to; k++) {
                char c = s.charAt(k);
                if (c == '\\' && inStr) {
                    k++;
                    continue;
                }
                if (c == '"') {
                    inStr = !inStr;
                    continue;
                }
                if (inStr) {
                    continue;
                }
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        end = k;
                        break;
                    }
                }
            }
            if (end < 0) {
                break;
            }
            out.add(s.substring(st, end + 1));
            i = end + 1;
        }
        return out;
    }

    /** 只取 JSON 对象「顶层」（深度 1）的字符串字段，避免命中 album/artist 子对象。 */
    private static String topStr(String obj, String key) {
        String needle = "\"" + key + "\"";
        int depth = 0;
        boolean inStr = false;
        for (int i = 0; i < obj.length(); i++) {
            char c = obj.charAt(i);
            if (inStr && c == '\\') {
                i++;
                continue;
            }
            if (c == '"') {
                if (!inStr && depth == 1 && obj.startsWith(needle, i)) {
                    int colon = obj.indexOf(':', i + needle.length());
                    int q = colon < 0 ? -1 : obj.indexOf('"', colon + 1);
                    if (q > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (int k = q + 1; k < obj.length(); k++) {
                            char ch = obj.charAt(k);
                            if (ch == '\\' && k + 1 < obj.length()) {
                                char e = obj.charAt(++k);
                                if (e == 'u' && k + 4 < obj.length()) {
                                    try {
                                        sb.append((char) Integer.parseInt(
                                                obj.substring(k + 1, k + 5), 16));
                                        k += 4;
                                    } catch (Throwable ignored) {
                                    }
                                } else {
                                    sb.append(e == 'n' ? '\n' : e);
                                }
                            } else if (ch == '"') {
                                break;
                            } else {
                                sb.append(ch);
                            }
                        }
                        return sb.toString();
                    }
                }
                inStr = !inStr;
            } else if (!inStr) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                }
            }
        }
        return "";
    }

    /** 只取顶层数字字段。 */
    private static String topNum(String obj, String key) {
        String needle = "\"" + key + "\"";
        int depth = 0;
        boolean inStr = false;
        for (int i = 0; i < obj.length(); i++) {
            char c = obj.charAt(i);
            if (inStr && c == '\\') {
                i++;
                continue;
            }
            if (c == '"') {
                if (!inStr && depth == 1 && obj.startsWith(needle, i)) {
                    int colon = obj.indexOf(':', i + needle.length());
                    if (colon < 0) {
                        return "";
                    }
                    int k = colon + 1;
                    while (k < obj.length() && !Character.isDigit(obj.charAt(k))
                            && obj.charAt(k) != '-') {
                        k++;
                    }
                    int st = k;
                    while (k < obj.length() && (Character.isDigit(obj.charAt(k))
                            || obj.charAt(k) == '-')) {
                        k++;
                    }
                    return st == k ? "" : obj.substring(st, k);
                }
                inStr = !inStr;
            } else if (!inStr) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                }
            }
        }
        return "";
    }

    private static String str(String obj, String key) {
        String needle = "\"" + key + "\"";
        int i = obj.indexOf(needle);
        if (i < 0) {
            return "";
        }
        int colon = obj.indexOf(':', i + needle.length());
        if (colon < 0) {
            return "";
        }
        int q1 = obj.indexOf('"', colon + 1);
        if (q1 < 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int k = q1 + 1; k < obj.length(); k++) {
            char c = obj.charAt(k);
            if (c == '\\' && k + 1 < obj.length()) {
                char e = obj.charAt(++k);
                if (e == 'n') {
                    sb.append('\n');
                } else if (e == 't') {
                    sb.append('\t');
                } else if (e == 'u' && k + 4 < obj.length()) {
                    try {
                        sb.append((char) Integer.parseInt(obj.substring(k + 1, k + 5), 16));
                        k += 4;
                    } catch (Throwable ignored) {
                    }
                } else {
                    sb.append(e);
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String firstQuoted(String s) {
        int a = s.indexOf('"');
        if (a < 0) {
            return "";
        }
        int b = s.indexOf('"', a + 1);
        return b > a ? s.substring(a + 1, b) : "";
    }

    private static String num(String obj, String key) {
        String needle = "\"" + key + "\"";
        int i = obj.indexOf(needle);
        if (i < 0) {
            return "";
        }
        int colon = obj.indexOf(':', i + needle.length());
        if (colon < 0) {
            return "";
        }
        int k = colon + 1;
        while (k < obj.length() && !Character.isDigit(obj.charAt(k)) && obj.charAt(k) != '-') {
            k++;
        }
        int st = k;
        while (k < obj.length() && (Character.isDigit(obj.charAt(k)) || obj.charAt(k) == '-')) {
            k++;
        }
        return st == k ? "" : obj.substring(st, k);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 网易云：artists 数组里第一个 name。 */
    private static String firstArtist(String obj) {
        int i = obj.indexOf("\"artists\"");
        if (i < 0) {
            return "";
        }
        int a = obj.indexOf('[', i);
        int b = matchBracket(obj, a);
        if (a < 0 || b < 0) {
            return "";
        }
        return str(obj.substring(a, b), "name");
    }

    /** QQ：singer 数组里第一个 name。 */
    private static String qqSinger(String obj) {
        int i = obj.indexOf("\"singer\"");
        if (i < 0) {
            return "";
        }
        int a = obj.indexOf('[', i);
        int b = matchBracket(obj, a);
        if (a < 0 || b < 0) {
            return "";
        }
        return str(obj.substring(a, b), "name");
    }

    // ================================================================ 歌单

    /** 歌单。 */
    public static final class Playlist {
        public boolean ok;
        public String error = "";
        public String name = "";
        public int trackCount;
        public final List<Track> tracks = new ArrayList<Track>();
    }

    /** 歌单加载进度。 */
    public interface PlaylistProgress {
        void onProgress(int done, int total, String message);
    }

    /** 从链接或纯数字里取歌单 id。 */
    public static String parsePlaylistId(String input) {
        if (input == null) {
            return "";
        }
        String s = input.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:playlist\\?|playlist/|id=)(\\d{4,})").matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        if (s.matches("\\d{4,}")) {
            return s;
        }
        return "";
    }

    /**
     * 拉取歌单（阻塞，后台线程）。
     *
     * @param maxTracks 最多解析多少首（0 表示不限，但会很久）
     */
    public static Playlist playlist(String input, String cookie, int maxTracks,
                                    PlaylistProgress p) {
        Playlist pl = new Playlist();
        String id = parsePlaylistId(input);
        if (id.isEmpty()) {
            pl.error = "请输入歌单链接或歌单 ID\n例如：https://music.163.com/playlist?id=24381616";
            return pl;
        }
        try {
            if (p != null) {
                p.onProgress(0, 1, "获取歌单信息…");
            }
            String body = httpGet("https://music.163.com/api/v6/playlist/detail?id=" + id
                    + "&n=1000&s=8", "https://music.163.com/", cookie);
            int pi = body.indexOf("\"playlist\"");
            if (pi < 0) {
                pl.error = "接口未返回歌单（可能是私密歌单或需要登录）";
                return pl;
            }
            int objStart = body.indexOf('{', pi);
            int objEnd = matchBrace(body, objStart);
            String plObj = objEnd > objStart ? body.substring(objStart, objEnd + 1) : "";

            pl.name = topStr(plObj, "name");
            String tc = topNum(plObj, "trackCount");
            pl.trackCount = (int) parseLong(tc);

            // trackIds
            List<String> ids = new ArrayList<String>();
            int ti = plObj.indexOf("\"trackIds\"");
            if (ti >= 0) {
                int a = plObj.indexOf('[', ti);
                int b = matchBracket(plObj, a);
                if (a > 0 && b > a) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("\\{\"id\":(\\d+)").matcher(plObj.substring(a, b));
                    while (m.find()) {
                        ids.add(m.group(1));
                    }
                }
            }
            if (ids.isEmpty()) {
                // 退化：用 tracks 里已有的
                int tr = plObj.indexOf("\"tracks\"");
                if (tr >= 0) {
                    int a = plObj.indexOf('[', tr);
                    int b = matchBracket(plObj, a);
                    if (a > 0 && b > a) {
                        for (String o : splitObjects(plObj, a + 1, b)) {
                            String tid = topNum(o, "id");
                            if (!tid.isEmpty()) {
                                ids.add(tid);
                            }
                        }
                    }
                }
            }
            if (pl.trackCount <= 0) {
                pl.trackCount = ids.size();
            }
            int limit = maxTracks > 0 ? Math.min(maxTracks, ids.size()) : ids.size();
            if (p != null) {
                p.onProgress(0, limit, "歌单《" + pl.name + "》共 " + pl.trackCount + " 首");
            }

            // 分批取详情（每批 200）
            int batch = 200;
            for (int from = 0; from < limit; from += batch) {
                int to = Math.min(limit, from + batch);
                StringBuilder sb = new StringBuilder();
                for (int k = from; k < to; k++) {
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(ids.get(k));
                }
                String url = "https://music.163.com/api/song/detail?ids=%5B"
                        + sb.toString() + "%5D";
                String detail = httpGet(url, "https://music.163.com/", cookie);
                int si = detail.indexOf("\"songs\"");
                if (si >= 0) {
                    int a = detail.indexOf('[', si);
                    int b = matchBracket(detail, a);
                    if (a > 0 && b > a) {
                        for (String o : splitObjects(detail, a + 1, b)) {
                            Track t = new Track();
                            t.source = SRC_NETEASE;
                            t.id = topNum(o, "id");
                            t.name = topStr(o, "name");
                            t.artist = firstArtist(o);
                            t.duration = (int) (parseLong(topNum(o, "duration")) / 1000);
                            if (!t.id.isEmpty() && !t.name.isEmpty()) {
                                pl.tracks.add(t);
                            }
                        }
                    }
                }
                if (p != null) {
                    p.onProgress(to, limit, "已解析 " + pl.tracks.size() + " 首…");
                }
            }
            pl.ok = true;
            AppLog.log("MUSIC", "playlist " + id + " → " + pl.tracks.size() + "/" + pl.trackCount);
            return pl;
        } catch (Throwable t) {
            pl.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return pl;
        }
    }

    // ================================================================ 我的歌单

    /** 账号里的歌单条目。 */
    public static final class PlaylistInfo {
        public String id = "";
        public String name = "";
        public int trackCount;
        public boolean special;   // 我喜欢的音乐
    }

    /** 我的歌单结果。 */
    public static final class MyPlaylists {
        public boolean ok;
        public String error = "";
        public String nickname = "";
        public final List<PlaylistInfo> items = new ArrayList<PlaylistInfo>();
    }

    /** 拉取当前登录账号的歌单（需 cookie）。 */
    public static MyPlaylists myPlaylists(String cookie) {
        MyPlaylists r = new MyPlaylists();
        if (cookie == null || cookie.trim().isEmpty()) {
            r.error = "请先登录网易云（点「登录」扫码）";
            return r;
        }
        try {
            String acc = httpGet("https://music.163.com/api/nuser/account/get",
                    "https://music.163.com/", cookie);
            int pi = acc.indexOf("\"profile\"");
            String uid = "";
            if (pi >= 0) {
                int a = acc.indexOf('{', pi);
                int b = matchBrace(acc, a);
                if (a > 0 && b > a) {
                    String prof = acc.substring(a, b + 1);
                    uid = topNum(prof, "userId");
                    r.nickname = topStr(prof, "nickname");
                }
            }
            if (uid.isEmpty()) {
                r.error = "未登录或登录已失效，请重新登录";
                return r;
            }
            String body = httpGet("https://music.163.com/api/user/playlist?uid=" + uid
                    + "&limit=1000&offset=0", "https://music.163.com/", cookie);
            int i = body.indexOf("\"playlist\"");
            if (i < 0) {
                r.error = "接口未返回歌单列表";
                return r;
            }
            int a = body.indexOf('[', i);
            int b = matchBracket(body, a);
            if (a < 0 || b < 0) {
                r.error = "解析失败";
                return r;
            }
            for (String o : splitObjects(body, a + 1, b)) {
                PlaylistInfo info = new PlaylistInfo();
                info.id = topNum(o, "id");
                info.name = topStr(o, "name");
                info.trackCount = (int) parseLong(topNum(o, "trackCount"));
                String sp = topNum(o, "specialType");
                info.special = "5".equals(sp);
                if (!info.id.isEmpty() && !info.name.isEmpty()) {
                    r.items.add(info);
                }
            }
            r.ok = true;
            AppLog.log("MUSIC", "my playlists uid=" + uid + " → " + r.items.size());
            return r;
        } catch (Throwable t) {
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return r;
        }
    }

    /** 花括号配对。 */
    private static int matchBrace(String s, int start) {
        if (start < 0 || start >= s.length() || s.charAt(start) != '{') {
            return -1;
        }
        int depth = 0;
        boolean inStr = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr && c == '\\') {
                i++;
                continue;
            }
            if (c == '"') {
                inStr = !inStr;
                continue;
            }
            if (inStr) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 时长格式化。 */
    public static String fmtDuration(int sec) {
        if (sec <= 0) {
            return "";
        }
        return String.format(java.util.Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    /** 从 cookie 里取 uin（QQ 号）。 */
    public static String uinFromCookie(String cookie) {
        if (cookie == null) {
            return "";
        }
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.startsWith("uin=") || p.startsWith("p_uin=")) {
                String v = p.substring(p.indexOf('=') + 1);
                v = v.replace("o", "").replace("O", "");
                if (v.matches("\\d+")) {
                    return v;
                }
            }
        }
        return "";
    }
}
