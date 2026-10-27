package com.example.newmusic.module.dict.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.newmusic.common.util.PinyinUtil;
import com.example.newmusic.module.dict.entity.PinyinDict;
import com.example.newmusic.module.dict.mapper.PinyinDictMapper;
import com.example.newmusic.module.music.entity.Album;
import com.example.newmusic.module.music.entity.Artist;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.mapper.AlbumMapper;
import com.example.newmusic.module.music.mapper.ArtistMapper;
import com.example.newmusic.module.music.mapper.SongMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 多音字词典 → 已物化拼音列的定向重算（模块 11）。
 *
 * <p>模块 06 的拼音列是**写时物化**的，改词典不会自动改历史行。对「重庆 → chongqing」
 * 这类修正，只把词典灌进 {@link PinyinUtil} 只能影响以后新写的歌；老歌仍然搜不到。
 * 因此词典变更后按「名字包含多音字词」筛出受影响的行，重算一次拼音列。
 *
 * <p>不做全表重算：生产曲库百万行时，一次改词就全表 UPDATE 是不可接受的。
 */
@Slf4j
@Service
public class PinyinDictRefresher {

    /** 单次变更最多重算这么多行，超过后写 warn 让运营改用离线回填 */
    private static final int MAX_ROWS_PER_WORD = 20_000;

    private final PinyinDictMapper pinyinMapper;
    private final SongMapper songMapper;
    private final ArtistMapper artistMapper;
    private final AlbumMapper albumMapper;
    private final DictSnapshotService snapshotService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PinyinDictRefresher(PinyinDictMapper pinyinMapper, SongMapper songMapper,
                               ArtistMapper artistMapper, AlbumMapper albumMapper,
                               DictSnapshotService snapshotService) {
        this.pinyinMapper = pinyinMapper;
        this.songMapper = songMapper;
        this.artistMapper = artistMapper;
        this.albumMapper = albumMapper;
        this.snapshotService = snapshotService;
    }

    /**
     * 启动完成后加载多音字词典。用 {@code ApplicationReadyEvent} 而不是 {@code @PostConstruct}：
     * 此时数据库连接池已就绪，且失败不会阻断应用启动（词典表在存量库里可能还没建）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        refreshAffected();
    }

    /** 词典变更后调用；返回实际更新的曲库行数。 */
    public int refreshAffected() {
        if (!running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            snapshotService.applyPinyinOverrides();
            List<PinyinDict> entries = pinyinMapper.selectList(new LambdaQueryWrapper<PinyinDict>()
                    .eq(PinyinDict::getEnabled, 1));
            if (entries.isEmpty()) {
                return 0;
            }
            Set<Long> songIds = new HashSet<>();
            Set<Long> artistIds = new HashSet<>();
            Set<Long> albumIds = new HashSet<>();
            int updated = 0;

            for (PinyinDict entry : entries) {
                String word = entry.getWord();
                if (word == null || word.isEmpty()) {
                    continue;
                }
                List<Song> songs = songMapper.selectList(new LambdaQueryWrapper<Song>()
                        .and(w -> w.like(Song::getName, word).or().like(Song::getArtistName, word))
                        .last("LIMIT " + MAX_ROWS_PER_WORD));
                for (Song song : songs) {
                    if (songIds.add(song.getId())) {
                        updated += updateSong(song);
                    }
                }
                for (Artist artist : artistMapper.selectList(new LambdaQueryWrapper<Artist>()
                        .like(Artist::getName, word).last("LIMIT " + MAX_ROWS_PER_WORD))) {
                    if (artistIds.add(artist.getId())) {
                        updated += updateArtist(artist);
                    }
                }
                for (Album album : albumMapper.selectList(new LambdaQueryWrapper<Album>()
                        .like(Album::getName, word).last("LIMIT " + MAX_ROWS_PER_WORD))) {
                    if (albumIds.add(album.getId())) {
                        updated += updateAlbum(album);
                    }
                }
            }
            if (updated > 0) {
                log.info("多音字词典已生效：重算曲库拼音列 {} 行", updated);
            }
            return updated;
        } catch (RuntimeException e) {
            // 存量库未执行 11-ik-seed.sql 时会到这里；不能影响启动与搜索
            log.warn("多音字词典拼音列重算跳过：{}", e.toString());
            return 0;
        } finally {
            running.set(false);
        }
    }

    private int updateSong(Song song) {
        String namePinyin = nullIfEmpty(PinyinUtil.fullPinyin(song.getName()));
        String nameFirst = nullIfEmpty(PinyinUtil.firstLetters(song.getName()));
        String artistPinyin = song.getArtistName() == null ? song.getArtistNamePinyin()
                : nullIfEmpty(PinyinUtil.fullPinyin(song.getArtistName()));
        String artistFirst = song.getArtistName() == null ? song.getArtistNamePinyinFirst()
                : nullIfEmpty(PinyinUtil.firstLetters(song.getArtistName()));
        if (equal(namePinyin, song.getNamePinyin()) && equal(nameFirst, song.getNamePinyinFirst())
                && equal(artistPinyin, song.getArtistNamePinyin())
                && equal(artistFirst, song.getArtistNamePinyinFirst())) {
            return 0;
        }
        songMapper.update(null, new UpdateWrapper<Song>()
                .eq("id", song.getId())
                .set("name_pinyin", namePinyin)
                .set("name_pinyin_first", nameFirst)
                .set("artist_name_pinyin", artistPinyin)
                .set("artist_name_pinyin_first", artistFirst));
        return 1;
    }

    private int updateArtist(Artist artist) {
        String pinyin = nullIfEmpty(PinyinUtil.fullPinyin(artist.getName()));
        String first = nullIfEmpty(PinyinUtil.firstLetters(artist.getName()));
        if (equal(pinyin, artist.getNamePinyin()) && equal(first, artist.getNamePinyinFirst())) {
            return 0;
        }
        artistMapper.update(null, new UpdateWrapper<Artist>()
                .eq("id", artist.getId())
                .set("name_pinyin", pinyin)
                .set("name_pinyin_first", first));
        return 1;
    }

    private int updateAlbum(Album album) {
        String pinyin = nullIfEmpty(PinyinUtil.fullPinyin(album.getName()));
        String first = nullIfEmpty(PinyinUtil.firstLetters(album.getName()));
        if (equal(pinyin, album.getNamePinyin()) && equal(first, album.getNamePinyinFirst())) {
            return 0;
        }
        albumMapper.update(null, new UpdateWrapper<Album>()
                .eq("id", album.getId())
                .set("name_pinyin", pinyin)
                .set("name_pinyin_first", first));
        return 1;
    }

    private static String nullIfEmpty(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static boolean equal(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
