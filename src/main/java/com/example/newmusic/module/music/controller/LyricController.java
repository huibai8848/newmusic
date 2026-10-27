package com.example.newmusic.module.music.controller;

import com.example.newmusic.common.result.R;
import com.example.newmusic.module.music.dto.LyricSaveReq;
import com.example.newmusic.module.music.entity.Lyric;
import com.example.newmusic.module.music.service.LyricService;
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

@Api(tags = "曲库-歌词")
@RestController
@RequestMapping("/api/v1/music/lyrics")
public class LyricController {

    private final LyricService lyricService;

    public LyricController(LyricService lyricService) {
        this.lyricService = lyricService;
    }

    @ApiOperation(value = "按歌曲取歌词", notes = "无歌词时 data 为 null，前端显示「暂无歌词」")
    @GetMapping("/{songId}")
    public R<Lyric> getBySongId(@PathVariable Long songId) {
        return R.ok(lyricService.getBySongId(songId));
    }

    @ApiOperation("新增歌词")
    @PostMapping
    @PreAuthorize("hasAuthority('music:lyric:create')")
    public R<Long> create(@RequestBody @Valid LyricSaveReq req) {
        return R.ok(lyricService.create(req));
    }

    @ApiOperation("编辑歌词")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('music:lyric:update')")
    public R<Long> update(@PathVariable Long id, @RequestBody @Valid LyricSaveReq req) {
        lyricService.update(id, req);
        return R.ok(id);
    }

    @ApiOperation("删除歌词")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('music:lyric:delete')")
    public R<Long> delete(@PathVariable Long id) {
        lyricService.delete(id);
        return R.ok(id);
    }
}
