package com.example.newmusic.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.user.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /** 用户拥有的角色码。 */
    @Select("SELECT r.role_code FROM t_user_role ur "
            + "JOIN t_role r ON r.id = ur.role_id "
            + "WHERE ur.user_id = #{userId} AND r.status = 1 AND r.deleted = 0")
    List<String> selectRoleCodes(@Param("userId") Long userId);

    /** 用户拥有的权限码（经角色汇总）。 */
    @Select("SELECT DISTINCT p.perm_code FROM t_user_role ur "
            + "JOIN t_role r ON r.id = ur.role_id AND r.status = 1 AND r.deleted = 0 "
            + "JOIN t_role_permission rp ON rp.role_id = ur.role_id "
            + "JOIN t_permission p ON p.id = rp.perm_id AND p.deleted = 0 "
            + "WHERE ur.user_id = #{userId}")
    List<String> selectPermCodes(@Param("userId") Long userId);
}
