package com.rootterm.fm;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 免费音乐搜索/下载（Jamendo，CC 授权曲库）。
 *
 * <p>Jamendo 需要免费申请 client_id（devportal.jamendo.com），本类只用用户自己的 key，
 * 不内置任何第三方密钥。所有曲目均为 Creative Commons 授权，可合法下载。</p>
 */
public final class MusicSearch {

    /** 一首歌。 */
    public static final class Track {
        public String name = "";
        public String artist = "";
        public int duration;          // 秒
        public String downloadUrl = "";
        public String streamUrl = "";
        public String license = "";
    }

    /** 结果。 */
    public static final class Result {
        public boolean ok;
        public String error = "";
        public final List<Track> tracks = new ArrayList<Track>();
    }

    private static final int TIMEOUT = 20000;

    private MusicSearch() {
    }

    /** 搜索（阻塞，必须在后台线程）。 */
    public static Result search(String clientId, String query) {
        Result r = new Result();
        if (clientId == null || clientId.trim().isEmpty()) {
            r.error = "未设置 Jamendo Client ID\n\n申请方法：\n"
                    + "1) 打开 devportal.jamendo.com\n"
                    + "2) 注册并创建 App（免费）\n"
                    + "3) 复制 Client ID 填到设置里";
            return r;
        }
        if (query == null || query.trim().isEmpty()) {
            r.error = "请输入搜索关键词";
            return r;
        }
        HttpURLConnection conn = null;
        try {
            String q = URLEncoder.encode(query.trim(), "UTF-8");
            String url = "https://api.jamendo.com/v3.0/tracks/?client_id="
                    + URLEncoder.encode(clientId.trim(), "UTF-8")
                    + "&format=json&limit=25&search=" + q
                    + "&include=licenses&audioformat=mp32";
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestProperty("User-Agent", "TFManager/" + AppVersion.NAME);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                r.error = "HTTP " + code;
                return r;
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            String body = bos.toString("UTF-8");

            String status = str(body, "status");
            if (!"success".equalsIgnoreCase(status)) {
                String msg = str(body, "error_message");
                r.error = msg.isEmpty() ? "接口返回失败（请检查 Client ID）" : msg;
                return r;
            }

            // 逐条解析 results 数组
            int idx = body.indexOf("\"results\"");
            int arrStart = body.indexOf('[', idx);
            int arrEnd = body.lastIndexOf(']');
            if (arrStart < 0 || arrEnd <= arrStart) {
                r.ok = true;
                return r;
            }
            String arr = body.substring(arrStart + 1, arrEnd);
            // 用 "id":" 作为每条记录的分隔
            String[] objs = arr.split("\\},\\s*\\{");
            for (String o : objs) {
                Track t = new Track();
                t.name = str(o, "name");
                t.artist = str(o, "artist_name");
                t.downloadUrl = str(o, "audiodownload");
                t.streamUrl = str(o, "audio");
                String dur = num(o, "duration");
                try {
                    t.duration = Integer.parseInt(dur);
                } catch (Throwable ignored) {
                }
                if (t.downloadUrl.isEmpty()) {
                    t.downloadUrl = t.streamUrl;
                }
                if (!t.name.isEmpty() && !t.downloadUrl.isEmpty()) {
                    r.tracks.add(t);
                }
            }
            r.ok = true;
            AppLog.log("MUSIC", "search '" + query + "' → " + r.tracks.size() + " 条");
            return r;
        } catch (Throwable t) {
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return r;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ---------------------------------------------------------------- 极简 JSON

    private static String str(String json, String key) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return "";
        }
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) {
            return "";
        }
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int k = q1 + 1; k < json.length(); k++) {
            char c = json.charAt(k);
            if (c == '\\' && k + 1 < json.length()) {
                char e = json.charAt(++k);
                if (e == 'n') {
                    sb.append('\n');
                } else if (e == 't') {
                    sb.append('\t');
                } else if (e == 'u' && k + 4 < json.length()) {
                    try {
                        sb.append((char) Integer.parseInt(json.substring(k + 1, k + 5), 16));
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

    private static String num(String json, String key) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return "";
        }
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) {
            return "";
        }
        int k = colon + 1;
        while (k < json.length() && !Character.isDigit(json.charAt(k))
                && json.charAt(k) != '-') {
            k++;
        }
        int st = k;
        while (k < json.length() && (Character.isDigit(json.charAt(k))
                || json.charAt(k) == '.' || json.charAt(k) == '-')) {
            k++;
        }
        return json.substring(st, k);
    }

    /** 时长格式化。 */
    public static String fmtDuration(int sec) {
        if (sec <= 0) {
            return "";
        }
        return String.format(java.util.Locale.US, "%d:%02d", sec / 60, sec % 60);
    }
}
