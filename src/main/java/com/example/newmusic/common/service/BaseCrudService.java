package com.example.newmusic.common.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.newmusic.common.entity.BaseEntity;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.query.PageQuery;
import com.example.newmusic.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Set;

/**
 * 通用 CRUD Service 基类（《10-模块01-数据库CRUD》4.4）。
 *
 * <p>统一提供：排序白名单、逻辑删除、批量上限保护、乐观锁冲突转换。
 */
@Slf4j
public abstract class BaseCrudService<M extends BaseMapper<T>, T extends BaseEntity>
        extends ServiceImpl<M, T> {

    /** 排序白名单外的回退排序字段 */
    protected static final String DEFAULT_SORT_COLUMN = "create_time";

    /** 单次批量操作上限（BR-10-028） */
    public static final int MAX_BATCH_SIZE = 500;

    /**
     * 应用排序：字段必须命中接口级白名单，否则回退默认排序且不报错（BR-10-002 / EX-10-001-3）。
     */
    protected void applySort(QueryWrapper<T> wrapper, PageQuery query, Set<String> allowedSortFields) {
        query.normalize();
        String field = query.getSortBy();
        if (StrUtil.isBlank(field) || allowedSortFields == null || !allowedSortFields.contains(field)) {
            if (StrUtil.isNotBlank(field)) {
                log.warn("sortBy 不在白名单内，回退默认排序 sortBy={} allowed={}", field, allowedSortFields);
            }
            wrapper.orderByDesc(DEFAULT_SORT_COLUMN);
            return;
        }
        wrapper.orderBy(true, query.isAsc(), StrUtil.toUnderlineCase(field));
    }

    /** 分页查询（PageQuery 已在 applySort 中归一化）。 */
    protected Page<T> pageQuery(PageQuery query, QueryWrapper<T> wrapper, Set<String> allowedSortFields) {
        applySort(wrapper, query, allowedSortFields);
        return this.page(query.toPage(), wrapper);
    }

    /**
     * 逻辑删除单条（BR-10-022）；删除后触发 {@link #afterRemove} 清理关联资源。
     */
    public void removeSafe(Long id) {
        T entity = getById(id);
        if (entity == null) {
            // EX-10-005-4：重复删除幂等返回成功
            return;
        }
        removeById(id);
        afterRemove(java.util.Collections.singletonList(id));
    }

    /** 批量逻辑删除，上限 500（BR-10-028）。 */
    public void batchRemoveSafe(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        if (ids.size() > MAX_BATCH_SIZE) {
            throw new BizException(ResultCode.BATCH_SIZE_EXCEEDED);
        }
        removeByIds(ids);
        afterRemove(ids);
    }

    /**
     * 更新并校验乐观锁（BR-10-018）。MP 乐观锁插件在 version 不匹配时返回 0 行。
     */
    public void updateWithVersion(T entity) {
        if (!updateById(entity)) {
            throw new BizException(ResultCode.CONCURRENT_MODIFY_CONFLICT);
        }
    }

    /**
     * 删除后的关联清理钩子（默认空实现）。
     *
     * <p>子类可覆写以级联删除关联表、回退冗余计数。
     * Redis 缓存清理（模块 03）与 ES 文档删除（模块 06）也在该钩子内补齐。
     */
    protected void afterRemove(Collection<Long> ids) {
        // no-op by default
    }

    /**
     * 更新前校验乐观锁版本号。
     *
     * <p>约定（《01-数据库CRUD》D-02）：PUT 请求体必须携带详情接口返回的 {@code version}，
     * 缺失即视为客户端未刷新数据，直接拒绝以保护并发写。
     */
    protected static void requireVersion(Integer version) {
        if (version == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "缺少 version，请先查询详情获取最新版本号");
        }
    }

    /** 主键非空校验的通用提示。 */
    protected static Long requireId(Long id) {
        if (id == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "ID 不能为空");
        }
        return id;
    }
}
