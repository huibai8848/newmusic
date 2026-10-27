package com.example.newmusic.module.music.controller;

import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
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

@Api(tags = "曲库-专辑")
@RestController
@RequestMapping("/api/v1/music/albums")
public class AlbumController {

    private final AlbumService albumService;

    public AlbumController(AlbumService albumService) {
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
    @PreAuthorize("hasAuthority('music:album:create')")
    public R<Long> create(@RequestBody @Valid AlbumSaveReq req) {
        return R.ok(albumService.create(req));
    }

    @ApiOperation("编辑专辑")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('music:album:update')")
    public R<Long> update(@PathVariable Long id, @RequestBody @Valid AlbumSaveReq req) {
        albumService.update(id, req);
        return R.ok(id);
    }

    @ApiOperation(value = "删除专辑", notes = "旗下仍有歌曲时返回 30045")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('music:album:delete')")
    public R<Long> delete(@PathVariable Long id) {
        albumService.delete(id);
        return R.ok(id);
    }
}
