package com.example.newmusic.module.ops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.ops.entity.RateLimitRule;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RateLimitRuleMapper extends BaseMapper<RateLimitRule> {
}
