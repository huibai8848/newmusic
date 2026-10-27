package com.example.newmusic.module.music.service.impl;

import com.example.newmusic.infra.InfraProperties;
import com.example.newmusic.infra.cache.BloomSupport;
import com.example.newmusic.infra.cache.CacheService;
import com.example.newmusic.infra.lock.DistributedLockService;
import com.example.newmusic.module.music.dto.SongSaveReq;
import com.example.newmusic.module.music.mapper.PlaylistMapper;
import com.example.newmusic.module.music.mapper.PlaylistSongMapper;
import com.example.newmusic.module.music.mapper.SongTagMapper;
import com.example.newmusic.module.music.service.AlbumService;
import com.example.newmusic.module.music.service.ArtistService;
import com.example.newmusic.module.music.service.LyricService;
import com.example.newmusic.module.music.service.TagService;
import com.example.newmusic.module.search.engine.es.SearchIndexSyncService;
import com.example.newmusic.module.social.service.FollowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 歌曲新增/编辑时「随行歌词」的写入边界。
 *
 * <p>这里只锁 {@code saveLyricIfPresent} 一个私有方法，不启动 Spring、也不构造整条新增链路：
 * 本次缺陷的核心就是该方法没有被覆盖——上传歌曲带了歌词，`t_lyric` 却始终没有行。
 * 依赖全部 mock，用反射直接调用，能精确区分「没传歌词」与「传空串」两种语义。
 */
@DisplayName("SongServiceImpl 随行歌词写入")
class SongServiceImplTest {

    private LyricService lyricService;
    private SongServiceImpl service;

    @BeforeEach
    void setUp() {
        lyricService = mock(LyricService.class);
        service = new SongServiceImpl(
                mock(ArtistService.class),
                mock(AlbumService.class),
                lyricService,
                mock(TagService.class),
                mock(SongTagMapper.class),
                mock(PlaylistSongMapper.class),
                mock(PlaylistMapper.class),
                mock(FollowService.class),
                mock(SearchIndexSyncService.class),
                mock(CacheService.class),
                mock(DistributedLockService.class),
                mock(BloomSupport.class),
                new InfraProperties(),
                mock(ApplicationEventPublisher.class));
    }

    @Test
    @DisplayName("带歌词新增/编辑：按歌曲写入正文与内容类型")
    void writesLyricWhenPresent() {
        SongSaveReq req = new SongSaveReq();
        req.setLyric("  [00:01.00]第一行  ");
        req.setLyricContentType(1);

        ReflectionTestUtils.invokeMethod(service, "saveLyricIfPresent", 5L, req);

        // 原样交给 LyricService：trim、类型识别与并发兜底都在 upsertContent 里
        verify(lyricService).upsertContent(5L, "  [00:01.00]第一行  ", 1);
    }

    @Test
    @DisplayName("没传歌词 / 空串 / 纯空白都按「不改歌词」处理，不产生空记录")
    void skipsBlankLyric() {
        SongSaveReq absent = new SongSaveReq();
        SongSaveReq empty = new SongSaveReq();
        empty.setLyric("");
        SongSaveReq blank = new SongSaveReq();
        blank.setLyric("   \n\t");

        ReflectionTestUtils.invokeMethod(service, "saveLyricIfPresent", 5L, absent);
        ReflectionTestUtils.invokeMethod(service, "saveLyricIfPresent", 5L, empty);
        ReflectionTestUtils.invokeMethod(service, "saveLyricIfPresent", 5L, blank);

        verifyNoInteractions(lyricService);
    }

    @Test
    @DisplayName("歌曲 ID 或请求为空时直接返回")
    void skipsNullInputs() {
        SongSaveReq req = new SongSaveReq();
        req.setLyric("[00:01.00]歌词");

        ReflectionTestUtils.invokeMethod(service, "saveLyricIfPresent", null, req);
        ReflectionTestUtils.invokeMethod(service, "saveLyricIfPresent", 5L, null);

        verifyNoInteractions(lyricService);
    }

    @Test
    @DisplayName("歌词写入异常向上抛出，让歌曲事务一起回滚（不能留下有歌无词）")
    void propagatesLyricFailure() {
        SongSaveReq req = new SongSaveReq();
        req.setLyric("[00:01.00]歌词");
        org.mockito.Mockito.doThrow(new IllegalStateException("lyric db down"))
                .when(lyricService).upsertContent(anyLong(), anyString(), any());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "saveLyricIfPresent", 5L, req));
    }
}
