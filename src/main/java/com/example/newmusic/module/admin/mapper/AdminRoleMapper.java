package com.example.newmusic.module.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.admin.entity.AdminRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AdminRoleMapper extends BaseMapper<AdminRole> {

    @Select("SELECT id FROM t_admin_role WHERE role_code = #{roleCode} AND status = 1 AND deleted = 0")
    Long selectIdByCode(@Param("roleCode") String roleCode);
}
