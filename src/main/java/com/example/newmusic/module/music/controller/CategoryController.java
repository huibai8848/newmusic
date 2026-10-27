package com.example.newmusic.module.music.controller;

import com.example.newmusic.common.query.PageQuery;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.music.dto.CategorySaveReq;
import com.example.newmusic.module.music.entity.Category;
import com.example.newmusic.module.music.service.CategoryService;
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

@Api(tags = "曲库-分类")
@RestController
@RequestMapping("/api/v1/music/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @ApiOperation("分类列表")
    @GetMapping
    public R<PageResult<Category>> list(PageQuery query,
                                        @ApiParam("父分类ID") @RequestParam(required = false) Long parentId,
                                        @ApiParam("1启用 0禁用") @RequestParam(required = false) Integer status) {
        return R.ok(categoryService.page(query, parentId, status));
    }

    @ApiOperation(value = "启用分类全集", notes = "供前台导航下拉使用，不分页")
    @GetMapping("/enabled")
    public R<List<Category>> listEnabled() {
        return R.ok(categoryService.listEnabled());
    }

    @ApiOperation("新增分类")
    @PostMapping
    @PreAuthorize("hasAuthority('music:category:create')")
    public R<Long> create(@RequestBody @Valid CategorySaveReq req) {
        return R.ok(categoryService.create(req));
    }

    @ApiOperation("编辑分类")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('music:category:update')")
    public R<Long> update(@PathVariable Long id, @RequestBody @Valid CategorySaveReq req) {
        categoryService.update(id, req);
        return R.ok(id);
    }

    @ApiOperation(value = "删除分类", notes = "存在子分类返回 30046")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('music:category:delete')")
    public R<Long> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return R.ok(id);
    }
}
