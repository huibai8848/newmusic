package com.example.newmusic.module.search.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 读 {@code t_sys_config} 的只读 Mapper。
 *
 * <p>为什么搜索模块要自己读系统配置表：敏感词词典（BR-15-002）按 PRD 的存在形式
 * 就是「运营在配置表里维护的一行」。模块 09 会提供完整的配置管理接口，
 * 但那是**写**侧；本模块只需要按 key 取一个值，为它去依赖一个尚未存在的模块
 * 是把顺序搞反了。
 *
 * <p>与 D-44 同一条理由：这是一次**只读跨域投影**，不引用任何领域的 Service，
 * 只读不写，依赖方向干净，故在搜索模块内自建。
 */
@Mapper
public interface SearchConfigMapper {

    /**
     * 取值。只在 {@code status=1}（启用）时返回——停用的配置行等同于不存在，
     * 这样运营可以「停用」而不是「删掉」来临时关闭一个开关。
     */
    @Select("SELECT config_value FROM t_sys_config WHERE config_key = #{key} AND status = 1 LIMIT 1")
    String selectValue(@Param("key") String key);
}
