package com.example.newmusic.module.music.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.music.dto.LyricSaveReq;
import com.example.newmusic.module.music.entity.Lyric;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.mapper.LyricMapper;
import com.example.newmusic.module.music.mapper.SongMapper;
import com.example.newmusic.module.search.engine.es.SearchIndexSyncService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 按歌曲写入歌词正文的单测（本次「上传后没有歌词」的后端核心）。
 *
 * <p>为什么不启动 Spring：这里的风险都不在 Web 层，而在**覆盖语义**——
 * 导入重跑时不能把翻译/罗马音/偏移清空，不能把非法 content_type 写进库，
 * 不能因为回写歌曲指针失败就把已写入的歌词回滚。用 mock mapper 精确锁这些行为，
 * 比连真库跑得快，也能断言「UPDATE 里到底带了哪些字段」。
 *
 * <p>真实 SQL、乐观锁与唯一键的行为由 {@code scripts/verify-lyrics-audit.mjs} 做端到端覆盖。
 */
@DisplayName("LyricServiceImpl 歌词正文写入")
class LyricServiceImplTest {

    private LyricMapper lyricMapper;
    private SongMapper songMapper;
    private SearchIndexSyncService syncService;
    private LyricServiceImpl service;

    /**
     * `linkSong` 用 LambdaUpdateWrapper（`Song::getId`）。没有 Spring 容器时 MyBatis-Plus
     * 的 lambda 缓存是空的，构造 wrapper 会抛「can not find lambda cache」并被 linkSong 的
     * 保护性 catch 吞掉——测试就会误以为「指针回写没执行」。先手动建一次表元数据。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace("com.example.newmusic.test");
        TableInfoHelper.initTableInfo(assistant, Song.class);
    }

    @BeforeEach
    void setUp() {
        lyricMapper = mock(LyricMapper.class);
        songMapper = mock(SongMapper.class);
        syncService = mock(SearchIndexSyncService.class);
        service = new LyricServiceImpl(syncService, songMapper);
        // ServiceImpl 的 baseMapper 是 MyBatis-Plus 注入的 protected 字段，单测里手动塞 mock
        ReflectionTestUtils.setField(service, "baseMapper", lyricMapper);
    }

    @Test
    @DisplayName("没有歌词时新建：trim 正文、自动识别 LRC、回写指针并刷新索引")
    void createsLyricWhenAbsent() {
        when(lyricMapper.selectOne(any())).thenReturn(null);
        when(lyricMapper.insert(any(Lyric.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Lyric.class).setId(900L);
            return 1;
        });

        Long id = service.upsertContent(5L, "  [00:01.00]第一行\n[00:02.50]第二行  ", null);

        assertEquals(900L, id);
        Lyric inserted = captureInsert();
        assertEquals(5L, inserted.getSongId());
        // 首尾空白来自 .lrc 的空行，原样入库会让播放器首屏空白
        assertEquals("[00:01.00]第一行\n[00:02.50]第二行", inserted.getContent());
        assertEquals(Integer.valueOf(1), inserted.getContentType());
        assertEquals(Integer.valueOf(0), inserted.getOffsetMs());
        // 运营导入的歌词直接可展示，不进入歌词审核队列
        assertEquals(Integer.valueOf(1), inserted.getAuditStatus());
        assertNull(inserted.getTranslation());
        assertNull(inserted.getRomaji());

        verify(songMapper).update(isNull(), any());
        verify(syncService).syncSong(5L);
    }

    @Test
    @DisplayName("纯文本自动识别为 3，而不是硬编码 1")
    void detectsPlainText() {
        when(lyricMapper.selectOne(any())).thenReturn(null);
        stubInsert(901L);

        service.upsertContent(5L, "没有时间轴的一句歌词", null);

        assertEquals(Integer.valueOf(3), captureInsert().getContentType());
    }

    @Test
    @DisplayName("显式 content_type 优先于自动识别（逐字 LRC 也含时间轴）")
    void explicitContentTypeWins() {
        when(lyricMapper.selectOne(any())).thenReturn(null);
        stubInsert(902L);

        service.upsertContent(5L, "[00:01.00]逐字歌词", 2);

        assertEquals(Integer.valueOf(2), captureInsert().getContentType());
    }

    @Test
    @DisplayName("已有歌词时只改正文/类型/版本，不清空翻译、罗马音与偏移")
    void updateKeepsOtherFields() {
        Lyric existing = lyric(900L, 5L, 3, 120);
        existing.setTranslation("[00:01.00]translation");
        existing.setRomaji("[00:01.00]romaji");
        when(lyricMapper.selectOne(any())).thenReturn(existing);
        when(lyricMapper.updateById(any(Lyric.class))).thenReturn(1);

        Long id = service.upsertContent(5L, "纯文本歌词", null);

        assertEquals(900L, id);
        ArgumentCaptor<Lyric> captor = ArgumentCaptor.forClass(Lyric.class);
        verify(lyricMapper).updateById(captor.capture());
        Lyric patch = captor.getValue();
        assertEquals(900L, patch.getId());
        assertEquals("纯文本歌词", patch.getContent());
        assertEquals(Integer.valueOf(3), patch.getContentType());
        // 乐观锁必须带详情里的 version，否则并发编辑没有保护
        assertEquals(Integer.valueOf(3), patch.getVersion());
        // 补丁实体未赋值的字段不能出现在 UPDATE 里，否则翻译/罗马音会被清空
        assertNull(patch.getTranslation());
        assertNull(patch.getRomaji());
        assertNull(patch.getOffsetMs());
        assertNull(patch.getAuditStatus());
        verify(songMapper).update(isNull(), any());
        verify(syncService).syncSong(5L);
    }

    @Test
    @DisplayName("非法 content_type 在写库前拒绝（10001）")
    void rejectsInvalidContentType() {
        BizException ex = assertThrows(BizException.class,
                () -> service.upsertContent(5L, "歌词", 9));

        assertEquals(ResultCode.PARAM_INVALID.getCode(), ex.getCode());
        verifyNoInteractions(lyricMapper, songMapper, syncService);
    }

    @Test
    @DisplayName("空歌曲 ID / 空正文拒绝，且不产生任何查询")
    void rejectsBlankInput() {
        assertThrows(BizException.class, () -> service.upsertContent(null, "歌词", 1));
        assertThrows(BizException.class, () -> service.upsertContent(5L, null, 1));
        assertThrows(BizException.class, () -> service.upsertContent(5L, "   \n\t", 1));
        verifyNoInteractions(lyricMapper, songMapper, syncService);
    }

    @Test
    @DisplayName("回写 t_song.lyric_id 失败只记日志，不让已写入的歌词回滚")
    void linkFailureDoesNotFailWrite() {
        when(lyricMapper.selectOne(any())).thenReturn(null);
        stubInsert(903L);
        when(songMapper.update(isNull(), any())).thenThrow(new IllegalStateException("lock wait timeout"));

        Long id = assertDoesNotThrow(() -> service.upsertContent(5L, "歌词", 1));

        assertEquals(903L, id);
        // 正文已经落库；索引刷新不应被指针回写失败阻断
        verify(syncService).syncSong(5L);
    }

    @Test
    @DisplayName("按歌曲查询：空 ID 不发 SQL，命中时原样返回")
    void getBySongIdHandlesNullAndHit() {
        assertNull(service.getBySongId(null));
        verifyNoInteractions(lyricMapper);

        Lyric existing = lyric(1L, 5L, 1, 0);
        when(lyricMapper.selectOne(any())).thenReturn(existing);
        assertSame(existing, service.getBySongId(5L));
    }

    @Test
    @DisplayName("content_type 边界值 1/2/3 均可用，0 和 4 拒绝")
    void contentTypeBoundaries() {
        when(lyricMapper.selectOne(any())).thenReturn(null);
        when(lyricMapper.insert(any(Lyric.class))).thenAnswer(invocation -> {
            Lyric entity = invocation.getArgument(0, Lyric.class);
            entity.setId(100L + entity.getContentType());
            return 1;
        });

        for (int type = 1; type <= 3; type++) {
            service.upsertContent(5L, "x", type);
        }

        ArgumentCaptor<Lyric> captor = ArgumentCaptor.forClass(Lyric.class);
        verify(lyricMapper, org.mockito.Mockito.times(3)).insert(captor.capture());
        assertEquals(Arrays.asList(1, 2, 3), captor.getAllValues().stream()
                .map(Lyric::getContentType).collect(java.util.stream.Collectors.toList()));

        assertThrows(BizException.class, () -> service.upsertContent(5L, "x", 0));
        assertThrows(BizException.class, () -> service.upsertContent(5L, "x", 4));
        // 非法值没有走到插入
        verify(lyricMapper, org.mockito.Mockito.times(3)).insert(any(Lyric.class));
    }

    @Test
    @DisplayName("歌词被逻辑删除过：按歌曲写入复活墓碑行，不再撞 uk_lyric_song")
    void revivesTombstoneInsteadOfInsert() {
        when(lyricMapper.selectOne(any())).thenReturn(null);
        Lyric tombstone = lyric(800L, 5L, 2, 0);
        tombstone.setDeleted(1);
        when(lyricMapper.selectBySongIdIncludeDeleted(5L)).thenReturn(tombstone);
        when(lyricMapper.reviveContent(800L, "新正文", 3)).thenReturn(1);

        Long id = service.upsertContent(5L, "  新正文  ", null);

        assertEquals(800L, id);
        // 复活走 UPDATE，不能再 INSERT（唯一键仍被墓碑行占着）
        verify(lyricMapper, never()).insert(any(Lyric.class));
        verify(lyricMapper).reviveContent(800L, "新正文", 3);
        verify(songMapper).update(isNull(), any());
        verify(syncService).syncSong(5L);
    }

    @Test
    @DisplayName("复活失败说明并发请求已恢复：转为覆盖可见行")
    void reviveRaceFallsBackToUpdate() {
        Lyric existing = lyric(801L, 5L, 4, 0);
        when(lyricMapper.selectOne(any())).thenReturn(null, existing);
        Lyric tombstone = lyric(801L, 5L, 3, 0);
        tombstone.setDeleted(1);
        when(lyricMapper.selectBySongIdIncludeDeleted(5L)).thenReturn(tombstone);
        when(lyricMapper.reviveContent(anyLong(), anyString(), anyInt())).thenReturn(0);
        when(lyricMapper.updateById(any(Lyric.class))).thenReturn(1);

        Long id = service.upsertContent(5L, "正文", 1);

        assertEquals(801L, id);
        verify(lyricMapper, never()).insert(any(Lyric.class));
        verify(lyricMapper).updateById(any(Lyric.class));
    }

    @Test
    @DisplayName("并发首写撞唯一键：当前读后转为覆盖，不把 99001 抛给调用方")
    void concurrentFirstWriteFallsBackToUpdate() {
        when(lyricMapper.selectOne(any())).thenReturn(null);
        when(lyricMapper.selectBySongIdIncludeDeleted(5L)).thenReturn(null);
        when(lyricMapper.insert(any(Lyric.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'uk_lyric_song'"));
        Lyric current = lyric(802L, 5L, 1, 0);
        when(lyricMapper.selectBySongIdIncludeDeletedForUpdate(5L)).thenReturn(current);
        when(lyricMapper.updateById(any(Lyric.class))).thenReturn(1);

        Long id = service.upsertContent(5L, "并发写入的正文", null);

        assertEquals(802L, id);
        // 当前读是绕过 REPEATABLE READ 快照的关键
        verify(lyricMapper).selectBySongIdIncludeDeletedForUpdate(5L);
        ArgumentCaptor<Lyric> captor = ArgumentCaptor.forClass(Lyric.class);
        verify(lyricMapper).updateById(captor.capture());
        assertEquals("并发写入的正文", captor.getValue().getContent());
        verify(syncService).syncSong(5L);
    }

    @Test
    @DisplayName("并发首写撞上墓碑行：先复活，不覆盖 deleted 字段")
    void concurrentFirstWriteRevivesTombstone() {
        when(lyricMapper.selectOne(any())).thenReturn(null);
        when(lyricMapper.selectBySongIdIncludeDeleted(5L)).thenReturn(null);
        when(lyricMapper.insert(any(Lyric.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'uk_lyric_song'"));
        Lyric tombstone = lyric(803L, 5L, 2, 0);
        tombstone.setDeleted(1);
        when(lyricMapper.selectBySongIdIncludeDeletedForUpdate(5L)).thenReturn(tombstone);
        when(lyricMapper.reviveContent(803L, "正文", 3)).thenReturn(1);

        assertEquals(803L, service.upsertContent(5L, "正文", null));
        verify(lyricMapper).reviveContent(803L, "正文", 3);
        verify(lyricMapper, never()).updateById(any(Lyric.class));
    }

    @Test
    @DisplayName("当前读也看不到行时保留原始 DuplicateKeyException（不吞异常）")
    void rethrowsDuplicateKeyWhenCurrentReadMisses() {
        when(lyricMapper.selectOne(any())).thenReturn(null);
        when(lyricMapper.selectBySongIdIncludeDeleted(5L)).thenReturn(null);
        when(lyricMapper.insert(any(Lyric.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'uk_lyric_song'"));
        when(lyricMapper.selectBySongIdIncludeDeletedForUpdate(5L)).thenReturn(null);

        assertThrows(DuplicateKeyException.class,
                () -> service.upsertContent(5L, "正文", null));
    }

    @Test
    @DisplayName("create 撞到墓碑行：返回 10010 并提示用按歌曲写入接口恢复")
    void createRejectsTombstoneWithReadableCode() {
        LyricSaveReq req = new LyricSaveReq();
        req.setSongId(5L);
        req.setContent("[00:01.00]歌词");
        when(lyricMapper.selectOne(any())).thenReturn(null);
        Lyric tombstone = lyric(804L, 5L, 1, 0);
        tombstone.setDeleted(1);
        when(lyricMapper.selectBySongIdIncludeDeleted(5L)).thenReturn(tombstone);

        BizException ex = assertThrows(BizException.class, () -> service.create(req));

        assertEquals(ResultCode.DUPLICATE_DATA.getCode(), ex.getCode());
        verify(lyricMapper, never()).insert(any(Lyric.class));
    }

    @Test
    @DisplayName("create 并发撞唯一键：转成 10010，而不是泄漏 99001")
    void createConvertsDuplicateKey() {
        LyricSaveReq req = new LyricSaveReq();
        req.setSongId(5L);
        req.setContent("[00:01.00]歌词");
        when(lyricMapper.selectOne(any())).thenReturn(null);
        when(lyricMapper.selectBySongIdIncludeDeleted(5L)).thenReturn(null);
        when(lyricMapper.insert(any(Lyric.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'uk_lyric_song'"));

        BizException ex = assertThrows(BizException.class, () -> service.create(req));

        assertEquals(ResultCode.DUPLICATE_DATA.getCode(), ex.getCode());
    }

    // ---------------------------------------------------------------- helpers

    private Lyric lyric(Long id, Long songId, int version, int offsetMs) {
        Lyric lyric = new Lyric();
        lyric.setId(id);
        lyric.setSongId(songId);
        lyric.setVersion(version);
        lyric.setOffsetMs(offsetMs);
        lyric.setAuditStatus(1);
        return lyric;
    }

    private void stubInsert(Long id) {
        when(lyricMapper.insert(any(Lyric.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Lyric.class).setId(id);
            return 1;
        });
    }

    private Lyric captureInsert() {
        ArgumentCaptor<Lyric> captor = ArgumentCaptor.forClass(Lyric.class);
        verify(lyricMapper).insert(captor.capture());
        return captor.getValue();
    }
}
