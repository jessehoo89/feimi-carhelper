package com.feimi.car.proxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

/**
 * 完整歌词：优先取落雪自己输出的整篇 LRC，联网搜索只作可选兜底。
 *
 * 落雪「显示完整蓝牙歌词」把整篇 LRC 写进 MediaSession 自定义 key；读到后走 applyLocal()，本 App 不联网。
 *
 * 当前句始终优先用落雪逐句写进 title 的那一句（精确同步）。
 *
 * 联网兜底（默认关闭）的三个坑（都踩过了）：
 *  1. key 必须按"歌"而不是按"歌词行" —— 否则每秒换 key，刚取到的歌词被清空重搜。
 *  2. key 还要归一化 —— 落雪的歌手字段在 `田馥甄` / `田馥甄 (Hebe Tien)` 之间来回抖，
 *     不归一会当成两首歌，互相把对方的歌词清掉。
 *  3. LRCLIB 返回繁体，要过一遍 {@link T2S}；网易云是简体，优先用它。
 */
public final class LyricFetcher {

    private static volatile String lastKey = "";

    /** 当前曲目已由落雪直接给出整篇 LRC —— 此时绝不联网。 */
    private static volatile boolean hasLocalLyric = false;
    private static volatile String localKey = "";
    private static volatile String lastAppliedLrc = "";
    /** 上一首的完整 LRC：落雪切歌瞬间会短暂残留这个值，用于识别并跳过。 */
    private static volatile String previousLrc = "";
    private static volatile boolean awaitingFreshLrc = false;
    private static volatile String lastMissingKey = "";
    private static volatile String currentTrackKey = "";

    /** 取到过的歌词（key 归一化后），切回上一首不用重新请求，也躲开 LRCLIB 的限流。 */
    private static final HashMap<String, String> CACHE = new HashMap<String, String>();
    private static final LinkedList<String> ORDER = new LinkedList<String>();

    private static final List<Long> TIMES = new ArrayList<Long>();
    private static final List<String> TEXTS = new ArrayList<String>();

    private LyricFetcher() {
    }

    /**
     * 切歌边界：由 SessionWatcher.onMetadata 在解析出歌名后调用。
     * 落雪逐句刷新 title 会反复进 onMetadata，只有 key 变化才清上一首歌词。
     */
    public static void onTrack(String song, String artist) {
        final String key = key(song, artist);
        if (key.length() == 0 || key.equals(currentTrackKey)) return;
        currentTrackKey = key;
        hasLocalLyric = false;
        localKey = "";
        previousLrc = lastAppliedLrc;
        awaitingFreshLrc = previousLrc.length() > 0;
        lastAppliedLrc = "";
        NowPlaying.lyric = null;
        synchronized (TIMES) {
            TIMES.clear();
            TEXTS.clear();
        }
        ProxyConfig.log("[歌词] 切歌，清空上一首歌词: " + key);
    }

    /**
     * 应用落雪直接给出的整篇 LRC。落雪每次逐句更新 title 时会重发同一份 LRC，
     * 按内容去重，避免反复 parse / pushLyric。全程不发网络请求。
     */
    public static void applyLocal(String lrc, String song, String artist) {
        if (lrc == null || lrc.length() == 0) return;
        if (lrc.equals(lastAppliedLrc)) return;
        // 落雪切歌瞬间 LYRICS 仍是上一首的值；最多挡一次，等新歌自己的 LRC 到达。
        // 只挡一次是为了兼容「两首歌 LRC 内容恰好相同」：第二份仍会被接受。
        if (awaitingFreshLrc && lrc.equals(previousLrc)) {
            awaitingFreshLrc = false;
            ProxyConfig.log("[歌词] 忽略切歌残留的上一首 LRC "
                    + lrc.length() + "B，等待新歌歌词");
            return;
        }
        awaitingFreshLrc = false;
        lastAppliedLrc = lrc;
        final String key = key(song, artist);
        parse(lrc);
        NowPlaying.lyric = lrc;
        hasLocalLyric = true;
        localKey = key;
        ProxyConfig.log("[歌词] 落雪完整 LRC " + key + " 共 " + lineCount()
                + " 行 " + lrc.length() + "B（未联网）");
        MediaCenterBridge.pushLyric();
    }

    /** 每首歌只提示一次：落雪没给完整 LRC 时怎么排查 / 怎么临时兜底。 */
    private static void noteMissing(String key) {
        if (key.equals(lastMissingKey)) return;
        lastMissingKey = key;
        ProxyConfig.log("[歌词] 落雪未给出完整 LRC " + key
                + "（确认「显示完整蓝牙歌词」已开；如需联网兜底："
                + "settings put global carproxy_lyric_online 1）");
    }

    public static void fetch(final String song, final String artist) {
        final String key = key(song, artist);
        if (key.length() == 0) return;
        // 落雪已给出整篇 LRC：本 App 不再对外发任何歌词请求。
        if (hasLocalLyric && key.equals(localKey)) return;
        // 默认严格「歌词都从落雪取」；在线兜底必须显式打开。
        if (!ProxyConfig.onlineLyricFallback()) {
            noteMissing(key);
            return;
        }
        if (key.equals(lastKey)) return;      // 同一首，已经在取或已取到
        lastKey = key;

        String cached = null;
        synchronized (CACHE) {
            cached = CACHE.get(key);
        }
        if (cached != null) {
            apply(cached, key, "缓存");
            return;
        }

        synchronized (TIMES) {
            TIMES.clear();
            TEXTS.clear();
        }
        NowPlaying.lyric = null;

        final String qSong = norm(song);
        final String qArtist = norm(artist);
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int attempt = 1; attempt <= 3; attempt++) {
                    String lrc = null;
                    String src = null;
                    try {
                        // 顺序有讲究：QQ 音乐不限流且是简体；网易云对车载共享出口 IP 常回 405；
                        // LRCLIB 是繁体兜底，最后才用。
                        lrc = queryQQ(qSong, qArtist);
                        src = "QQ音乐";
                        if (lrc == null) {
                            lrc = queryNetease(qSong, qArtist);
                            src = "网易云";
                        }
                        if (lrc == null) {
                            lrc = queryLrclib(qSong, qArtist, NowPlaying.durationMs);
                            src = "LRCLIB";
                            if (lrc != null) lrc = T2S.convert(lrc);   // 这个源是繁体
                        }
                    } catch (Throwable t) {
                        ProxyConfig.log("[歌词] 第" + attempt + "次异常: " + t);
                    }
                    if (lrc != null && lrc.length() > 0) {
                        remember(key, lrc);
                        apply(lrc, key + " (" + src + ")", "命中");
                        return;
                    }
                    if (attempt < 3) {
                        try {
                            Thread.sleep(1500L * attempt);   // 限流/抖动，退避重试
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
                ProxyConfig.log("[歌词] 未命中 " + key + "（重试 3 次）");
            }
        }, "carproxy-lyric").start();
    }

    private static void apply(String lrc, String key, String how) {
        parse(lrc);
        NowPlaying.lyric = lrc;
        ProxyConfig.log("[歌词] " + how + " " + key + " 共 " + lineCount() + " 行 " + lrc.length() + "B");
        MediaCenterBridge.pushLyric();
        MediaCenterBridge.pushState();
    }

    private static void remember(String key, String lrc) {
        synchronized (CACHE) {
            CACHE.put(key, lrc);
            ORDER.addLast(key);
            while (ORDER.size() > 30) {
                String old = ORDER.removeFirst();
                CACHE.remove(old);
            }
        }
    }

    /**
     * 当前句：优先用落雪自己推过来的那一句（精确同步），
     * 没有时才按播放进度从 LRC 推算。
     */
    public static String currentSentence() {
        String live = NowPlaying.lyricLine;
        if (live != null && live.length() > 0) return live;
        long pos = NowPlaying.positionMs;
        synchronized (TIMES) {
            if (TIMES.isEmpty()) return null;
            int idx = -1;
            for (int i = 0; i < TIMES.size(); i++) {
                if (TIMES.get(i) <= pos) idx = i;
                else break;
            }
            if (idx < 0) return null;
            String s = TEXTS.get(idx);
            return (s == null || s.length() == 0) ? null : s;
        }
    }

    public static int lineCount() {
        synchronized (TIMES) {
            return TIMES.size();
        }
    }

    // ------------------------------------------------------------------ key 归一化

    private static String key(String song, String artist) {
        String s = norm(song);
        if (s.length() == 0) return "";
        return s + "|" + norm(artist);
    }

    /** 去掉括号里的附注（"（电影《…》主题曲）"、" (Hebe Tien)" 这类）和空白。 */
    static String norm(String in) {
        if (in == null) return "";
        StringBuilder b = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (c == '(' || c == '（' || c == '[' || c == '【' || c == '〔') {
                depth++;
                continue;
            }
            if (c == ')' || c == '）' || c == ']' || c == '】' || c == '〕') {
                if (depth > 0) depth--;
                continue;
            }
            if (depth > 0) continue;
            if (c == ' ' || c == '\t' || c == '\u00a0' || c == '·' || c == '・') continue;
            b.append(c);
        }
        return b.toString().trim();
    }

    // ------------------------------------------------------------------

    /** 解析 [mm:ss.xx] 行（meta 信息没有时间戳，会被自然过滤掉）。 */
    private static void parse(String lrc) {
        synchronized (TIMES) {
            TIMES.clear();
            TEXTS.clear();
            for (String raw : lrc.split("\n")) {
                String line = raw.trim();
                if (line.length() < 6 || line.charAt(0) != '[') continue;
                int close = line.indexOf(']');
                if (close < 0) continue;
                String stamp = line.substring(1, close);
                if (stamp.length() == 0 || !Character.isDigit(stamp.charAt(0))) continue;
                long ms = toMs(stamp);
                if (ms < 0) continue;
                String text = line.substring(close + 1).trim();
                if (text.length() == 0) continue;
                TIMES.add(ms);
                TEXTS.add(text);
            }
        }
    }

    private static long toMs(String stamp) {
        try {
            String[] p = stamp.split(":");
            if (p.length != 2) return -1;
            int min = Integer.parseInt(p[0].trim());
            float sec = Float.parseFloat(p[1].trim());
            return (long) (min * 60000 + sec * 1000);
        } catch (Throwable t) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ 歌词源

    private static String queryNetease(String song, String artist) {
        try {
            String kw = URLEncoder.encode(song + " " + artist, "UTF-8");
            String body = httpGet("https://music.163.com/api/search/get/web?s=" + kw + "&type=1&limit=5");
            if (body == null || body.length() == 0) {
                ProxyConfig.log("[歌词] 网易云搜索空响应");
                return null;
            }
            JSONObject root = new JSONObject(body);
            JSONObject result = root.optJSONObject("result");
            JSONArray songs = result == null ? null : result.optJSONArray("songs");
            if (songs == null || songs.length() == 0) {
                ProxyConfig.log("[歌词] 网易云无结果 code=" + root.optInt("code", -1)
                        + " 响应=" + body.length() + "B");
                return null;
            }
            long id = pickBest(songs, song, NowPlaying.durationMs);
            if (id <= 0) return null;

            if (NowPlaying.artwork == null || NowPlaying.artwork.length() == 0) {
                fillArtwork(id);
            }
            String ly = httpGet("https://music.163.com/api/song/lyric?id=" + id + "&lv=1&kv=1&tv=-1");
            if (ly == null) return null;
            JSONObject lrcObj = new JSONObject(ly).optJSONObject("lrc");
            String s = lrcObj == null ? "" : lrcObj.optString("lyric", "");
            return s.length() > 0 ? s : null;
        } catch (Throwable t) {
            ProxyConfig.log("[歌词] 网易云失败: " + t);
            return null;
        }
    }

    /** 搜出多个结果时挑时长最接近的，避免撞到同名翻唱/伴奏。 */
    private static long pickBest(JSONArray songs, String song, long durMs) {
        long best = 0;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < songs.length(); i++) {
            JSONObject o = songs.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("name", "");
            long id = o.optLong("id", 0);
            if (name.length() == 0 || id <= 0) continue;

            long d = 0;
            JSONObject dt = o.optJSONObject("dt");
            if (dt != null) d = dt.optLong("duration", 0);
            if (d == 0) d = o.optLong("duration", 0);

            if (durMs <= 0 || d <= 0) {
                if (best == 0) best = id;
                continue;
            }
            long diff = Math.abs(d - durMs);
            if (norm(name).startsWith(norm(song))) diff -= 8000;   // 名字对上的优先
            if (diff < bestDiff) {
                bestDiff = diff;
                best = id;
            }
        }
        return best;
    }

    private static void fillArtwork(long id) {
        try {
            String detail = httpGet("https://music.163.com/api/song/detail?ids=%5B" + id + "%5D");
            if (detail == null) return;
            JSONArray ds = new JSONObject(detail).optJSONArray("songs");
            if (ds != null && ds.length() > 0) {
                JSONObject al = ds.getJSONObject(0).optJSONObject("album");
                String pic = al == null ? "" : al.optString("picUrl", "");
                if (pic.length() > 0) {
                    NowPlaying.artwork = pic;
                    MediaCenterBridge.pushState();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * QQ 音乐（无鉴权两步：smartbox 搜索拿 songmid → lyric 接口拿 base64 LRC）。
     * 车机出口 IP 下比网易云稳，且返回简体。
     */
    private static String queryQQ(String song, String artist) {
        String QQ_REF = "https://y.qq.com/";
        try {
            String key = URLEncoder.encode(song + " " + artist, "UTF-8");
            String body = httpGet("https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?key=" + key
                    + "&format=json&inCharset=utf8&outCharset=utf-8", QQ_REF);
            if (body == null || body.length() == 0) return null;
            JSONObject d = new JSONObject(body);
            JSONObject data = d.optJSONObject("data");
            JSONObject songObj = data == null ? null : data.optJSONObject("song");
            JSONArray items = songObj == null ? null : songObj.optJSONArray("itemlist");
            if (items == null || items.length() == 0) {
                ProxyConfig.log("[歌词] QQ 无结果");
                return null;
            }
            String mid = null;
            for (int i = 0; i < items.length() && mid == null; i++) {
                JSONObject o = items.optJSONObject(i);
                if (o != null && o.optString("mid", "").length() > 0) mid = o.optString("mid");
            }
            if (mid == null) return null;

            String ly = httpGet("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=" + mid
                    + "&format=json&nobase64=0&g_tk=5381&loginUin=0&hostUin=0&platform=yqq&needNewCode=0",
                    "https://y.qq.com/portal/player.html");
            if (ly == null) return null;
            String b64 = new JSONObject(ly).optString("lyric", "");
            if (b64.length() == 0) return null;
            byte[] raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            String out = new String(raw, "UTF-8");
            return out.length() > 0 ? out : null;
        } catch (Throwable t) {
            ProxyConfig.log("[歌词] QQ 失败: " + t);
            return null;
        }
    }

    /** 备源：LRCLIB，纯 JSON 无鉴权，对偏门曲目比网易云强（但输出是繁体）。 */
    private static String queryLrclib(String song, String artist, long durMs) {
        try {
            StringBuilder u = new StringBuilder("https://lrclib.net/api/search?track_name=")
                    .append(URLEncoder.encode(song, "UTF-8"));
            if (artist != null && artist.length() > 0) {
                u.append("&artist_name=").append(URLEncoder.encode(artist, "UTF-8"));
            }
            String body = httpGet(u.toString());
            if (body == null) return null;
            JSONArray arr = new JSONArray(body);
            if (arr.length() == 0) {
                ProxyConfig.log("[歌词] LRCLIB 无结果");
                return null;
            }
            JSONObject best = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String s = o.optString("syncedLyrics", "");
                if (s.length() == 0) continue;
                best = o;
                if (durMs > 0) {
                    long d = (long) (o.optDouble("duration", 0) * 1000);
                    if (d > 0 && Math.abs(d - durMs) < 3000) break;
                }
            }
            if (best == null) return null;
            String s = best.optString("syncedLyrics", "");
            return s.length() > 0 ? s : null;
        } catch (Throwable t) {
            ProxyConfig.log("[歌词] LRCLIB 失败: " + t);
            return null;
        }
    }

    private static String httpGet(String urlStr) throws Exception {
        return httpGet(urlStr, "https://music.163.com/");
    }

    private static String httpGet(String urlStr, String referer) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(7000);
        conn.setReadTimeout(7000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)");
        conn.setRequestProperty("Referer", referer);
        conn.setRequestProperty("Accept", "*/*");
        int code = conn.getResponseCode();
        if (code / 100 != 2) {
            ProxyConfig.log("[歌词] HTTP " + code + " "
                    + urlStr.substring(0, Math.min(70, urlStr.length())));
            conn.disconnect();
            return null;
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        conn.disconnect();
        return sb.toString();
    }
}
