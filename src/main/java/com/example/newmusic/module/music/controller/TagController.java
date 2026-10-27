package com.example.newmusic.module.music.controller;

import com.example.newmusic.common.query.PageQuery;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.music.dto.SongTagReq;
import com.example.newmusic.module.music.dto.TagSaveReq;
import com.example.newmusic.module.music.entity.Tag;
import com.example.newmusic.module.music.service.TagService;
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
import java.util.List;

@Api(tags = "曲库-标签")
@RestController
@RequestMapping("/api/v1/music/tags")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @ApiOperation("标签列表")
    @GetMapping
    public R<PageResult<Tag>> list(PageQuery query,
                                   @ApiParam("1风格 2场景 3情绪 4年代 5语种 6自定义")
                                   @RequestParam(required = false) Integer tagType,
                                   @ApiParam("是否热门") @RequestParam(required = false) Integer isHot,
                                   @ApiParam("1启用 0禁用") @RequestParam(required = false) Integer status) {
        return R.ok(tagService.page(query, tagType, isHot, status));
    }

    @ApiOperation(value = "热门标签", notes = "首页/搜索建议使用")
    @GetMapping("/hot")
    public R<List<Tag>> hot(@RequestParam(defaultValue = "20") Integer limit) {
        return R.ok(tagService.listHot(limit == null ? 20 : limit));
    }

    @ApiOperation("歌曲已绑定标签")
    @GetMapping("/song/{songId}")
    public R<List<Tag>> listBySong(@PathVariable Long songId) {
        return R.ok(tagService.listBySongId(songId));
    }

    @ApiOperation("新增标签")
    @PostMapping
    @PreAuthorize("hasAuthority('music:tag:create')")
    public R<Long> create(@RequestBody @Valid TagSaveReq req) {
        return R.ok(tagService.create(req));
    }

    @ApiOperation("编辑标签")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('music:tag:update')")
    public R<Long> update(@PathVariable Long id, @RequestBody @Valid TagSaveReq req) {
        tagService.update(id, req);
        return R.ok(id);
    }

    @ApiOperation("删除标签")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('music:tag:delete')")
    public R<Long> delete(@PathVariable Long id) {
        tagService.delete(id);
        return R.ok(id);
    }

    @ApiOperation(value = "歌曲打标签（全量覆盖）", notes = "单曲最多 10 个；同步维护 tag.use_count")
    @PutMapping("/song")
    @PreAuthorize("hasAuthority('music:tag:update')")
    public R<Long> bindSongTags(@RequestBody @Valid SongTagReq req) {
        tagService.bindSongTags(req.getSongId(), req.getTagIds());
        return R.ok(req.getSongId());
    }
}
