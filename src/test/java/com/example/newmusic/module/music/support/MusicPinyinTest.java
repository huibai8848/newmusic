package com.example.newmusic.module.music.support;

import com.example.newmusic.module.music.entity.Album;
import com.example.newmusic.module.music.entity.Artist;
import com.example.newmusic.module.music.entity.Song;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 曲库写路径拼音物化单测（模块 06 T612 / 系分 D-35）。
 *
 * <p>三种「写」的后果在这里被钉住：写对了（搜得到）、写空了（列变成 null）、
 * 以及**不该写的时候没写**（偏更新不抹掉原值）。第三条最容易漏——
 * 它的症状是「改了歌手简介之后，这个歌手的拼音搜索就没了」。
 */
@DisplayName("MusicPinyin 曲库拼音物化")
class MusicPinyinTest {

    // ------------------------------------------------------------------ 歌曲

    @Test
    @DisplayName("歌曲：名字与冗余歌手名各写两列，共 4 列")
    void fillsSong() {
        Song song = new Song();
        song.setName("晴天");
        song.setArtistName("周杰伦");

        MusicPinyin.fill(song);

        assertEquals("qingtian", song.getNamePinyin());
        assertEquals("qt", song.getNamePinyinFirst());
        assertEquals("zhoujielun", song.getArtistNamePinyin());
        assertEquals("zjl", song.getArtistNamePinyinFirst());
    }

    @Test
    @DisplayName("歌曲：artistName 为 null 时不动歌手拼音列（偏更新不抹掉原值）")
    void songArtistPinyinUntouchedWhenArtistNameMissing() {
        Song song = new Song();
        song.setName("晴天");
        // artistName 没带 —— 模拟「只改了歌名」的偏更新

        MusicPinyin.fill(song);

        assertEquals("qingtian", song.getNamePinyin());
        assertNull(song.getArtistNamePinyin(), "没带的字段必须留 null，交给更新策略跳过");
        assertNull(song.getArtistNamePinyinFirst());
    }

    @Test
    @DisplayName("歌曲：name 为 null 时名字列留 null（不写空串）")
    void songNamePinyinUntouchedWhenNameMissing() {
        Song song = new Song();
        song.setArtistName("周杰伦");

        MusicPinyin.fill(song);

        assertNull(song.getNamePinyin());
        assertNull(song.getNamePinyinFirst());
        assertEquals("zhoujielun", song.getArtistNamePinyin());
    }

    // ------------------------------------------------------------------ 歌手 / 专辑

    @Test
    @DisplayName("歌手：两列")
    void fillsArtist() {
        Artist artist = new Artist();
        artist.setName("五月天");

        MusicPinyin.fill(artist);

        assertEquals("wuyuetian", artist.getNamePinyin());
        assertEquals("wyt", artist.getNamePinyinFirst());
    }

    @Test
    @DisplayName("专辑：两列")
    void fillsAlbum() {
        Album album = new Album();
        album.setName("叶惠美");

        MusicPinyin.fill(album);

        assertEquals("yehuimei", album.getNamePinyin());
        assertEquals("yhm", album.getNamePinyinFirst());
    }

    // ------------------------------------------------------------------ 空值口径

    /**
     * 这条是整个类的核心不变式。
     *
     * <p>算不出拼音时写**空串**会同时造成两个后果：回填脚本按
     * {@code WHERE name_pinyin IS NULL} 挑行，空串让这一行「看起来已经处理过」，
     * 于是永远不再被回填；而且空串在 {@code LIKE} 里也失去「无拼音」这个语义。
     * 所以这里必须断言 {@code null}，而不是「为空」。
     */
    @Test
    @DisplayName("算不出拼音（纯符号名）→ null，不是空串")
    void unfillableNameYieldsNull() {
        Song song = new Song();
        song.setName("。。。");
        song.setArtistName("!!!");

        MusicPinyin.fill(song);

        assertNull(song.getNamePinyin(), "空串会被误判为「已回填」，必须是 null");
        assertNull(song.getNamePinyinFirst());
        assertNull(song.getArtistNamePinyin());
        assertNull(song.getArtistNamePinyinFirst());

        Artist artist = new Artist();
        artist.setName("？？");
        MusicPinyin.fill(artist);
        assertNull(artist.getNamePinyin());

        Album album = new Album();
        album.setName("");
        MusicPinyin.fill(album);
        assertNull(album.getNamePinyin());
    }

    @Test
    @DisplayName("英文名也能物化（ASCII 原样保留）")
    void asciiName() {
        Artist artist = new Artist();
        artist.setName("A-Lin");

        MusicPinyin.fill(artist);

        assertEquals("alin", artist.getNamePinyin());
        assertEquals("alin", artist.getNamePinyinFirst());
    }

    @Test
    @DisplayName("null 实体不抛异常（维护作业可能传进来空行）")
    void nullEntityIsSafe() {
        MusicPinyin.fill((Song) null);
        MusicPinyin.fill((Artist) null);
        MusicPinyin.fill((Album) null);
    }
}
