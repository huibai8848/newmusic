package com.example.newmusic.module.admin.controller;

import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.music.dto.AlbumQueryReq;
import com.example.newmusic.module.music.dto.AlbumSaveReq;
import com.example.newmusic.module.music.entity.Album;
import com.example.newmusic.module.music.service.AlbumService;
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

@Api(tags = "后台接口-专辑管理")
@RestController
@RequestMapping("/api/v1/admin/albums")
@PreAuthorize("hasAuthority('admin:album:manage')")
public class AdminAlbumController {

    private final AlbumService albumService;

    public AdminAlbumController(AlbumService albumService) {
        this.albumService = albumService;
    }

    @ApiOperation("专辑列表")
    @GetMapping
    public R<PageResult<Album>> list(AlbumQueryReq query) {
        return R.ok(albumService.page(query));
    }

    @ApiOperation("专辑详情")
    @GetMapping("/{id}")
    public R<Album> detail(@PathVariable Long id) {
        return R.ok(albumService.detail(id));
    }

    @ApiOperation("新增专辑")
    @PostMapping
    @AdminOpLog(type = "ALBUM_CREATE", target = "ALBUM", description = "新增专辑")
    public R<Long> create(@RequestBody @Valid AlbumSaveReq req) {
        return R.ok(albumService.create(req));
    }

    @ApiOperation("编辑专辑")
    @PutMapping("/{id}")
    @AdminOpLog(type = "ALBUM_UPDATE", target = "ALBUM", description = "编辑专辑")
    public R<Long> update(@PathVariable Long id, @RequestBody @Valid AlbumSaveReq req) {
        albumService.update(id, req);
        return R.ok(id);
    }

    @ApiOperation(value = "删除专辑", notes = "有关联歌曲时拒绝（BR-18-071）")
    @DeleteMapping("/{id}")
    @AdminOpLog(type = "ALBUM_DELETE", target = "ALBUM", description = "删除专辑", sensitive = true)
    public R<Long> delete(@PathVariable Long id) {
        albumService.delete(id);
        return R.ok(id);
    }
}
