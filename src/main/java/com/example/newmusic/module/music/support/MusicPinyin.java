package com.example.newmusic.module.music.support;

import com.example.newmusic.common.util.PinyinUtil;
import com.example.newmusic.module.music.entity.Album;
import com.example.newmusic.module.music.entity.Artist;
import com.example.newmusic.module.music.entity.Song;

/**
 * 曲库写路径上的拼音物化（模块 06 D-35）。
 *
 * <p>曲库一共三处入口会改变「名字」：新增、改名（走各自的 {@code update}）、以及
 * 歌曲的冗余歌手名随主歌手改名而变化。三条路径都必须经过这里——
 * 少走一条的症状是「新加的歌搜不到拼音」，而这种故障在开发机上永远不会出现，
 * 因为开发机里的数据是回填出来的。
 *
 * <p><b>空结果写 {@code null} 而不是空串。</b>空串在这里是有害的：回填脚本按
 * {@code WHERE name_pinyin IS NULL} 挑待回填的行，写进去的空串会让那一行
 * **永远不再被回填**——它既没有拼音，看起来又「已经处理过了」。
 * 空串在 {@code LIKE} 里也失去「无拼音」的表达能力。null 才是「不知道」。
 */
public final class MusicPinyin {

    private MusicPinyin() {
    }

    /**
     * 歌曲：自身名字 + 冗余歌手名，共 4 列。
     *
     * <p>{@code artistName} 为空时**不动**这两列：那是「本次没带这个字段」的偏更新，
     * 此时把歌手拼音写成 null 会把原值抹掉（MyBatis-Plus 的更新策略对 null 是跳过，
     * 对空串是写入）。判断放在这里而不是交给调用方，是为了让「能不能写」和「写什么」
     * 这两件事只有一个出口。
     */
    public static void fill(Song song) {
        if (song == null) {
            return;
        }
        song.setNamePinyin(nullIfEmpty(PinyinUtil.fullPinyin(song.getName())));
        song.setNamePinyinFirst(nullIfEmpty(PinyinUtil.firstLetters(song.getName())));
        if (song.getArtistName() != null) {
            song.setArtistNamePinyin(nullIfEmpty(PinyinUtil.fullPinyin(song.getArtistName())));
            song.setArtistNamePinyinFirst(nullIfEmpty(PinyinUtil.firstLetters(song.getArtistName())));
        }
    }

    public static void fill(Artist artist) {
        if (artist == null) {
            return;
        }
        artist.setNamePinyin(nullIfEmpty(PinyinUtil.fullPinyin(artist.getName())));
        artist.setNamePinyinFirst(nullIfEmpty(PinyinUtil.firstLetters(artist.getName())));
    }

    public static void fill(Album album) {
        if (album == null) {
            return;
        }
        album.setNamePinyin(nullIfEmpty(PinyinUtil.fullPinyin(album.getName())));
        album.setNamePinyinFirst(nullIfEmpty(PinyinUtil.firstLetters(album.getName())));
    }

    private static String nullIfEmpty(String pinyin) {
        return pinyin == null || pinyin.isEmpty() ? null : pinyin;
    }
}
