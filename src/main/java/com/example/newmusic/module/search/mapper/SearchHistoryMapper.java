package com.example.newmusic.module.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.search.entity.SearchHistory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 搜索历史的落库写入（BR-15-006 的「同时落库用于分析」）。
 *
 * <p>只有写没有读：读路径走 Redis（§6.3）。所以这里连查询方法都不提供——
 * 提供一个没人用的 {@code selectList} 只会诱导后来者从库里读历史，
 * 而那会恢复出跨设备的、更早的、用户已经删过的条目（与 BR-15-024 冲突）。
 */
@Mapper
public interface SearchHistoryMapper extends BaseMapper<SearchHistory> {
}
