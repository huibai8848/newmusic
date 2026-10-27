package com.example.newmusic.module.contentsafety.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.contentsafety.entity.SensitiveWord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SensitiveWordMapper extends BaseMapper<SensitiveWord> {

    /** 命中计数异步累加，避免每次审核都写主表行造成锁竞争。 */
    @Update("UPDATE t_sensitive_word SET hit_count = hit_count + #{delta}, last_hit_time = NOW(3) "
            + "WHERE id = #{id}")
    int incrementHit(@Param("id") Long id, @Param("delta") long delta);

    @Select("SELECT * FROM t_sensitive_word WHERE status = 1 ORDER BY level DESC, id ASC")
    List<SensitiveWord> selectEnabled();

    @Select("SELECT * FROM t_sensitive_word WHERE status = 1 ORDER BY hit_count DESC, id ASC LIMIT #{limit}")
    List<SensitiveWord> selectTopHits(@Param("limit") int limit);
}
