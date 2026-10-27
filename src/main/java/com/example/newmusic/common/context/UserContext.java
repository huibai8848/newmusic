package com.example.newmusic.common.context;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;

/**
 * 用户上下文（ThreadLocal）。
 *
 * <p>约束（《05-通用设计规范》4.2）：业务代码禁止从 {@code HttpServletRequest} 直接取用户信息，
 * 统一走这里；异步线程需手动 {@link #copyTo(LoginUser)} 传递。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    /** 未登录返回 null。 */
    public static Long getUserId() {
        LoginUser user = HOLDER.get();
        return user == null ? null : user.getUserId();
    }

    /** 未登录抛 40101。 */
    public static Long requireUserId() {
        Long userId = getUserId();
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    public static boolean isLogin() {
        return getUserId() != null;
    }

    public static boolean hasPermission(String perm) {
        LoginUser user = HOLDER.get();
        return user != null && user.hasPermission(perm);
    }

    /** 供异步线程传递上下文。 */
    public static void copyTo(LoginUser user) {
        HOLDER.set(user);
    }

    public static void clear() {
        HOLDER.remove();
    }
}
