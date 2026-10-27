package com.example.newmusic.module.dict.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.dict.config.DictProperties;
import com.example.newmusic.module.dict.entity.DictSyncLog;
import com.example.newmusic.module.dict.mapper.DictSyncLogMapper;
import com.example.newmusic.module.dict.support.EsClient;
import com.example.newmusic.module.dict.vo.DictSyncResultVO;
import com.example.newmusic.module.music.entity.Album;
import com.example.newmusic.module.music.entity.Artist;
import com.example.newmusic.module.music.entity.Lyric;
import com.example.newmusic.module.music.entity.Playlist;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.mapper.AlbumMapper;
import com.example.newmusic.module.music.mapper.ArtistMapper;
import com.example.newmusic.module.music.mapper.LyricMapper;
import com.example.newmusic.module.music.mapper.PlaylistMapper;
import com.example.newmusic.module.music.mapper.SongMapper;
import com.example.newmusic.module.search.engine.es.EsDocFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 索引重建（模块 11 FR-20-007 / BR-20-058）。
 *
 * <p>流程与 PRD 15 的约定一致：<b>新索引 → 全量写入 → 刷新 → 原子切别名</b>。
 * 不直接往线上索引里灌数据：重建期间搜索仍在旧索引上正常进行，
 * 切别名是一个原子操作，不存在「重建到一半用户搜到一半数据」的窗口。
 *
 * <p>触发时机：运营在后台点「重建索引」，或启动时
 * {@code EsIndexInitializer} 发现别名缺失/mapping 版本落后。切换完成后旧索引会被删除
 * （只删本别名的 {@code music_song_v*} 历史版本）。ES 未启用/不可达时立刻返回
 * {@code 71008}，不会静默成功。
 */
@Slf4j
@Service
public class EsReindexService {

    private static final int BULK_SIZE = 500;
    private static final DateTimeFormatter INDEX_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final EsClient esClient;
    private final ObjectMapper objectMapper;
    private final EsDocFactory docFactory;
    private final SongMapper songMapper;
    private final ArtistMapper artistMapper;
    private final AlbumMapper albumMapper;
    private final PlaylistMapper playlistMapper;
    private final LyricMapper lyricMapper;
    private final DictSyncLogMapper syncLogMapper;
    private final DictProperties properties;

    public EsReindexService(EsClient esClient, ObjectMapper objectMapper, EsDocFactory docFactory,
                            SongMapper songMapper, ArtistMapper artistMapper, AlbumMapper albumMapper,
                            PlaylistMapper playlistMapper, LyricMapper lyricMapper,
                            DictSyncLogMapper syncLogMapper, DictProperties properties) {
        this.esClient = esClient;
        this.objectMapper = objectMapper;
        this.docFactory = docFactory;
        this.songMapper = songMapper;
        this.artistMapper = artistMapper;
        this.albumMapper = albumMapper;
        this.playlistMapper = playlistMapper;
        this.lyricMapper = lyricMapper;
        this.syncLogMapper = syncLogMapper;
        this.properties = properties;
    }

    public DictSyncResultVO reindex() {
        long started = System.currentTimeMillis();
        DictSyncResultVO result = new DictSyncResultVO();
        if (!esClient.isAvailable()) {
            throw new BizException(ResultCode.DICT_ES_UNAVAILABLE,
                    "Elasticsearch 未启用或不可达，无法重建索引；搜索仍由 MySQL 兜底");
        }

        String alias = properties.getEs().getIndexAlias();
        String index = alias + "_v" + INDEX_SUFFIX.format(LocalDateTime.now());
        try {
            boolean full = esClient.hasIkPlugin() && esClient.hasPinyinPlugin() && esClient.hasStconvertPlugin();
            String resource = full ? "es/music_song-index.json" : "es/music_song-index-lite.json";
            createIndex(index, resource);

            List<Doc> docs = collectDocs();
            result.setScanned(docs.size());
            int errors = bulk(index, docs);
            result.setCreated(docs.size() - errors);
            result.setSkipped(errors);

            esClient.exchange(org.springframework.http.HttpMethod.POST, "/" + index + "/_refresh", null);
            dropOldIndices(switchAlias(alias, index), alias);

            result.setCostMs(System.currentTimeMillis() - started);
            result.setMessage("索引 " + index + " 已切换为别名 " + alias
                    + (full ? "（IK + 拼音 + 繁简插件）" : "（未检测到 IK/拼音/繁简插件，使用标准分析器）")
                    + (errors > 0 ? "，写入失败 " + errors + " 条" : ""));
            writeLog("SUCCESS", result);
            return result;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            result.setCostMs(System.currentTimeMillis() - started);
            result.setMessage("重建索引失败：" + e.getMessage());
            writeLog("FAIL", result);
            log.warn("重建 ES 索引失败 index={} cause={}", index, e.toString());
            throw new BizException(ResultCode.DICT_ES_UNAVAILABLE,
                    "重建索引失败：" + e.getMessage() + "（原索引未被切换）", e);
        }
    }

    private void createIndex(String index, String resource) throws Exception {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            JsonNode body = objectMapper.readTree(in);
            esClient.exchange(org.springframework.http.HttpMethod.PUT, "/" + index, body);
        }
    }

    private int bulk(String index, List<Doc> docs) throws Exception {
        int errors = 0;
        for (int start = 0; start < docs.size(); start += BULK_SIZE) {
            int end = Math.min(start + BULK_SIZE, docs.size());
            StringBuilder ndjson = new StringBuilder(1024 * 64);
            for (int i = start; i < end; i++) {
                Doc doc = docs.get(i);
                ObjectNode action = objectMapper.createObjectNode();
                ObjectNode meta = action.putObject("index");
                meta.put("_index", index);
                meta.put("_id", doc.id);
                ndjson.append(objectMapper.writeValueAsString(action)).append('\n')
                        .append(objectMapper.writeValueAsString(doc.body)).append('\n');
            }
            JsonNode response = esClient.bulk(ndjson.toString());
            if (response.path("errors").asBoolean(false)) {
                for (JsonNode item : response.path("items")) {
                    JsonNode indexResult = item.path("index");
                    if (indexResult.path("status").asInt(200) >= 300) {
                        errors++;
                    }
                }
            }
        }
        return errors;
    }

    /** @return 切换前挂在别名上的旧索引，供 {@link #dropOldIndices} 清理 */
    private List<String> switchAlias(String alias, String index) throws Exception {
        List<ObjectNode> actions = new ArrayList<>();
        List<String> old = new ArrayList<>();
        try {
            JsonNode existing = esClient.exchange(
                    org.springframework.http.HttpMethod.GET, "/_alias/" + alias, null);
            Iterator<String> names = existing.fieldNames();
            while (names.hasNext()) {
                String previous = names.next();
                old.add(previous);
                ObjectNode remove = objectMapper.createObjectNode();
                ObjectNode detail = remove.putObject("remove");
                detail.put("index", previous);
                detail.put("alias", alias);
                actions.add(remove);
            }
        } catch (RuntimeException e) {
            // 404 = 别名还不存在，属于首次建索引的正常情况
            log.debug("读取别名 {} 失败（首次建索引属正常）：{}", alias, e.toString());
        }
        ObjectNode add = objectMapper.createObjectNode();
        add.putObject("add").put("index", index).put("alias", alias);
        actions.add(add);

        ObjectNode body = objectMapper.createObjectNode();
        body.set("actions", objectMapper.valueToTree(actions));
        esClient.exchange(org.springframework.http.HttpMethod.POST, "/_aliases", body);
        return old;
    }

    /**
     * 删除切换下来的旧索引。
     *
     * <p>不删就会「每改一次 mapping 多一个索引」，本机反复重启几次之后
     * {@code _cat/indices} 里全是历史版本，白白占磁盘也让人看不懂哪个在服务。
     *
     * <p><b>只删本别名自己的历史物理索引</b>（{@code music_song_v*}），
     * 删不掉也只是遗留一条日志：清理失败不能影响「新索引已经生效」这个事实。
     */
    private void dropOldIndices(List<String> old, String alias) {
        String prefix = alias + "_v";
        for (String index : old) {
            if (index == null || !index.startsWith(prefix) || index.equals(alias)) {
                log.info("保留非本别名物理索引 {}（不删除）", index);
                continue;
            }
            try {
                esClient.exchange(org.springframework.http.HttpMethod.DELETE, "/" + index, null);
                log.info("已删除旧索引 {}", index);
            } catch (RuntimeException e) {
                log.warn("删除旧索引 {} 失败（不影响新索引）：{}", index, e.toString());
            }
        }
    }

    private List<Doc> collectDocs() {
        List<Doc> docs = new ArrayList<>();
        Map<Long, String> lyrics = lyricMap();

        for (Song song : songMapper.selectList(new LambdaQueryWrapper<Song>()
                .eq(Song::getStatus, 1)
                .eq(Song::getAuditStatus, 1)
                .orderByAsc(Song::getId))) {
            docs.add(new Doc("SONG_" + song.getId(), docFactory.song(song, lyrics.get(song.getId()))));
        }

        for (Artist artist : artistMapper.selectList(new LambdaQueryWrapper<Artist>()
                .eq(Artist::getStatus, 1).orderByAsc(Artist::getId))) {
            docs.add(new Doc("ARTIST_" + artist.getId(), docFactory.artist(artist)));
        }

        for (Album album : albumMapper.selectList(new LambdaQueryWrapper<Album>()
                .eq(Album::getStatus, 1).orderByAsc(Album::getId))) {
            docs.add(new Doc("ALBUM_" + album.getId(), docFactory.album(album)));
        }

        for (Playlist playlist : playlistMapper.selectList(new LambdaQueryWrapper<Playlist>()
                .eq(Playlist::getStatus, 1)
                .eq(Playlist::getIsPublic, 1)
                .orderByAsc(Playlist::getId))) {
            docs.add(new Doc("PLAYLIST_" + playlist.getId(), docFactory.playlist(playlist)));
        }
        return docs;
    }

    private Map<Long, String> lyricMap() {
        Map<Long, String> result = new HashMap<>();
        for (Lyric lyric : lyricMapper.selectList(new LambdaQueryWrapper<Lyric>()
                .eq(Lyric::getAuditStatus, 1))) {
            if (lyric.getSongId() != null && lyric.getContent() != null) {
                result.put(lyric.getSongId(), lyric.getContent());
            }
        }
        return result;
    }

    private void writeLog(String status, DictSyncResultVO result) {
        try {
            DictSyncLog entry = new DictSyncLog();
            entry.setSyncType("REINDEX");
            entry.setStatus(status);
            entry.setItemCount(result.getCreated());
            entry.setCostMs(result.getCostMs());
            entry.setMessage(result.getMessage() == null ? null
                    : result.getMessage().substring(0, Math.min(480, result.getMessage().length())));
            syncLogMapper.insert(entry);
        } catch (RuntimeException e) {
            log.warn("写重建索引日志失败：{}", e.toString());
        }
    }

    private static final class Doc {
        private final String id;
        private final ObjectNode body;

        Doc(String id, ObjectNode body) {
            this.id = id;
            this.body = body;
        }
    }
}
