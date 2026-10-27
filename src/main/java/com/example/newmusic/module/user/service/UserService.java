package com.example.newmusic.module.user.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.newmusic.common.context.LoginUser;
import com.example.newmusic.module.user.dto.ChangePasswordReq;
import com.example.newmusic.module.user.dto.RegisterReq;
import com.example.newmusic.module.user.dto.UserUpdateReq;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.user.vo.UserVO;

/**
 * 用户域对外服务接口（其他模块只允许依赖本接口，禁止直连 UserMapper）。
 */
public interface UserService extends IService<User> {

    /** 按 ID 查询，不存在抛 20004。 */
    User getByIdOrThrow(Long userId);

    /** 按用户名查询（可空）。 */
    User getByUsername(String username);

    /** 注册（含默认角色授予）。 */
    UserVO register(RegisterReq req, String clientIp);

    /** 当前登录用户资料。 */
    UserVO currentUser();

    /** 修改当前登录用户资料。 */
    UserVO updateMe(UserUpdateReq req);

    /**
     * 修改当前登录用户密码。管理员重置密码后 {@code force_change_password=1}，
     * C 端凭临时密码调用本接口设置新密码，成功后清除该标记并撤销全部 refreshToken。
     */
    void changePassword(ChangePasswordReq req);

    /** 组装登录上下文（含 roles / perms）。 */
    LoginUser buildLoginUser(Long userId);

    /** 实体 → VO；withAuth 时附带 roles / perms。 */
    UserVO toVO(User user, boolean withAuth);
}
