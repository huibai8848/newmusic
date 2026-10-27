package com.example.newmusic.module.contentsafety.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.contentsafety.entity.SensitiveWordVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SensitiveWordVersionMapper extends BaseMapper<SensitiveWordVersion> {

    @Select("SELECT * FROM t_sensitive_word_version ORDER BY version DESC LIMIT 1")
    SensitiveWordVersion selectLatest();
}
