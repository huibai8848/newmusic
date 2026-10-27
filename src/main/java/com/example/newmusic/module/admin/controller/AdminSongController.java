package com.example.newmusic.module.admin.controller;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.admin.dto.ReasonReq;
import com.example.newmusic.module.admin.dto.SongBatchStatusReq;
import com.example.newmusic.module.admin.dto.SongImportReq;
import com.example.newmusic.module.admin.dto.SongLyricSaveReq;
import com.example.newmusic.module.admin.service.AdminContentService;
import com.example.newmusic.module.admin.vo.SongImportResultVO;
import com.example.newmusic.module.music.dto.SongQueryReq;
import com.example.newmusic.module.music.dto.SongSaveReq;
import com.example.newmusic.module.music.entity.Lyric;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.service.LyricService;
import com.example.newmusic.module.music.service.SongService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * 后台歌曲管理。权限码与 C 端曲库写接口（music:*）刻意分开：
 * 后台管理员只拥有 admin:song:*，不存在「拿到后台身份就自动拥有前台运营权限」的捷径。
 */
@Api(tags = "后台接口-歌曲管理")
@RestController
@RequestMapping("/api/v1/admin/songs")
public class AdminSongController {

    private static final int MAX_BATCH = 200;

    private final SongService songService;
    private final LyricService lyricService;
    private final AdminContentService contentService;

    public AdminSongController(SongService songService, LyricService lyricService,
                               AdminContentService contentService) {
        this.songService = songService;
        this.lyricService = lyricService;
        this.contentService = contentService;
    }

    @ApiOperation(value = "歌曲列表", notes = "包含下架/待审；支持状态、审核状态、歌手、专辑等筛选")
    @GetMapping
    @PreAuthorize("hasAuthority('admin:song:list')")
    public R<PageResult<Song>> list(SongQueryReq query) {
        return R.ok(songService.page(query));
    }

    @ApiOperation("歌曲详情")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:song:list')")
    public R<Song> detail(@PathVariable Long id) {
        return R.ok(songService.detail(id));
    }

    @ApiOperation(value = "新增歌曲", notes = "标准音质音频必填；默认待审核（BR-18-065）")
    @PostMapping
    @PreAuthorize("hasAuthority('admin:song:create')")
    @AdminOpLog(type = "SONG_CREATE", target = "SONG", description = "新增歌曲")
    public R<Long> create(@RequestBody @Valid SongSaveReq req) {
        return R.ok(songService.create(req));
    }

    @ApiOperation("编辑歌曲")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:song:edit')")
    @AdminOpLog(type = "SONG_UPDATE", target = "SONG", description = "编辑歌曲")
    public R<Long> update(@PathVariable Long id, @RequestBody @Valid SongSaveReq req) {
        songService.update(id, req);
        return R.ok(id);
    }

    @ApiOperation(value = "读取歌曲歌词", notes = "没有歌词时 data 为 null")
    @GetMapping("/{id}/lyric")
    @PreAuthorize("hasAuthority('admin:song:list')")
    public R<Lyric> lyric(@PathVariable Long id) {
        songService.detail(id);
        return R.ok(lyricService.getBySongId(id));
    }

    @ApiOperation(value = "保存歌曲歌词", notes = "没有则创建、有则覆盖正文；返回歌词 ID")
    @PutMapping("/{id}/lyric")
    @PreAuthorize("hasAuthority('admin:song:edit')")
    @AdminOpLog(type = "SONG_LYRIC", target = "SONG", description = "保存歌曲歌词")
    public R<Long> saveLyric(@PathVariable Long id, @RequestBody @Valid SongLyricSaveReq req) {
        // 先按歌曲校验存在性：upsertContent 按 songId 写，不存在的歌会留下一条孤儿歌词
        songService.detail(id);
        return R.ok(lyricService.upsertContent(id, req.getContent(), req.getContentType()));
    }

    @ApiOperation(value = "上架/下架", notes = "上架仍受版权与审核状态约束（BR-04-001/002）")
    @PutMapping("/{id}/status/{status}")
    @PreAuthorize("hasAnyAuthority('admin:song:online','admin:song:offline')")
    @AdminOpLog(type = "SONG_STATUS", target = "SONG", description = "歌曲上架/下架")
    public R<Long> changeStatus(@PathVariable Long id, @PathVariable Integer status) {
        songService.changeStatus(id, status);
        return R.ok(id);
    }

    @ApiOperation(value = "批量上下架", notes = "单次最多 200 首；逐首校验，失败即返回业务错误")
    @PostMapping("/batch-status")
    @PreAuthorize("hasAnyAuthority('admin:song:online','admin:song:offline')")
    @AdminOpLog(type = "SONG_BATCH_STATUS", target = "SONG", description = "批量上架/下架歌曲")
    public R<Integer> batchStatus(@RequestBody @Valid SongBatchStatusReq req) {
        if (req.getIds().size() > MAX_BATCH) {
            throw new BizException(ResultCode.PARAM_INVALID, "单次最多操作 " + MAX_BATCH + " 首歌曲");
        }
        int handled = 0;
        for (Long id : req.getIds()) {
            songService.changeStatus(id, req.getStatus());
            handled++;
        }
        return R.ok(handled);
    }

    @ApiOperation(value = "删除歌曲", notes = "仅超管；必须填写原因（BR-18-060）")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:song:delete')")
    @AdminOpLog(type = "SONG_DELETE", target = "SONG", description = "删除歌曲", sensitive = true)
    public R<Long> delete(@PathVariable Long id, @RequestBody @Valid ReasonReq req) {
        songService.delete(id);
        return R.ok(id);
    }

    @ApiOperation(value = "JSON 批量导入", notes = "逐行独立事务，返回失败行报告（EX-18-007-4）")
    @PostMapping("/import")
    @PreAuthorize("hasAuthority('admin:song:create')")
    @AdminOpLog(type = "SONG_IMPORT", target = "SONG", description = "批量导入歌曲")
    public R<SongImportResultVO> importSongs(@RequestBody @Valid SongImportReq req) {
        return R.ok(contentService.importSongs(req.getRows()));
    }

    @ApiOperation(value = "导出 CSV", notes = "最多 10000 行，UTF-8 BOM")
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('admin:song:list')")
    @AdminOpLog(type = "SONG_EXPORT", target = "SONG", description = "导出歌曲 CSV")
    public void export(SongQueryReq query, HttpServletResponse response) throws IOException {
        String filename = "songs-" + LocalDate.now() + ".csv";
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, "UTF-8")
                        .replace("+", "%20"));
        contentService.exportSongs(query, response.getOutputStream());
    }
}
