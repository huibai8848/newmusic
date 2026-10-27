package com.example.newmusic.module.contentsafety.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.contentsafety.entity.MinorProtection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MinorProtectionMapper extends BaseMapper<MinorProtection> {

    @Select("SELECT COUNT(*) FROM t_minor_protection WHERE guardian_user_id = #{guardianId} AND is_minor = 1")
    long countByGuardian(Long guardianId);
}
