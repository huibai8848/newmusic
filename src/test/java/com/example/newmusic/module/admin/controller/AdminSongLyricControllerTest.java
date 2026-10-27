package com.example.newmusic.module.admin.controller;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.R;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.admin.dto.SongLyricSaveReq;
import com.example.newmusic.module.admin.service.AdminContentService;
import com.example.newmusic.module.music.entity.Lyric;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.service.LyricService;
import com.example.newmusic.module.music.service.SongService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 后台歌词读写接口的委派单测。
 *
 * <p>只测 Controller 这一层的职责：**先校验歌曲存在，再碰歌词**。
 * 少了这一步，对不存在的 songId 调 {@code PUT /admin/songs/{id}/lyric} 会写出一条
 * 谁也读不到的孤儿歌词（C 端按歌曲查、后台按歌曲查都找不到）。
 *
 * <p>权限码 {@code admin:song:list/edit} 与 {@code @Valid} 由 Spring Security / MVC
 * 在运行时生效，不在本测试范围内；DTO 约束另有 {@code SongLyricSaveReqValidationTest}，
 * 端到端权限由 {@code scripts/verify-09.mjs} 覆盖。
 */
@DisplayName("AdminSongController 歌词接口")
class AdminSongLyricControllerTest {

    private SongService songService;
    private LyricService lyricService;
    private AdminSongController controller;

    @BeforeEach
    void setUp() {
        songService = mock(SongService.class);
        lyricService = mock(LyricService.class);
        controller = new AdminSongController(songService, lyricService, mock(AdminContentService.class));
    }

    @Test
    @DisplayName("读取：歌曲存在但没有歌词 → code=0 且 data=null（前端据此显示空文本框）")
    void getLyricReturnsNullWhenAbsent() {
        when(songService.detail(7L)).thenReturn(song(7L));
        when(lyricService.getBySongId(7L)).thenReturn(null);

        R<Lyric> result = controller.lyric(7L);

        assertEquals(ResultCode.SUCCESS.getCode(), result.getCode());
        assertNull(result.getData());
        verify(songService).detail(7L);
        verify(lyricService).getBySongId(7L);
    }

    @Test
    @DisplayName("读取：歌曲不存在时业务异常原样抛出，不查歌词表")
    void getLyricRejectsMissingSong() {
        when(songService.detail(404L)).thenThrow(new BizException(ResultCode.SONG_NOT_FOUND));

        BizException ex = assertThrows(BizException.class, () -> controller.lyric(404L));

        assertEquals(ResultCode.SONG_NOT_FOUND.getCode(), ex.getCode());
        verify(lyricService, never()).getBySongId(404L);
    }

    @Test
    @DisplayName("保存：先校验歌曲，再按 songId upsert，返回歌词 ID")
    void saveLyricDelegatesAfterSongCheck() {
        when(songService.detail(7L)).thenReturn(song(7L));
        SongLyricSaveReq req = new SongLyricSaveReq();
        req.setContent("[00:01.00]第一句");
        req.setContentType(1);
        when(lyricService.upsertContent(7L, req.getContent(), 1)).thenReturn(900L);

        R<Long> result = controller.saveLyric(7L, req);

        assertEquals(ResultCode.SUCCESS.getCode(), result.getCode());
        assertEquals(900L, result.getData());
        verify(songService).detail(7L);
        verify(lyricService).upsertContent(7L, "[00:01.00]第一句", 1);
    }

    @Test
    @DisplayName("保存：歌曲不存在时直接失败，绝不写出孤儿歌词")
    void saveLyricRejectsMissingSong() {
        when(songService.detail(404L)).thenThrow(new BizException(ResultCode.SONG_NOT_FOUND));
        SongLyricSaveReq req = new SongLyricSaveReq();
        req.setContent("歌词");

        assertThrows(BizException.class, () -> controller.saveLyric(404L, req));
        verifyNoInteractions(lyricService);
    }

    @Test
    @DisplayName("读取：有歌词时原样返回实体（含 contentType/version 供前端回传）")
    void getLyricReturnsEntity() {
        Lyric lyric = new Lyric();
        lyric.setId(900L);
        lyric.setSongId(7L);
        lyric.setContent("[00:01.00]第一句");
        lyric.setContentType(1);
        lyric.setVersion(2);
        when(songService.detail(7L)).thenReturn(song(7L));
        when(lyricService.getBySongId(7L)).thenReturn(lyric);

        R<Lyric> result = controller.lyric(7L);

        assertSame(lyric, result.getData());
    }

    private Song song(Long id) {
        Song song = new Song();
        song.setId(id);
        return song;
    }
}
