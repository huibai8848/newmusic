package com.example.newmusic.module.contentsafety.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.contentsafety.entity.AuditRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AuditRuleMapper extends BaseMapper<AuditRule> {

    @Select("SELECT * FROM t_audit_rule WHERE status = 1 ORDER BY priority ASC, id ASC")
    List<AuditRule> selectEnabled();
}
