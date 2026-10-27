package com.example.newmusic.module.music.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.query.PageQuery;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.common.service.BaseCrudService;
import com.example.newmusic.common.util.LikeUtil;
import com.example.newmusic.module.music.dto.TagSaveReq;
import com.example.newmusic.module.music.entity.SongTag;
import com.example.newmusic.module.music.entity.Tag;
import com.example.newmusic.module.music.mapper.SongTagMapper;
import com.example.newmusic.module.music.mapper.TagMapper;
import com.example.newmusic.module.music.service.TagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TagServiceImpl extends BaseCrudService<TagMapper, Tag> implements TagService {

    /** 单曲标签数量上限（模块 01 接口约束，防止标签滥用污染筛选） */
    public static final int MAX_TAGS_PER_SONG = 10;

    private static final Set<String> SORT_FIELDS =
            new HashSet<>(Arrays.asList("id", "useCount", "isHot", "createTime", "name"));

    private final SongTagMapper songTagMapper;

    public TagServiceImpl(SongTagMapper songTagMapper) {
        this.songTagMapper = songTagMapper;
    }

    @Override
    public PageResult<Tag> page(PageQuery query, Integer tagType, Integer isHot, Integer status) {
        QueryWrapper<Tag> wrapper = Wrappers.query();
        wrapper.eq(tagType != null, "tag_type", tagType);
        wrapper.eq(isHot != null, "is_hot", isHot);
        wrapper.eq(status != null, "status", status);
        if (query.hasKeyword()) {
            wrapper.like("name", LikeUtil.escape(query.getKeyword()));
        }
        if (StrUtil.isBlank(query.getSortBy())) {
            query.normalize();
            wrapper.orderByDesc("is_hot").orderByDesc("use_count").orderByAsc("id");
            return PageResult.of(this.page(query.toPage(), wrapper), query.isTruncated());
        }
        return PageResult.of(this.pageQuery(query, wrapper, SORT_FIELDS), query.isTruncated());
    }

    @Override
    public List<Tag> listHot(int limit) {
        int size = limit <= 0 ? 20 : Math.min(limit, PageQuery.MAX_PAGE_SIZE);
        return list(Wrappers.<Tag>query()
                .eq("status", 1)
                .orderByDesc("is_hot")
                .orderByDesc("use_count")
                .last("LIMIT " + size));
    }

    @Override
    public List<Tag> listBySongId(Long songId) {
        List<SongTag> links = songTagMapper.selectList(Wrappers.<SongTag>query().eq("song_id", songId));
        if (CollUtil.isEmpty(links)) {
            return Collections.emptyList();
        }
        Set<Long> tagIds = links.stream().map(SongTag::getTagId).collect(Collectors.toCollection(LinkedHashSet::new));
        List<Tag> tags = listByIds(tagIds);
        // 保持传入顺序稳定（listByIds 不保证顺序）
        Map<Long, Tag> map = tags.stream().collect(Collectors.toMap(Tag::getId, Function.identity(), (a, b) -> a));
        List<Tag> ordered = new ArrayList<>(tagIds.size());
        for (Long tagId : tagIds) {
            Tag tag = map.get(tagId);
            if (tag != null) {
                ordered.add(tag);
            }
        }
        return ordered;
    }

    @Override
    public Tag detail(Long id) {
        Tag entity = getById(requireId(id));
        if (entity == null) {
            throw new BizException(ResultCode.TAG_NOT_FOUND);
        }
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(TagSaveReq req) {
        Long dup = count(Wrappers.<Tag>query().eq("name", req.getName()));
        if (dup != null && dup > 0) {
            throw new BizException(ResultCode.DUPLICATE_DATA, "标签名已存在：" + req.getName());
        }
        Tag entity = new Tag();
        BeanUtils.copyProperties(req, entity);
        entity.setId(null);
        entity.setTagType(entity.getTagType() == null ? 6 : entity.getTagType());
        entity.setIsHot(entity.getIsHot() == null ? 0 : entity.getIsHot());
        entity.setStatus(entity.getStatus() == null ? 1 : entity.getStatus());
        entity.setUseCount(0L);
        save(entity);
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, TagSaveReq req) {
        requireVersion(req.getVersion());
        Tag exists = detail(id);
        if (StrUtil.isNotBlank(req.getName()) && !exists.getName().equals(req.getName())) {
            Long dup = count(Wrappers.<Tag>query().eq("name", req.getName()).ne("id", id));
            if (dup != null && dup > 0) {
                throw new BizException(ResultCode.DUPLICATE_DATA, "标签名已存在：" + req.getName());
            }
        }
        Tag entity = new Tag();
        BeanUtils.copyProperties(req, entity);
        entity.setId(exists.getId());
        entity.setVersion(req.getVersion());
        updateWithVersion(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Tag exists = detail(id);
        removeSafe(exists.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindSongTags(Long songId, List<Long> tagIds) {
        Set<Long> target = tagIds == null ? new LinkedHashSet<>()
                : tagIds.stream().filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (target.size() > MAX_TAGS_PER_SONG) {
            throw new BizException(ResultCode.PARAM_INVALID, "单曲最多绑定 " + MAX_TAGS_PER_SONG + " 个标签");
        }
        // 目标标签必须存在（避免脏关联）
        if (!target.isEmpty()) {
            List<Tag> tags = listByIds(target);
            if (tags.size() != target.size()) {
                throw new BizException(ResultCode.TAG_NOT_FOUND);
            }
        }

        List<SongTag> current = songTagMapper.selectList(Wrappers.<SongTag>query().eq("song_id", songId));
        Set<Long> currentIds = current.stream().map(SongTag::getTagId).collect(Collectors.toSet());

        // 新增
        for (Long tagId : target) {
            if (currentIds.contains(tagId)) {
                continue;
            }
            SongTag link = new SongTag();
            link.setSongId(songId);
            link.setTagId(tagId);
            songTagMapper.insert(link);
            baseMapper.updateUseCount(tagId, 1);
        }
        // 移除（关联表物理删除，见 D-03）
        for (SongTag link : current) {
            if (target.contains(link.getTagId())) {
                continue;
            }
            songTagMapper.deleteById(link.getId());
            baseMapper.updateUseCount(link.getTagId(), -1);
        }
    }

    @Override
    public void changeUseCount(Long tagId, int delta) {
        if (tagId == null || delta == 0) {
            return;
        }
        baseMapper.updateUseCount(tagId, delta);
    }
}
