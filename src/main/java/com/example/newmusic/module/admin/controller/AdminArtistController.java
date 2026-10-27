package com.example.newmusic.module.admin.controller;

import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.music.dto.ArtistQueryReq;
import com.example.newmusic.module.music.dto.ArtistSaveReq;
import com.example.newmusic.module.music.entity.Artist;
import com.example.newmusic.module.music.service.ArtistService;
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

@Api(tags = "后台接口-歌手管理")
@RestController
@RequestMapping("/api/v1/admin/artists")
@PreAuthorize("hasAuthority('admin:artist:manage')")
public class AdminArtistController {

    private final ArtistService artistService;

    public AdminArtistController(ArtistService artistService) {
        this.artistService = artistService;
    }

    @ApiOperation("歌手列表")
    @GetMapping
    public R<PageResult<Artist>> list(ArtistQueryReq query) {
        return R.ok(artistService.page(query));
    }

    @ApiOperation("歌手详情")
    @GetMapping("/{id}")
    public R<Artist> detail(@PathVariable Long id) {
        return R.ok(artistService.detail(id));
    }

    @ApiOperation("新增歌手")
    @PostMapping
    @AdminOpLog(type = "ARTIST_CREATE", target = "ARTIST", description = "新增歌手")
    public R<Long> create(@RequestBody @Valid ArtistSaveReq req) {
        return R.ok(artistService.create(req));
    }

    @ApiOperation("编辑歌手")
    @PutMapping("/{id}")
    @AdminOpLog(type = "ARTIST_UPDATE", target = "ARTIST", description = "编辑歌手")
    public R<Long> update(@PathVariable Long id, @RequestBody @Valid ArtistSaveReq req) {
        artistService.update(id, req);
        return R.ok(id);
    }

    @ApiOperation(value = "删除歌手", notes = "有关联歌曲时拒绝（BR-18-069）")
    @DeleteMapping("/{id}")
    @AdminOpLog(type = "ARTIST_DELETE", target = "ARTIST", description = "删除歌手", sensitive = true)
    public R<Long> delete(@PathVariable Long id) {
        artistService.delete(id);
        return R.ok(id);
    }
}
