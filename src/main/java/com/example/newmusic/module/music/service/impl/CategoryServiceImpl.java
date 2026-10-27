package com.example.newmusic.module.music.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.query.PageQuery;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.common.service.BaseCrudService;
import com.example.newmusic.common.util.LikeUtil;
import com.example.newmusic.module.music.dto.CategorySaveReq;
import com.example.newmusic.module.music.entity.Category;
import com.example.newmusic.module.music.mapper.CategoryMapper;
import com.example.newmusic.module.music.service.CategoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class CategoryServiceImpl extends BaseCrudService<CategoryMapper, Category> implements CategoryService {

    /** 排序白名单（《01-数据库CRUD》D-09） */
    private static final Set<String> SORT_FIELDS =
            new HashSet<>(Arrays.asList("id", "sortOrder", "createTime", "name"));

    @Override
    public PageResult<Category> page(PageQuery query, Long parentId, Integer status) {
        QueryWrapper<Category> wrapper = Wrappers.query();
        wrapper.eq(parentId != null, "parent_id", parentId);
        wrapper.eq(status != null, "status", status);
        if (query.hasKeyword()) {
            wrapper.like("name", LikeUtil.escape(query.getKeyword()));
        }
        if (StrUtil.isBlank(query.getSortBy())) {
            // 分类默认按运营配置的 sort_order 升序，而非 create_time desc
            query.normalize();
            wrapper.orderByAsc("sort_order").orderByAsc("id");
            return PageResult.of(this.page(query.toPage(), wrapper), query.isTruncated());
        }
        return PageResult.of(this.pageQuery(query, wrapper, SORT_FIELDS), query.isTruncated());
    }

    @Override
    public List<Category> listEnabled() {
        return list(Wrappers.<Category>query().eq("status", 1).orderByAsc("sort_order").orderByAsc("id"));
    }

    @Override
    public Category detail(Long id) {
        Category entity = getById(requireId(id));
        if (entity == null) {
            throw new BizException(ResultCode.CATEGORY_NOT_FOUND);
        }
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(CategorySaveReq req) {
        Long dup = count(Wrappers.<Category>query().eq("code", req.getCode()));
        if (dup != null && dup > 0) {
            throw new BizException(ResultCode.DUPLICATE_DATA, "分类编码已存在：" + req.getCode());
        }
        Category entity = new Category();
        BeanUtils.copyProperties(req, entity);
        entity.setId(null);
        if (entity.getParentId() == null) {
            entity.setParentId(0L);
        }
        if (entity.getStatus() == null) {
            entity.setStatus(1);
        }
        if (entity.getSortOrder() == null) {
            entity.setSortOrder(0);
        }
        save(entity);
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, CategorySaveReq req) {
        requireVersion(req.getVersion());
        Category exists = detail(id);
        if (StrUtil.isNotBlank(req.getCode())) {
            Long dup = count(Wrappers.<Category>query()
                    .eq("code", req.getCode())
                    .ne("id", id));
            if (dup != null && dup > 0) {
                throw new BizException(ResultCode.DUPLICATE_DATA, "分类编码已存在：" + req.getCode());
            }
        }
        if (id.equals(req.getParentId())) {
            throw new BizException(ResultCode.PARAM_INVALID, "父分类不能是自己");
        }
        Category entity = new Category();
        BeanUtils.copyProperties(req, entity);
        entity.setId(exists.getId());
        entity.setVersion(req.getVersion());
        updateWithVersion(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Category exists = detail(id);
        Long children = count(Wrappers.<Category>query().eq("parent_id", exists.getId()));
        if (children != null && children > 0) {
            throw new BizException(ResultCode.CATEGORY_HAS_CHILDREN);
        }
        removeSafe(exists.getId());
    }
}
