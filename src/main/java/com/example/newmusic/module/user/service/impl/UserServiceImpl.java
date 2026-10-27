package com.example.newmusic.module.user.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.newmusic.common.context.LoginUser;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.common.security.PermissionCatalogue;
import com.example.newmusic.infra.security.TokenSessionStore;
import com.example.newmusic.module.user.dto.ChangePasswordReq;
import com.example.newmusic.module.user.dto.RegisterReq;
import com.example.newmusic.module.user.dto.UserUpdateReq;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.user.entity.UserRole;
import com.example.newmusic.module.user.mapper.RoleMapper;
import com.example.newmusic.module.user.mapper.UserMapper;
import com.example.newmusic.module.user.mapper.UserRoleMapper;
import com.example.newmusic.module.user.service.UserService;
import com.example.newmusic.module.user.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    /** 注册后默认授予的角色 */
    private static final String DEFAULT_ROLE = "ROLE_USER";

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenSessionStore tokenSessionStore;

    public UserServiceImpl(UserMapper userMapper, RoleMapper roleMapper,
                           UserRoleMapper userRoleMapper, PasswordEncoder passwordEncoder,
                           TokenSessionStore tokenSessionStore) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenSessionStore = tokenSessionStore;
    }

    @Override
    public User getByIdOrThrow(Long userId) {
        User user = getById(userId);
        if (user == null) {
            throw new BizException(ResultCode.ACCOUNT_NOT_FOUND);
        }
        return user;
    }

    @Override
    public User getByUsername(String username) {
        if (StrUtil.isBlank(username)) {
            return null;
        }
        return getOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username), false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO register(RegisterReq req, String clientIp) {
        if (getByUsername(req.getUsername()) != null) {
            throw new BizException(ResultCode.DUPLICATE_DATA, "用户名已被占用");
        }
        long nicknameCount = count(Wrappers.<User>lambdaQuery().eq(User::getNickname, req.getNickname()));
        if (nicknameCount > 0) {
            throw new BizException(ResultCode.NICKNAME_EXISTS);
        }

        User user = new User();
        user.setUsername(req.getUsername());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setNickname(req.getNickname());
        user.setUserType(1);
        user.setStatus(1);
        user.setGender(0);
        user.setVipLevel(0);
        user.setRegisterIp(clientIp);
        save(user);

        grantRole(user.getId(), DEFAULT_ROLE);
        log.info("用户注册成功 userId={} username={}", user.getId(), user.getUsername());
        return toVO(user, true);
    }

    @Override
    public UserVO currentUser() {
        Long userId = UserContext.requireUserId();
        return toVO(getByIdOrThrow(userId), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO updateMe(UserUpdateReq req) {
        Long userId = UserContext.requireUserId();
        User user = getByIdOrThrow(userId);

        if (StrUtil.isNotBlank(req.getNickname()) && !req.getNickname().equals(user.getNickname())) {
            long count = count(Wrappers.<User>lambdaQuery().eq(User::getNickname, req.getNickname()));
            if (count > 0) {
                throw new BizException(ResultCode.NICKNAME_EXISTS);
            }
            user.setNickname(req.getNickname());
        }
        if (req.getAvatar() != null) {
            user.setAvatar(req.getAvatar());
        }
        if (req.getGender() != null) {
            user.setGender(req.getGender());
        }
        if (req.getBirthday() != null) {
            user.setBirthday(req.getBirthday());
        }
        if (req.getSignature() != null) {
            user.setSignature(req.getSignature());
        }
        // 乐观锁：version 随实体带入 WHERE 条件（BR-10-018）
        if (!updateById(user)) {
            throw new BizException(ResultCode.CONCURRENT_MODIFY_CONFLICT);
        }
        return toVO(getById(userId), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(ChangePasswordReq req) {
        Long userId = UserContext.requireUserId();
        User user = getByIdOrThrow(userId);
        if (!passwordEncoder.matches(req.getOldPassword(), user.getPassword())) {
            throw new BizException(ResultCode.OLD_PASSWORD_ERROR);
        }
        if (req.getOldPassword().equals(req.getNewPassword())) {
            throw new BizException(ResultCode.PASSWORD_WEAK, "新密码不能与原密码相同");
        }
        validatePassword(req.getNewPassword());

        user.setPassword(passwordEncoder.encode(req.getNewPassword()));
        user.setForceChangePassword(0);
        if (!updateById(user)) {
            throw new BizException(ResultCode.CONCURRENT_MODIFY_CONFLICT);
        }
        // 密码变更后旧 refreshToken 必须失效；当前 accessToken 仍会在 30 分钟内自然过期
        tokenSessionStore.revokeAllSessions(userId);
        log.info("用户修改密码成功 userId={}", userId);
    }

    /** 与后台账号策略一致：8~64 位、字母 + 数字，拒绝常见弱口令前缀。 */
    private void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 64
                || !password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) {
            throw new BizException(ResultCode.PASSWORD_WEAK);
        }
        String lower = password.toLowerCase();
        if (lower.startsWith("password") || lower.startsWith("123456")) {
            throw new BizException(ResultCode.PASSWORD_WEAK);
        }
    }

    @Override
    public LoginUser buildLoginUser(Long userId) {
        User user = getByIdOrThrow(userId);
        return LoginUser.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .userType(user.getUserType())
                .roles(new LinkedHashSet<>(nullSafe(userMapper.selectRoleCodes(userId))))
                // 通配码在此展开为具体动作码：Token 内的权限码即可被 hasAuthority 精确匹配
                .perms(PermissionCatalogue.expand(nullSafe(userMapper.selectPermCodes(userId))))
                .build();
    }

    @Override
    public UserVO toVO(User user, boolean withAuth) {
        UserVO vo = UserVO.from(user);
        if (vo != null && withAuth && user.getId() != null) {
            vo.setRoles(new LinkedHashSet<>(nullSafe(userMapper.selectRoleCodes(user.getId()))));
            vo.setPerms(new LinkedHashSet<>(nullSafe(userMapper.selectPermCodes(user.getId()))));
        }
        return vo;
    }

    private void grantRole(Long userId, String roleCode) {
        Long roleId = roleMapper.selectIdByCode(roleCode);
        if (roleId == null) {
            log.error("默认角色不存在，roleCode={}，请检查 sql/data.sql 是否已初始化", roleCode);
            return;
        }
        UserRole userRole = new UserRole();
        userRole.setUserId(userId);
        userRole.setRoleId(roleId);
        userRoleMapper.insert(userRole);
    }

    private List<String> nullSafe(List<String> list) {
        return list == null ? Collections.emptyList() : list;
    }
}
