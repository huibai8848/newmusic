package com.example.newmusic.module.music.controller;

import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.music.dto.PlaylistOrderReq;
import com.example.newmusic.module.music.dto.PlaylistQueryReq;
import com.example.newmusic.module.music.dto.PlaylistSaveReq;
import com.example.newmusic.module.music.dto.PlaylistSongReq;
import com.example.newmusic.module.music.entity.Playlist;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.service.PlaylistService;
import com.example.newmusic.module.music.service.SongService;
import com.example.newmusic.module.music.vo.PlaylistDetailVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@Api(tags = "曲库-歌单")
@RestController
@RequestMapping("/api/v1/music/playlists")
public class PlaylistController {

    private final PlaylistService playlistService;
    private final SongService songService;

    public PlaylistController(PlaylistService playlistService, SongService songService) {
        this.playlistService = playlistService;
        this.songService = songService;
    }

    @ApiOperation(value = "歌单列表", notes = "mine=true 返回我的歌单（含私密）；匿名仅见公开歌单")
    @GetMapping
    public R<PageResult<Playlist>> list(PlaylistQueryReq query) {
        return R.ok(playlistService.page(query));
    }

    @ApiOperation(value = "歌单详情", notes = "含歌曲分页；私密歌单仅创建者/管理员可见")
    @GetMapping("/{id}")
    public R<PlaylistDetailVO> detail(@PathVariable Long id,
                                      @ApiParam("歌曲页码") @RequestParam(defaultValue = "1") Integer pageNum,
                                      @ApiParam("歌曲每页条数") @RequestParam(defaultValue = "20") Integer pageSize) {
        return R.ok(playlistService.detail(id, pageNum, pageSize));
    }

    @ApiOperation("歌单内歌曲分页")
    @GetMapping("/{id}/songs")
    public R<PageResult<Song>> songs(@PathVariable Long id,
                                     @ApiParam("页码") @RequestParam(defaultValue = "1") Integer pageNum,
                                     @ApiParam("每页条数") @RequestParam(defaultValue = "20") Integer pageSize) {
        playlistService.require(id);
        return R.ok(songService.pageByPlaylist(id, pageNum, pageSize));
    }

    @ApiOperation("创建歌单")
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public R<Long> create(@RequestBody @Valid PlaylistSaveReq req) {
        return R.ok(playlistService.create(req));
    }

    @ApiOperation(value = "编辑歌单", notes = "仅创建者或 music:playlist:manage，越权返回 30005")
    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public R<Long> update(@PathVariable Long id, @RequestBody @Valid PlaylistSaveReq req) {
        playlistService.update(id, req);
        return R.ok(id);
    }

    @ApiOperation(value = "删除歌单", notes = "逻辑删除 + 级联物理删除歌单歌曲关联")
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public R<Long> delete(@PathVariable Long id) {
        playlistService.delete(id);
        return R.ok(id);
    }

    @ApiOperation(value = "加歌", notes = "重复歌曲幂等跳过；上限 1000 返回 30006")
    @PostMapping("/{id}/songs")
    @PreAuthorize("isAuthenticated()")
    public R<Integer> addSongs(@PathVariable Long id, @RequestBody @Valid PlaylistSongReq req) {
        return R.ok(playlistService.addSongs(id, req.getSongIds()));
    }

    @ApiOperation(value = "批量移歌", notes = "body: {\"songIds\":[...]}，关联行物理删除")
    @DeleteMapping("/{id}/songs")
    @PreAuthorize("isAuthenticated()")
    public R<Integer> removeSongs(@PathVariable Long id, @RequestBody @Valid PlaylistSongReq req) {
        return R.ok(playlistService.removeSongs(id, req.getSongIds()));
    }

    @ApiOperation(value = "歌单重排", notes = "传完整有序 songId 数组，按步长 100 整体重排")
    @PutMapping("/{id}/songs/order")
    @PreAuthorize("isAuthenticated()")
    public R<Integer> reorder(@PathVariable Long id, @RequestBody @Valid PlaylistOrderReq req) {
        playlistService.reorder(id, req.getSongIds());
        return R.ok(req.getSongIds().size());
    }
}
