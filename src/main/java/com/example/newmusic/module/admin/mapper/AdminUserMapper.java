package com.example.newmusic.module.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.admin.entity.AdminUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AdminUserMapper extends BaseMapper<AdminUser> {

    @Select("SELECT r.role_code FROM t_admin_user_role ur "
            + "JOIN t_admin_role r ON r.id = ur.role_id AND r.status = 1 AND r.deleted = 0 "
            + "WHERE ur.user_id = #{adminId}")
    List<String> selectRoleCodes(@Param("adminId") Long adminId);

    @Select("SELECT DISTINCT p.perm_code FROM t_admin_user_role ur "
            + "JOIN t_admin_role r ON r.id = ur.role_id AND r.status = 1 AND r.deleted = 0 "
            + "JOIN t_admin_role_permission rp ON rp.role_id = ur.role_id "
            + "JOIN t_admin_permission p ON p.id = rp.perm_id AND p.status = 1 AND p.deleted = 0 "
            + "WHERE ur.user_id = #{adminId}")
    List<String> selectPermCodes(@Param("adminId") Long adminId);

    /** 拥有全量通配（超管）且账号正常的用户，用于敏感操作通知。 */
    @Select("SELECT DISTINCT u.id FROM t_admin_user u "
            + "JOIN t_admin_user_role ur ON ur.user_id = u.id "
            + "JOIN t_admin_role r ON r.id = ur.role_id AND r.role_code = 'SUPER_ADMIN' "
            + "WHERE u.status = 1 AND u.deleted = 0 AND u.id <> #{excludeId}")
    List<Long> selectSuperAdminIds(@Param("excludeId") Long excludeId);

    /** 最后一个可用超管之外，是否还有其它可用超管。 */
    @Select("SELECT COUNT(DISTINCT u.id) FROM t_admin_user u "
            + "JOIN t_admin_user_role ur ON ur.user_id = u.id "
            + "JOIN t_admin_role r ON r.id = ur.role_id AND r.role_code = 'SUPER_ADMIN' "
            + "WHERE u.status = 1 AND u.deleted = 0 AND u.id <> #{adminId}")
    long countOtherActiveSuperAdmins(@Param("adminId") Long adminId);
}
