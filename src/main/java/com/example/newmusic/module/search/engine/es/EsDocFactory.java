package com.example.newmusic.module.search.engine.es;

import com.example.newmusic.common.util.PinyinUtil;
import com.example.newmusic.module.music.entity.Album;
import com.example.newmusic.module.music.entity.Artist;
import com.example.newmusic.module.music.entity.Playlist;
import com.example.newmusic.module.music.entity.Song;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 把库里的实体变成 ES 文档（模块 11 / 模块 06）。
 *
 * <p>全量重建（{@code EsReindexService}）与增量同步（{@code SearchIndexSyncService}）
 * **必须共用这一份字段拼装**：两条路径各写一遍字段名，迟早会出现「重建出来的文档能搜到、
 * 增量写进去的搜不到」——而 {@code dynamic:false} 下多写/写错字段都不报错，只能靠人发现。
 */
@Component
public class EsDocFactory {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final ObjectMapper objectMapper;

    public EsDocFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode song(Song song, String lyric) {
        ObjectNode node = base(song.getId(), "SONG", song.getName(), song.getCover(), song.getHotScore());
        node.put("artistId", song.getArtistId());
        node.put("artistName", song.getArtistName());
        putPinyin(node, "artistNamePinyin", PinyinUtil.fullPinyin(song.getArtistName()));
        putPinyin(node, "artistNamePinyinFirst", PinyinUtil.firstLetters(song.getArtistName()));
        node.put("albumId", song.getAlbumId());
        node.put("albumName", song.getAlbumName());
        putPinyin(node, "albumNamePinyin", PinyinUtil.fullPinyin(song.getAlbumName()));
        node.put("language", song.getLanguage());
        node.put("genre", song.getGenre());
        node.put("era", song.getEra());
        node.put("duration", song.getDuration());
        node.put("copyrightStatus", song.getCopyrightStatus());
        putTime(node, "publishTime", song.getPublishTime());
        if (lyric != null && !lyric.isEmpty()) {
            node.put("lyric", stripLrc(lyric));
        }
        return node;
    }

    public ObjectNode artist(Artist artist) {
        ObjectNode node = base(artist.getId(), "ARTIST", artist.getName(), artist.getCover(), artist.getHotScore());
        node.put("alias", artist.getAlias());
        node.put("songCount", artist.getSongCount());
        return node;
    }

    public ObjectNode album(Album album) {
        ObjectNode node = base(album.getId(), "ALBUM", album.getName(), album.getCover(), album.getHotScore());
        node.put("artistId", album.getArtistId());
        node.put("artistName", album.getArtistName());
        putPinyin(node, "artistNamePinyin", PinyinUtil.fullPinyin(album.getArtistName()));
        putPinyin(node, "artistNamePinyinFirst", PinyinUtil.firstLetters(album.getArtistName()));
        node.put("songCount", album.getSongCount());
        if (album.getPublishDate() != null) {
            putTime(node, "publishTime", album.getPublishDate().atStartOfDay());
        }
        return node;
    }

    public ObjectNode playlist(Playlist playlist) {
        ObjectNode node = base(playlist.getId(), "PLAYLIST", playlist.getName(),
                playlist.getCover(), playlist.getHotScore());
        node.put("songCount", playlist.getSongCount());
        node.put("playCount", playlist.getPlayCount());
        node.put("tags", playlist.getTags());
        return node;
    }

    /** 歌词搜的是正文，LRC 时间轴必须先剥掉：否则搜「00」会命中全部歌词，高亮也会亮在时间戳上。 */
    public static String stripLrc(String content) {
        return content == null ? null : content.replaceAll("\\[[^]]*\\]", "");
    }

    private ObjectNode base(Long id, String docType, String name, String cover, Integer hotScore) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", id);
        node.put("docType", docType);
        node.put("name", name);
        node.put("cover", cover);
        node.put("hotScore", hotScore);
        // 拼音由应用侧物化：ES 的 pinyin 插件缺失时，multi-field 无法把中文转成拼音，
        // 因此显式写入全拼与首字母，mapping 用 edge_ngram 支持前缀检索。
        putPinyin(node, "namePinyin", PinyinUtil.fullPinyin(name));
        putPinyin(node, "namePinyinFirst", PinyinUtil.firstLetters(name));
        return node;
    }

    /** 空拼音不写入：`dynamic:false` 下多写一个空串没有意义，还会让 unigram 边产生噪音。 */
    private void putPinyin(ObjectNode node, String field, String pinyin) {
        if (pinyin != null && !pinyin.isEmpty()) {
            node.put(field, pinyin);
        }
    }

    private void putTime(ObjectNode node, String field, LocalDateTime time) {
        if (time != null) {
            node.put(field, DATE_TIME.format(time));
        }
    }
}
