package com.example.newmusic.module.music.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.module.music.entity.Album;
import com.example.newmusic.module.music.entity.Artist;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.mapper.AlbumMapper;
import com.example.newmusic.module.music.mapper.ArtistMapper;
import com.example.newmusic.module.music.mapper.SongMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 拼音检索列的历史数据回填（模块 06 D-35）。
 *
 * <p>新增的 {@code *_pinyin} 列对**已经存在**的行是 NULL，而写入期的物化只覆盖
 * 「回填上线之后被写过」的行。没有这个回填，上线当天拼音搜不到任何老歌——
 * 一个只有老数据才复现、在新环境里永远看不到的故障。
 *
 * <p><b>它和写路径共用 {@link MusicPinyin} 这一个出口</b>，所以「新写的行」与
 * 「回填出来的行」在拼音口径上不可能不一致（多音字取第一个读音这类规则只写了一遍）。
 *
 * <p>四个刻意的选择：
 * <ul>
 *   <li>只挑 {@code name_pinyin IS NULL} 的行——**幂等**，每次启动重跑是安全的，
 *       不必记「迁移跑过没有」（同 {@code SongBloomInitializer} 的思路）；</li>
 *   <li>键集分页（{@code id > 上一批最大 id}）而不是 {@code LIMIT offset}：
 *       有些名字（纯符号、emoji）算不出任何拼音，这些行写完之后**仍然是 NULL**，
 *       用 offset 分页会让它们被反复选中，最坏情况是原地死循环；</li>
 *   <li>每条 UPDATE 都带 {@code update_time = update_time}：{@code update_time} 的 DDL 是
 *       {@code ON UPDATE CURRENT_TIMESTAMP}，回填只改派生列、不改业务事实，
 *       不该让每一行的「更新时间」跳到部署那一刻——那会让详情接口的响应发生变化
 *       （进而让缓存里已有的 VO 与库里的对不上）</li>
 *   <li>失败只记日志，不抛异常：回填最坏的结果是拼音搜不到老歌，而挡住启动的代价
 *       是整个服务不可用。</li>
 * </ul>
 *
 * <p>走 Mapper 而不是 Service：这是一次针对**派生列**的维护作业，不是业务操作。
 * 三个 Service 的 {@code update} 都刻意不接受拼音字段（派生数据不应由调用方决定），
 * 为了这个作业去给每个 Service 加一对方法，等于为了让维护作业复用业务方法
 * 而把派生列写进业务契约。
 */
@Slf4j
@Component
public class PinyinBackfill {

    /** 每批处理的行数。取 500：够把一次往返的收益摊开，又不至于让单批占用太久的连接 */
    private static final int BATCH_SIZE = 500;

    private final SongMapper songMapper;
    private final ArtistMapper artistMapper;
    private final AlbumMapper albumMapper;
    private final boolean enabled;

    public PinyinBackfill(SongMapper songMapper, ArtistMapper artistMapper, AlbumMapper albumMapper,
                          @Value("${newmusic.search.pinyin.backfill-enabled:true}") boolean enabled) {
        this.songMapper = songMapper;
        this.artistMapper = artistMapper;
        this.albumMapper = albumMapper;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        if (!enabled) {
            log.info("拼音列回填已关闭（newmusic.search.pinyin.backfill-enabled=false），跳过");
            return;
        }
        try {
            int songs = backfillSongs();
            int artists = backfillArtists();
            int albums = backfillAlbums();
            if (songs + artists + albums == 0) {
                log.info("拼音列回填：无需处理（没有 name_pinyin IS NULL 的行）");
            } else {
                log.info("拼音列回填完成：歌曲={} 歌手={} 专辑={}", songs, artists, albums);
            }
        } catch (Exception e) {
            log.error("拼音列回填失败（不影响启动；影响是拼音搜不到老数据）: {}", e.toString());
        }
    }

    private int backfillSongs() {
        int total = 0;
        long lastId = 0L;
        while (true) {
            List<Song> rows = songMapper.selectList(Wrappers.<Song>query()
                    .select("id", "name", "artist_name")
                    .isNull("name_pinyin")
                    .gt("id", lastId)
                    .orderByAsc("id")
                    .last("LIMIT " + BATCH_SIZE));
            if (rows.isEmpty()) {
                return total;
            }
            for (Song row : rows) {
                // 游标先推进，再判断能不能填。反过来的话，一批**全部**算不出拼音的行
                // 会让游标原地不动，同一个批次被永远选中——回填变成启动时的死循环
                lastId = row.getId();
                Song patch = new Song();
                patch.setName(row.getName());
                patch.setArtistName(row.getArtistName());
                // 与写路径同一个出口：口径只写一遍，见类注释
                MusicPinyin.fill(patch);
                if (patch.getNamePinyin() == null) {
                    // 算不出拼音（纯符号/emoji 名字）。不写空串——空串会让
                    // 「name_pinyin IS NULL」这个挑选条件再也选不中它，看起来像已处理过
                    continue;
                }
                songMapper.update(null, Wrappers.<Song>update()
                        .set("name_pinyin", patch.getNamePinyin())
                        .set("name_pinyin_first", patch.getNamePinyinFirst())
                        .set("artist_name_pinyin", patch.getArtistNamePinyin())
                        .set("artist_name_pinyin_first", patch.getArtistNamePinyinFirst())
                        .setSql("update_time = update_time")
                        .eq("id", row.getId()));
                total++;
            }
            if (rows.size() < BATCH_SIZE) {
                return total;
            }
        }
    }

    private int backfillArtists() {
        int total = 0;
        long lastId = 0L;
        while (true) {
            List<Artist> rows = artistMapper.selectList(Wrappers.<Artist>query()
                    .select("id", "name")
                    .isNull("name_pinyin")
                    .gt("id", lastId)
                    .orderByAsc("id")
                    .last("LIMIT " + BATCH_SIZE));
            if (rows.isEmpty()) {
                return total;
            }
            for (Artist row : rows) {
                lastId = row.getId();
                Artist patch = new Artist();
                patch.setName(row.getName());
                MusicPinyin.fill(patch);
                if (patch.getNamePinyin() == null) {
                    continue;
                }
                artistMapper.update(null, Wrappers.<Artist>update()
                        .set("name_pinyin", patch.getNamePinyin())
                        .set("name_pinyin_first", patch.getNamePinyinFirst())
                        .setSql("update_time = update_time")
                        .eq("id", row.getId()));
                total++;
            }
            if (rows.size() < BATCH_SIZE) {
                return total;
            }
        }
    }

    private int backfillAlbums() {
        int total = 0;
        long lastId = 0L;
        while (true) {
            List<Album> rows = albumMapper.selectList(Wrappers.<Album>query()
                    .select("id", "name")
                    .isNull("name_pinyin")
                    .gt("id", lastId)
                    .orderByAsc("id")
                    .last("LIMIT " + BATCH_SIZE));
            if (rows.isEmpty()) {
                return total;
            }
            for (Album row : rows) {
                lastId = row.getId();
                Album patch = new Album();
                patch.setName(row.getName());
                MusicPinyin.fill(patch);
                if (patch.getNamePinyin() == null) {
                    continue;
                }
                albumMapper.update(null, Wrappers.<Album>update()
                        .set("name_pinyin", patch.getNamePinyin())
                        .set("name_pinyin_first", patch.getNamePinyinFirst())
                        .setSql("update_time = update_time")
                        .eq("id", row.getId()));
                total++;
            }
            if (rows.size() < BATCH_SIZE) {
                return total;
            }
        }
    }
}
