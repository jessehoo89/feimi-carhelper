package com.feimi.car.proxy;

/**
 * 目标 App 的 MediaMetadata 语义还原。
 *
 * 落雪音乐（react-native-track-player）实际塞进去的字段是：
 *   title  = 当前歌词句；还没拿到歌词时是歌名
 *   artist = "歌名 - 歌手"（注意顺序是 歌名在前）
 *   album  = 专辑名
 *
 * 所以绝不能把 title 当歌名用 —— 否则卡片标题变成滚动歌词、
 * 关键词搜索也用歌词去搜（必然搜不到），而且 uuid 每秒都变。
 *
 * 这里把三种形态都归一成 歌名 / 歌手 / 当前歌词行。
 */
final class TargetMeta {

    private TargetMeta() {
    }

    /**
     * @return {歌名, 歌手, 当前歌词行或 null}
     */
    static String[] parse(String rawTitle, String rawArtist) {
        String title = clean(rawTitle);
        String artist = clean(rawArtist);

        String[] fromArtist = splitPair(artist);   // artist 字段里藏着的 "歌名 - 歌手"
        String[] fromTitle = splitPair(title);     // title 本身就是 "歌名 - 歌手"

        if (fromTitle != null) {
            String song = fromTitle[0];
            if (fromArtist == null || similar(song, fromArtist[0])) {
                return new String[]{song, fromTitle[1], null};
            }
        }
        if (fromArtist != null) {
            String song = fromArtist[0];
            String line = null;
            // title 跟歌名不一致 → 它就是滚动的歌词句（落雪的车载歌词就是这么喂的）
            if (title.length() > 0 && !similar(title, song)) {
                line = title;
            }
            return new String[]{song, fromArtist[1], line};
        }
        return new String[]{title, artist, null};
    }

    /** "歌名 - 歌手" → {歌名, 歌手}。按最后一个 " - " 切，兼容歌名本身带短横。 */
    private static String[] splitPair(String s) {
        if (s == null || s.length() == 0) return null;
        int i = s.lastIndexOf(" - ");
        if (i <= 0 || i + 3 >= s.length()) return null;
        String a = s.substring(0, i).trim();
        String b = s.substring(i + 3).trim();
        if (a.length() == 0 || b.length() == 0) return null;
        return new String[]{a, b};
    }

    private static boolean similar(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        if (a.length() == 0 || b.length() == 0) return false;
        return a.startsWith(b) || b.startsWith(a)
                || a.contains(b) || b.contains(a);
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }
}
