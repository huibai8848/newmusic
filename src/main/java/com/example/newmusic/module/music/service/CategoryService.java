package com.example.newmusic.module.music.service;

import com.example.newmusic.common.query.PageQuery;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.module.music.dto.CategorySaveReq;
import com.example.newmusic.module.music.entity.Category;

import java.util.List;

public interface CategoryService {

    PageResult<Category> page(PageQuery query, Long parentId, Integer status);

    /** 全量启用分类（后台下拉用，不分页；数据量 < 200 时可用）。 */
    List<Category> listEnabled();

    Category detail(Long id);

    Long create(CategorySaveReq req);

    void update(Long id, CategorySaveReq req);

    void delete(Long id);
}
