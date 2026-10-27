package com.example.newmusic.module.music.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.common.service.BaseCrudService;
import com.example.newmusic.module.music.dto.LyricSaveReq;
import com.example.newmusic.module.music.entity.Lyric;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.mapper.LyricMapper;
import com.example.newmusic.module.music.mapper.SongMapper;
import com.example.newmusic.module.music.service.LyricService;
import com.example.newmusic.module.search.engine.es.SearchIndexSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

@Slf4j
@Service
public class LyricServiceImpl extends BaseCrudService<LyricMapper, Lyric> implements LyricService {

    /** offsetMs 允许范围（模块 01 接口约束：防前端误传毫秒级数值导致整首错位） */
    private static final int MAX_OFFSET_MS = 10000;

    /** LRC 时间轴：`[01:23]` / `[01:23.45]` / `[01:23.456]` */
    private static final Pattern LRC_TIMESTAMP = Pattern.compile("\\[\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?]");

    /** 歌词是 ES 文档的一部分：歌词改了要重写那首歌的文档，否则「搜歌词」还是旧内容。 */
    private final SearchIndexSyncService searchIndexSyncService;

    /** 回写 `t_song.lyric_id`：AI 情绪分析等按 `s.lyric_id` 关联歌词的功能依赖这个指针。 */
    private final SongMapper songMapper;

    public LyricServiceImpl(SearchIndexSyncService searchIndexSyncService, SongMapper songMapper) {
        this.searchIndexSyncService = searchIndexSyncService;
        this.songMapper = songMapper;
    }

    @Override
    public Lyric getBySongId(Long songId) {
        if (songId == null) {
            return null;
        }
        return getOne(Wrappers.<Lyric>query().eq("song_id", songId).last("LIMIT 1"));
    }

    @Override
    public Lyric detail(Long id) {
        Lyric entity = getById(requireId(id));
        if (entity == null) {
            throw new BizException(ResultCode.LYRIC_NOT_FOUND);
        }
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(LyricSaveReq req) {
        checkOffset(req.getOffsetMs());
        Lyric exists = getBySongId(req.getSongId());
        if (exists != null) {
            throw new BizException(ResultCode.DUPLICATE_DATA, "该歌曲已存在歌词，请使用更新接口");
        }
        // 逻辑删除不会释放 uk_lyric_song：墓碑行还在时直接 INSERT 会变成 99001。
        // create 接口不做「复活」语义（调用方还带 translation/offset 等整套字段），
        // 但至少要给出可读的 10010，并指向能恢复的写入接口。
        if (baseMapper.selectBySongIdIncludeDeleted(req.getSongId()) != null) {
            throw new BizException(ResultCode.DUPLICATE_DATA,
                    "该歌曲存在已删除的歌词，请使用按歌曲写入接口恢复");
        }
        Lyric entity = new Lyric();
        BeanUtils.copyProperties(req, entity);
        entity.setId(null);
        entity.setContentType(entity.getContentType() == null ? 1 : entity.getContentType());
        entity.setOffsetMs(entity.getOffsetMs() == null ? 0 : entity.getOffsetMs());
        entity.setAuditStatus(entity.getAuditStatus() == null ? 1 : entity.getAuditStatus());
        try {
            save(entity);
        } catch (DuplicateKeyException e) {
            // 并发新增同一首歌：唯一键是最后一道防线，转换成业务错误而不是 99001
            throw new BizException(ResultCode.DUPLICATE_DATA, "该歌曲已存在歌词，请使用更新接口");
        }
        linkSong(entity.getSongId(), entity.getId());
        syncSongAfterCommit(entity.getSongId());
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, LyricSaveReq req) {
        requireVersion(req.getVersion());
        checkOffset(req.getOffsetMs());
        Lyric exists = detail(id);
        Lyric entity = new Lyric();
        BeanUtils.copyProperties(req, entity);
        entity.setId(exists.getId());
        entity.setVersion(req.getVersion());
        entity.setSongId(exists.getSongId());
        updateWithVersion(entity);
        linkSong(exists.getSongId(), exists.getId());
        syncSongAfterCommit(exists.getSongId());
    }

    /**
     * 按歌曲写入歌词正文（导入/后台编辑共用）。
     *
     * <p>三段逻辑都围绕「不破坏已有数据」：
     * <ol>
     *   <li>正文先 {@code trim()}：导入的 `.lrc` 常带首尾空行，原样入库会让播放器首屏是空白；</li>
     *   <li>没有歌词才新建；已存在时只覆盖正文与内容类型，翻译/罗马音/偏移保持原值；</li>
     *   <li>写成功后回写 {@code t_song.lyric_id} 并刷新 ES 文档——指针和索引都不能落后于正文。</li>
     * </ol>
     *
     * <p>并发/删除两类「看不见已有行」的情况都在这里兜底：
     * <ul>
     *   <li>两个请求同时走新建分支：后一个 INSERT 撞 `uk_lyric_song`，
     *       用当前读重新取行并转为覆盖，不再把 99001 抛给运营；</li>
     *   <li>歌词被逻辑删除过：唯一键仍被墓碑行占用，先复活再覆盖，
     *       否则「删了再传」会永远失败。</li>
     * </ul>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long upsertContent(Long songId, String content, Integer contentType) {
        if (songId == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "歌曲ID不能为空");
        }
        if (!StringUtils.hasText(content)) {
            throw new BizException(ResultCode.PARAM_INVALID, "歌词内容不能为空");
        }
        // 1/2/3 是 t_lyric.content_type 的取值域；越界值会让播放器按未知类型处理
        if (contentType != null && (contentType < 1 || contentType > 3)) {
            throw new BizException(ResultCode.PARAM_INVALID, "歌词内容类型仅支持 1/2/3");
        }
        String text = content.trim();
        // 显式类型优先：逐字 LRC（2）和标准 LRC 的正文都含时间轴，无法从文本反推
        int type = contentType == null ? detectContentType(text) : contentType;

        Lyric exists = getBySongId(songId);
        if (exists != null) {
            return updateExisting(exists, songId, text, type);
        }

        // 逻辑删除的歌词仍占着唯一键：先尝试复活墓碑行
        Lyric tombstone = baseMapper.selectBySongIdIncludeDeleted(songId);
        if (tombstone != null) {
            if (baseMapper.reviveContent(tombstone.getId(), text, type) > 0) {
                linkSong(songId, tombstone.getId());
                syncSongAfterCommit(songId);
                return tombstone.getId();
            }
            // 复活失败说明另一个请求刚恢复了它，按普通更新处理
            exists = getBySongId(songId);
            if (exists != null) {
                return updateExisting(exists, songId, text, type);
            }
        }

        Lyric entity = new Lyric();
        entity.setSongId(songId);
        entity.setContent(text);
        entity.setContentType(type);
        entity.setOffsetMs(0);
        // 导入/后台写入的歌词视为运营已确认，直接可展示；歌词审核流程不在本期范围
        entity.setAuditStatus(1);
        try {
            save(entity);
        } catch (DuplicateKeyException e) {
            // 并发首写：当前读绕过 REPEATABLE READ 快照，才能看到另一事务刚提交的行
            Lyric current = baseMapper.selectBySongIdIncludeDeletedForUpdate(songId);
            if (current == null) {
                throw e;
            }
            if (Integer.valueOf(1).equals(current.getDeleted())) {
                if (baseMapper.reviveContent(current.getId(), text, type) > 0) {
                    linkSong(songId, current.getId());
                    syncSongAfterCommit(songId);
                    return current.getId();
                }
                current = getBySongId(songId);
                if (current == null) {
                    throw e;
                }
            }
            log.info("歌词并发首写，转为覆盖已有记录 songId={} lyricId={}", songId, current.getId());
            return updateExisting(current, songId, text, type);
        }
        linkSong(songId, entity.getId());
        syncSongAfterCommit(songId);
        return entity.getId();
    }

    /** 只覆盖正文与类型：翻译、罗马音、偏移在其它入口维护，不能被一次导入清空。 */
    private Long updateExisting(Lyric exists, Long songId, String text, int type) {
        // 用补丁实体而不是「查出来改字段再整行更新」，是为了让未赋值字段不进入 UPDATE 语句
        Lyric patch = new Lyric();
        patch.setId(exists.getId());
        patch.setContent(text);
        patch.setContentType(type);
        // 带详情返回的 version：并发编辑同一份歌词时由乐观锁插件返回 0 行 → 10008
        patch.setVersion(exists.getVersion());
        updateWithVersion(patch);
        linkSong(songId, exists.getId());
        syncSongAfterCommit(songId);
        return exists.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Lyric exists = detail(id);
        removeSafe(exists.getId());
        unlinkSong(exists.getSongId(), exists.getId());
        syncSongAfterCommit(exists.getSongId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeBySongId(Long songId) {
        Lyric exists = getBySongId(songId);
        if (exists != null) {
            removeSafe(exists.getId());
            unlinkSong(songId, exists.getId());
        }
        syncSongAfterCommit(songId);
    }

    private void syncSongAfterCommit(Long songId) {
        if (songId == null) {
            return;
        }
        SearchIndexSyncService.afterCommit(() -> searchIndexSyncService.syncSong(songId));
    }

    /** 含时间轴按标准 LRC，否则按纯文本；逐字 LRC（2）无法从正文可靠识别，需显式传 contentType。 */
    private int detectContentType(String content) {
        return LRC_TIMESTAMP.matcher(content).find() ? 1 : 3;
    }

    /**
     * 回写 `t_song.lyric_id`。
     *
     * <p>失败只记日志：歌词已经落库，指针丢了最多让 AI 情绪分析跳过这首，
     * 不该把整个歌词写入回滚掉。用条件 UPDATE 而不是实体更新，避免动到乐观锁版本号。
     */
    private void linkSong(Long songId, Long lyricId) {
        if (songId == null || lyricId == null) {
            return;
        }
        try {
            songMapper.update(null, Wrappers.<Song>lambdaUpdate()
                    .eq(Song::getId, songId)
                    .set(Song::getLyricId, lyricId));
        } catch (Exception e) {
            log.warn("回写歌曲歌词指针失败 songId={} lyricId={} cause={}", songId, lyricId, e.toString());
        }
    }

    /** 清空指针时带 lyric_id 条件：避免误清掉后来新建的那份歌词的指针。 */
    private void unlinkSong(Long songId, Long lyricId) {
        if (songId == null || lyricId == null) {
            return;
        }
        try {
            songMapper.update(null, Wrappers.<Song>lambdaUpdate()
                    .eq(Song::getId, songId)
                    .eq(Song::getLyricId, lyricId)
                    .set(Song::getLyricId, null));
        } catch (Exception e) {
            log.warn("清空歌曲歌词指针失败 songId={} lyricId={} cause={}", songId, lyricId, e.toString());
        }
    }

    private void checkOffset(Integer offsetMs) {
        if (offsetMs != null && Math.abs(offsetMs) > MAX_OFFSET_MS) {
            throw new BizException(ResultCode.PARAM_INVALID, "歌词偏移需在 ±" + MAX_OFFSET_MS + "ms 内");
        }
    }
}
