package com.example.newmusic.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.user.entity.Role;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RoleMapper extends BaseMapper<Role> {

    @Select("SELECT id FROM t_role WHERE role_code = #{roleCode} AND deleted = 0")
    Long selectIdByCode(@Param("roleCode") String roleCode);
}
