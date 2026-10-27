package com.example.newmusic.module.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.newmusic.common.context.LoginUser;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.query.PageQuery;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.common.security.PermissionCatalogue;
import com.example.newmusic.module.admin.dto.AdminAccountSaveReq;
import com.example.newmusic.module.admin.entity.AdminPermission;
import com.example.newmusic.module.admin.entity.AdminRole;
import com.example.newmusic.module.admin.entity.AdminUser;
import com.example.newmusic.module.admin.entity.AdminUserRole;
import com.example.newmusic.module.admin.mapper.AdminPermissionMapper;
import com.example.newmusic.module.admin.mapper.AdminRoleMapper;
import com.example.newmusic.module.admin.mapper.AdminUserMapper;
import com.example.newmusic.module.admin.mapper.AdminUserRoleMapper;
import com.example.newmusic.module.admin.security.AdminSessionStore;
import com.example.newmusic.module.admin.support.TotpSupport;
import com.example.newmusic.module.admin.vo.AdminUserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 后台账号管理（仅 SUPER_ADMIN）与登录上下文组装。
 *
 * <p>三条硬保护：最后一个超管不可禁用/降级（80017）、不能禁用自己（80018）、
 * 登录名唯一（80019）。所有写操作由 Controller 上的 {@code @AdminOpLog} 留痕。
 */
@Slf4j
@Service
public class AdminAccountService {

    private static final Pattern PASSWORD_LETTER = Pattern.compile(".*[A-Za-z].*");
    private static final Pattern PASSWORD_DIGIT = Pattern.compile(".*\\d.*");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CHAR_POOL =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private final AdminUserMapper adminUserMapper;
    private final AdminUserRoleMapper userRoleMapper;
    private final AdminRoleMapper roleMapper;
    private final AdminPermissionMapper permissionMapper;
    private final PasswordEncoder passwordEncoder;
    private final AdminSessionStore sessionStore;
    private final TotpSupport totpSupport;

    public AdminAccountService(AdminUserMapper adminUserMapper,
                               AdminUserRoleMapper userRoleMapper,
                               AdminRoleMapper roleMapper,
                               AdminPermissionMapper permissionMapper,
                               PasswordEncoder passwordEncoder,
                               AdminSessionStore sessionStore,
                               TotpSupport totpSupport) {
        this.adminUserMapper = adminUserMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.passwordEncoder = passwordEncoder;
        this.sessionStore = sessionStore;
        this.totpSupport = totpSupport;
    }

    public AdminUser getByUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        return adminUserMapper.selectOne(new QueryWrapper<AdminUser>()
                .eq("username", username.trim()).last("LIMIT 1"));
    }

    public AdminUser getById(Long id) {
        return id == null ? null : adminUserMapper.selectById(id);
    }

    public AdminUser requireById(Long id) {
        AdminUser user = getById(id);
        if (user == null) {
            throw new BizException(ResultCode.ACCOUNT_NOT_FOUND, "管理员不存在");
        }
        return user;
    }

    /** 组装后台登录上下文；超级管理员的 {@code *:*:*} 会展开为全部具体权限码。 */
    public LoginUser buildLoginUser(AdminUser user) {
        Set<String> roles = new LinkedHashSet<>(nullSafe(adminUserMapper.selectRoleCodes(user.getId())));
        Set<String> perms = new LinkedHashSet<>(nullSafe(adminUserMapper.selectPermCodes(user.getId())));
        if (perms.contains(PermissionCatalogue.ALL)) {
            List<AdminPermission> all = permissionMapper.selectList(
                    new QueryWrapper<AdminPermission>().eq("status", 1));
            for (AdminPermission permission : all) {
                if (!PermissionCatalogue.ALL.equals(permission.getPermCode())) {
                    perms.add(permission.getPermCode());
                }
            }
        }
        return LoginUser.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(StringUtils.hasText(user.getRealName()) ? user.getRealName() : user.getUsername())
                .userType(roles.contains(AdminRole.SUPER_ADMIN) ? 4 : 3)
                .roles(roles)
                .perms(perms)
                .build();
    }

    public AdminUserVO toVO(AdminUser user, boolean withAuth) {
        AdminUserVO vo = AdminUserVO.from(user);
        if (vo != null && withAuth) {
            vo.setRoles(new LinkedHashSet<>(nullSafe(adminUserMapper.selectRoleCodes(user.getId()))));
            vo.setPerms(new LinkedHashSet<>(nullSafe(adminUserMapper.selectPermCodes(user.getId()))));
        }
        return vo;
    }

    public List<AdminRole> roles() {
        return roleMapper.selectList(new QueryWrapper<AdminRole>()
                .eq("status", 1).orderByAsc("id"));
    }

    public PageResult<AdminUserVO> page(PageQuery query, Integer status) {
        query.normalize();
        QueryWrapper<AdminUser> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.and(q -> q.like("username", query.getKeyword())
                    .or().like("real_name", query.getKeyword())
                    .or().like("email", query.getKeyword()));
        }
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("create_time");
        Page<AdminUser> page = adminUserMapper.selectPage(query.toPage(), wrapper);
        return PageResult.of(page, u -> toVO(u, true), query.isTruncated());
    }

    @Transactional
    public Long create(AdminAccountSaveReq req, Long operatorId) {
        if (getByUsername(req.getUsername()) != null) {
            throw new BizException(ResultCode.ADMIN_USERNAME_EXISTS);
        }
        validatePassword(req.getPassword(), true);
        requireRole(req.getRoleCode());

        AdminUser user = new AdminUser();
        user.setUsername(req.getUsername().trim());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setRealName(req.getRealName());
        user.setEmail(req.getEmail());
        user.setPhone(req.getPhone());
        user.setStatus(1);
        // TOTP 密钥只能通过 reset-totp 生成并回传。创建时直接开启会得到
        // 「已开启 TOTP 但没有密钥」的死锁账号，因此这里一律先关闭。
        user.setTotpEnabled(0);
        user.setForceChangePassword(1);
        user.setPasswordUpdateTime(LocalDateTime.now());
        user.setFailCount(0);
        user.setAllowIpList(req.getAllowIpList());
        user.setRemark(req.getRemark());
        user.setCreateBy(operatorId);
        user.setUpdateBy(operatorId);
        adminUserMapper.insert(user);
        grantRole(user.getId(), req.getRoleCode());
        log.info("后台管理员创建成功 adminId={} username={} by={}",
                user.getId(), user.getUsername(), operatorId);
        return user.getId();
    }

    @Transactional
    public void update(Long id, AdminAccountSaveReq req, Long operatorId) {
        AdminUser user = requireById(id);
        if (!user.getUsername().equals(req.getUsername())
                && getByUsername(req.getUsername()) != null) {
            throw new BizException(ResultCode.ADMIN_USERNAME_EXISTS);
        }
        if (StringUtils.hasText(req.getPassword())) {
            validatePassword(req.getPassword(), true);
            user.setPassword(passwordEncoder.encode(req.getPassword()));
            user.setPasswordUpdateTime(LocalDateTime.now());
            user.setForceChangePassword(1);
        }
        user.setUsername(req.getUsername().trim());
        user.setRealName(req.getRealName());
        user.setEmail(req.getEmail());
        // 编辑接口不回传完整手机号（只回传脱敏值），null 表示「未提交」而非「清空」。
        if (req.getPhone() != null) {
            user.setPhone(req.getPhone());
        }
        user.setAllowIpList(req.getAllowIpList());
        user.setRemark(req.getRemark());
        user.setUpdateBy(operatorId);

        boolean enablingTotp = Boolean.TRUE.equals(req.getTotpEnabled());
        if (enablingTotp && !StringUtils.hasText(user.getTotpSecret())) {
            throw new BizException(ResultCode.PARAM_INVALID,
                    "该账号还没有 TOTP 密钥，请先使用「重置 TOTP」生成并绑定");
        }
        user.setTotpEnabled(enablingTotp ? 1 : 0);
        // updateById 默认忽略 null 字段，关闭 TOTP 时必须显式把密钥置空
        if (!enablingTotp) {
            user.setTotpSecret(null);
        }
        adminUserMapper.updateById(user);
        if (!enablingTotp) {
            adminUserMapper.update(null, new UpdateWrapper<AdminUser>()
                    .eq("id", id).set("totp_secret", null));
        }

        if (StringUtils.hasText(req.getRoleCode())) {
            requireRole(req.getRoleCode());
            boolean wasSuper = isSuperAdmin(id);
            if (wasSuper && !AdminRole.SUPER_ADMIN.equals(req.getRoleCode())
                    && adminUserMapper.countOtherActiveSuperAdmins(id) == 0) {
                throw new BizException(ResultCode.ADMIN_LAST_SUPER);
            }
            replaceRole(id, req.getRoleCode());
        }
        if (StringUtils.hasText(req.getPassword())
                || (req.getRoleCode() != null && !req.getRoleCode().isEmpty())) {
            sessionStore.bumpVersion(id);
        }
    }

    @Transactional
    public void changeStatus(Long id, Integer status, Long operatorId) {
        AdminUser user = requireById(id);
        if (id.equals(operatorId)) {
            throw new BizException(ResultCode.ADMIN_SELF_DISABLE);
        }
        if (status != null && status == AdminUser.STATUS_DISABLED
                && isSuperAdmin(id) && adminUserMapper.countOtherActiveSuperAdmins(id) == 0) {
            throw new BizException(ResultCode.ADMIN_LAST_SUPER);
        }
        user.setStatus(status);
        user.setUpdateBy(operatorId);
        adminUserMapper.updateById(user);
        if (status != null && status == AdminUser.STATUS_DISABLED) {
            sessionStore.bumpVersion(id);
        }
        log.info("后台管理员状态变更 adminId={} status={} by={}", id, status, operatorId);
    }

    /** 重置密码：返回一次性明文，强制下次登录修改。 */
    @Transactional
    public String resetPassword(Long id, Long operatorId) {
        AdminUser user = requireById(id);
        String password = randomPassword();
        user.setPassword(passwordEncoder.encode(password));
        user.setPasswordUpdateTime(LocalDateTime.now());
        user.setForceChangePassword(1);
        user.setFailCount(0);
        user.setLockUntil(null);
        user.setUpdateBy(operatorId);
        adminUserMapper.updateById(user);
        sessionStore.bumpVersion(id);
        return password;
    }

    /** 重置 TOTP：返回新密钥（Base32）。 */
    @Transactional
    public String resetTotp(Long id, Long operatorId) {
        AdminUser user = requireById(id);
        String secret = totpSupport.generateSecret();
        user.setTotpSecret(secret);
        user.setTotpEnabled(1);
        user.setUpdateBy(operatorId);
        adminUserMapper.updateById(user);
        sessionStore.bumpVersion(id);
        return secret;
    }

    public boolean isSuperAdmin(Long adminId) {
        return nullSafe(adminUserMapper.selectRoleCodes(adminId)).contains(AdminRole.SUPER_ADMIN);
    }

    // ------------------------------------------------------------------ helpers

    private void grantRole(Long adminId, String roleCode) {
        Long roleId = roleMapper.selectIdByCode(roleCode);
        if (roleId == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "角色不存在：" + roleCode);
        }
        AdminUserRole link = new AdminUserRole();
        link.setUserId(adminId);
        link.setRoleId(roleId);
        userRoleMapper.insert(link);
    }

    private void replaceRole(Long adminId, String roleCode) {
        userRoleMapper.delete(new QueryWrapper<AdminUserRole>().eq("user_id", adminId));
        grantRole(adminId, roleCode);
    }

    private void requireRole(String roleCode) {
        if (roleMapper.selectIdByCode(roleCode) == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "角色不存在：" + roleCode);
        }
    }

    public void validatePassword(String password, boolean required) {
        if (!StringUtils.hasText(password)) {
            if (required) {
                throw new BizException(ResultCode.ADMIN_PASSWORD_WEAK);
            }
            return;
        }
        if (password.length() < 8 || password.length() > 64
                || !PASSWORD_LETTER.matcher(password).matches()
                || !PASSWORD_DIGIT.matcher(password).matches()
                || password.equalsIgnoreCase("password")
                || password.startsWith("123456")) {
            throw new BizException(ResultCode.ADMIN_PASSWORD_WEAK);
        }
    }

    public String randomPassword() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(CHAR_POOL.charAt(RANDOM.nextInt(CHAR_POOL.length())));
        }
        return sb.toString();
    }

    private List<String> nullSafe(List<String> list) {
        return list == null ? Collections.<String>emptyList() : list;
    }
}
