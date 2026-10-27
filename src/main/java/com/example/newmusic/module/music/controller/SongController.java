package com.example.newmusic.module.music.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.module.music.dto.SongQueryReq;
import com.example.newmusic.module.music.dto.SongSaveReq;
import com.example.newmusic.module.music.entity.Song;
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

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

@Api(tags = "曲库-歌曲")
@RestController
@RequestMapping("/api/v1/music/songs")
public class SongController {

    /** 后台读权限：决定列表是否可见未上架歌曲 */
    private static final String PERM_READ = "music:song:read";

    private final SongService songService;

    public SongController(SongService songService) {
        this.songService = songService;
    }

    @ApiOperation(value = "歌曲列表", notes = "公开接口仅返回已上架且审核通过的歌曲；"
            + "携带 music:song:read 权限可查全部（含下架/待审）")
    @GetMapping
    @RateLimit(limit = 300, windowSeconds = 60, type = "music")
    public R<PageResult<Song>> list(SongQueryReq query) {
        if (UserContext.hasPermission(PERM_READ)) {
            return R.ok(songService.page(query));
        }
        return R.ok(songService.pagePublished(query));
    }

    @ApiOperation(value = "歌曲详情", notes = "未上架歌曲仅对有 read 权限者可见；"
            + "命中缓存（模块 03），响应通常 <5ms")
    @GetMapping("/{id}")
    // PRD：IP 级公开读 300 次/分钟。键按 api 分片，所以这里的 300 只算这一个接口
    @RateLimit(limit = 300, windowSeconds = 60, type = "music")
    public R<Song> detail(@PathVariable Long id) {
        Song song = UserContext.hasPermission(PERM_READ) ? songService.detail(id) : songService.requirePlayable(id);
        return R.ok(song);
    }

    @ApiOperation("新增歌曲")
    @PostMapping
    @PreAuthorize("hasAuthority('music:song:create')")
    public R<Long> create(@RequestBody @Valid SongSaveReq req) {
        return R.ok(songService.create(req));
    }

    @ApiOperation(value = "编辑歌曲", notes = "需回传 version；上架需满足 BR-04-001/002")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('music:song:update')")
    public R<Long> update(@PathVariable Long id, @RequestBody @Valid SongSaveReq req) {
        songService.update(id, req);
        return R.ok(id);
    }

    @ApiOperation(value = "上架/下架", notes = "status: 0下架 1上架（上架强校验 BR-04-001/002）")
    @PutMapping("/{id}/status/{status}")
    @PreAuthorize("hasAnyAuthority('music:song:online','music:song:offline','music:song:update')")
    public R<Long> changeStatus(@PathVariable Long id, @PathVariable Integer status) {
        songService.changeStatus(id, status);
        return R.ok(id);
    }

    @ApiOperation(value = "歌曲审核", notes = "auditStatus: 0待审 1通过 2拒绝（拒绝时自动下架）")
    @PutMapping("/{id}/audit/{auditStatus}")
    @PreAuthorize("hasAuthority('music:song:audit')")
    public R<Long> audit(@PathVariable Long id, @PathVariable Integer auditStatus) {
        songService.audit(id, auditStatus);
        return R.ok(id);
    }

    @ApiOperation(value = "删除歌曲", notes = "逻辑删除 + 级联清理标签/歌单关联/歌词")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('music:song:delete')")
    public R<Long> delete(@PathVariable Long id) {
        songService.delete(id);
        return R.ok(id);
    }

    @ApiOperation(value = "批量删除歌曲", notes = "body: {\"ids\":[...]}；单次上限 500（10009）")
    @DeleteMapping("/batch")
    @PreAuthorize("hasAuthority('music:song:delete')")
    public R<Integer> batchDelete(@RequestBody Map<String, List<Long>> body) {
        List<Long> ids = body == null ? null : body.get("ids");
        songService.batchDelete(ids);
        return R.ok(ids == null ? 0 : ids.size());
    }
}
