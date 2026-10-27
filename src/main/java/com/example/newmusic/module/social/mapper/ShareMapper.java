package com.example.newmusic.module.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.social.entity.ShareLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ShareMapper extends BaseMapper<ShareLink> {

    @Update("UPDATE t_share SET click_count = click_count + 1 WHERE code = #{code}")
    int incrementClick(@Param("code") String code);
}
