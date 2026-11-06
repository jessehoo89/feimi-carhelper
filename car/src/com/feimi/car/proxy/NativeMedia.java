package com.feimi.car.proxy;

import android.app.PendingIntent;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.List;

/**
 * 媒体数据 Parcel 编码 —— 只用 Android 公开 API。
 *
 * 三个数据对象（IMedia / IContent / IMediaList）都是 Parcelable，
 * 上下行只需要「写」或只需要「读」。我们是数据生产方，所以只实现写。
 * 字段顺序与协议规格逐字段对齐，不可调换。
 *
 * 注意：这些字段布局是跨进程线格式，属于接口规格；本类为独立实现。
 */
public final class NativeMedia {

    private NativeMedia() {
    }

    /** 对应 ecarx.xsf.mediacenter.IMedia，writeToParcel 共 30 个字段。 */
    public static final class Item {
        public String uuid;
        public String title;
        public String artist;
        public String album;
        public String author;
        public String composer;
        public String description;
        public String subtitle;
        public String rating;
        public String year;
        public long duration;
        public int positionInQueue;
        public int albumIndex;
        public String categoryStr;
        public String subCategoryStr;
        public String mediaId;
        public String mediaCp;
        public String targetType;
        public int sourceType;
        public Uri mediaPath;
        public String lyricContent;
        public Uri lyric;
        public Uri artWork;
        public String radioFrequency;
        public String radioStationName;
        public int vip;
        public String playingMediaListId;
        public int playingMediaListType;
        public int supportCollect;
        public int collected;

        public void writeTo(Parcel d, int flags) {
            d.writeString(uuid);
            d.writeString(title);
            d.writeString(artist);
            d.writeString(album);
            d.writeString(author);
            d.writeString(composer);
            d.writeString(description);
            d.writeString(subtitle);
            d.writeString(rating);
            d.writeString(year);
            d.writeLong(duration);
            d.writeInt(positionInQueue);
            d.writeInt(albumIndex);
            d.writeString(categoryStr);
            d.writeString(subCategoryStr);
            d.writeString(mediaId);
            d.writeString(mediaCp);
            d.writeString(targetType);
            d.writeInt(sourceType);
            d.writeParcelable(mediaPath, flags);
            d.writeString(lyricContent);
            d.writeParcelable(lyric, flags);
            d.writeParcelable(artWork, flags);
            d.writeString(radioFrequency);
            d.writeString(radioStationName);
            d.writeInt(vip);
            d.writeString(playingMediaListId);
            d.writeInt(playingMediaListType);
            d.writeInt(supportCollect);
            d.writeInt(collected);
        }
    }

    /** 对应 ecarx.xsf.mediacenter.IContent，writeToParcel 共 4 个字段。 */
    public static final class Content {
        public String id;
        public String title;
        public PendingIntent pendingIntent;
        public Uri background;

        public void writeTo(Parcel d, int flags) {
            d.writeString(id);
            d.writeString(title);
            d.writeParcelable(pendingIntent, flags);
            d.writeParcelable(background, flags);
        }
    }

    /** 对应 ecarx.xsf.mediacenter.IMediaList，writeToParcel 共 6 个字段。 */
    public static final class MediaList {
        public String title;
        public int sourceType;
        public String mediaListId;
        public int mediaListType;
        public List<Item> mediaList;
        public PendingIntent pendingIntent;

        public void writeTo(Parcel d, int flags) {
            d.writeString(title);
            d.writeInt(sourceType);
            d.writeString(mediaListId);
            d.writeInt(mediaListType);
            writeItemList(d, mediaList);
            d.writeParcelable(pendingIntent, flags);
        }
    }

    /**
     * 等价于 Parcel.writeTypedList(List&lt;IMedia&gt;)：
     * null 写 -1，否则写个数，每个元素再写 0/1 头 + 字段。
     */
    static void writeItemList(Parcel d, List<Item> list) {
        if (list == null) {
            d.writeInt(-1);
            return;
        }
        int n = list.size();
        d.writeInt(n);
        for (int i = 0; i < n; i++) {
            Item it = list.get(i);
            if (it == null) {
                d.writeInt(0);
            } else {
                d.writeInt(1);
                it.writeTo(d, 0);
            }
        }
    }

    /** 等价于 Parcel.writeTypedList(List&lt;IContent&gt;)。 */
    static void writeContentList(Parcel d, List<Content> list) {
        if (list == null) {
            d.writeInt(-1);
            return;
        }
        int n = list.size();
        d.writeInt(n);
        for (int i = 0; i < n; i++) {
            Content it = list.get(i);
            if (it == null) {
                d.writeInt(0);
            } else {
                d.writeInt(1);
                it.writeTo(d, 0);
            }
        }
    }

    /** 仅用于自检：确认框架类可用（避免被误认为需要自写 Uri/PendingIntent 编码）。 */
    static boolean frameworkOk() {
        return Parcelable.class != null && Uri.class != null && PendingIntent.class != null;
    }
}
