package com.example.newmusic.module.dict.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.newmusic.module.dict.config.DictProperties;
import com.example.newmusic.module.dict.entity.DictSyncLog;
import com.example.newmusic.module.dict.entity.IkDictWord;
import com.example.newmusic.module.dict.mapper.DictSyncLogMapper;
import com.example.newmusic.module.dict.mapper.IkDictWordMapper;
import com.example.newmusic.module.dict.support.DictTextSupport;
import com.example.newmusic.module.dict.vo.DictSyncResultVO;
import com.example.newmusic.module.music.entity.Artist;
import com.example.newmusic.module.music.mapper.ArtistMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 歌手库 → 主词典自动同步（BR-20-007）。
 *
 * <p>同步范围：歌手名 + 别名（别名按中英文逗号/顿号/斜杠拆开）。
 * 自动词条 {@code source=AUTO_ARTIST}，后台只能停用、不能删除（BR-20-061）；
 * 歌手改名后旧词条不会被删除——删除会让「昨天还能搜到的老名字」直接 404，
 * 运营确认后再手工停用更安全。
 *
 * <p>定时任务每小时一次，cron 错开整点（与热搜落库的 {@code 0 5 * * * ?} 错开）。
 */
@Slf4j
@Service
public class DictArtistSyncService {

    private static final String SOURCE = "AUTO_ARTIST";
    private static final int AUTO_FREQUENCY = 100;

    private final ArtistMapper artistMapper;
    private final IkDictWordMapper wordMapper;
    private final DictSyncLogMapper syncLogMapper;
    private final DictSnapshotService snapshotService;
    private final DictProperties properties;

    public DictArtistSyncService(ArtistMapper artistMapper, IkDictWordMapper wordMapper,
                                 DictSyncLogMapper syncLogMapper, DictSnapshotService snapshotService,
                                 DictProperties properties) {
        this.artistMapper = artistMapper;
        this.wordMapper = wordMapper;
        this.syncLogMapper = syncLogMapper;
        this.snapshotService = snapshotService;
        this.properties = properties;
    }

    /** 手动触发入口（后台按钮）。 */
    public DictSyncResultVO sync() {
        long started = System.currentTimeMillis();
        DictSyncResultVO result = new DictSyncResultVO();
        try {
            doSync(result);
            result.setCostMs(System.currentTimeMillis() - started);
            result.setMessage("同步完成：新增 " + result.getCreated() + "，已存在 " + result.getSkipped());
            writeLog("SUCCESS", result, null);
            return result;
        } catch (RuntimeException e) {
            result.setCostMs(System.currentTimeMillis() - started);
            result.setMessage("同步失败：" + e.getMessage());
            writeLog("FAIL", result, e.toString());
            throw e;
        } finally {
            snapshotService.invalidate();
        }
    }

    /** 定时同步。异常必须自己吞掉，否则一次数据库抖动会让调度线程停摆。 */
    @Scheduled(cron = "${newmusic.dict.artist-sync-cron:0 17 * * * ?}")
    public void syncScheduled() {
        try {
            long started = System.currentTimeMillis();
            DictSyncResultVO result = new DictSyncResultVO();
            doSync(result);
            result.setCostMs(System.currentTimeMillis() - started);
            writeLog("SUCCESS", result, "scheduled");
            if (result.getCreated() > 0) {
                log.info("歌手自动同步完成：新增 {} 条", result.getCreated());
            }
        } catch (Exception e) {
            log.warn("歌手自动同步失败：{}", e.toString());
        } finally {
            snapshotService.invalidate();
        }
    }

    private void doSync(DictSyncResultVO result) {
        List<Artist> artists = artistMapper.selectList(new LambdaQueryWrapper<Artist>()
                .eq(Artist::getStatus, 1)
                .orderByAsc(Artist::getId));
        Set<String> existing = new HashSet<>();
        for (IkDictWord word : wordMapper.selectList(null)) {
            existing.add(word.getWord());
        }

        int total = existing.size();
        for (Artist artist : artists) {
            result.setScanned(result.getScanned() + 1);
            for (String candidate : candidates(artist)) {
                String invalid = DictTextSupport.validateWord(candidate,
                        properties.getWordMinLength(), properties.getWordMaxLength());
                if (invalid != null) {
                    result.setSkipped(result.getSkipped() + 1);
                    continue;
                }
                if (!existing.add(candidate)) {
                    result.setSkipped(result.getSkipped() + 1);
                    continue;
                }
                if (total >= properties.getMaxWords()) {
                    result.setSkipped(result.getSkipped() + 1);
                    continue;
                }
                IkDictWord word = new IkDictWord();
                word.setWord(candidate);
                word.setFrequency(AUTO_FREQUENCY);
                word.setPos("n");
                word.setSource(SOURCE);
                word.setRefId(artist.getId());
                word.setEnabled(1);
                word.setDictType("MAIN");
                wordMapper.insert(word);
                total++;
                result.setCreated(result.getCreated() + 1);
            }
        }
    }

    private Set<String> candidates(Artist artist) {
        Set<String> names = new LinkedHashSet<>();
        add(names, artist.getName());
        if (artist.getAlias() != null) {
            for (String alias : artist.getAlias().split("[,，、/|]")) {
                add(names, alias);
            }
        }
        return names;
    }

    private void add(Set<String> names, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (!trimmed.isEmpty()) {
            names.add(trimmed);
        }
    }

    private void writeLog(String status, DictSyncResultVO result, String message) {
        try {
            DictSyncLog entry = new DictSyncLog();
            entry.setSyncType("ARTIST_SYNC");
            entry.setStatus(status);
            entry.setItemCount(result.getCreated());
            entry.setCostMs(result.getCostMs());
            entry.setMessage(DictTextSupport.brief(message == null ? result.getMessage() : message, 480));
            syncLogMapper.insert(entry);
        } catch (RuntimeException e) {
            log.warn("写歌手同步日志失败：{}", e.toString());
        }
    }
}
