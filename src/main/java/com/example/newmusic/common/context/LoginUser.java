package com.example.newmusic.common.context;

import com.example.newmusic.common.security.PermissionCatalogue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.Set;

/**
 * 当前登录用户（请求级上下文）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String username;
    private String nickname;
    /** 1普通 2运营 3管理员 4超管 */
    private Integer userType;
    /** 角色码，如 ROLE_ADMIN */
    @Builder.Default
    private Set<String> roles = Collections.emptySet();
    /** 权限码，如 music:song:create */
    @Builder.Default
    private Set<String> perms = Collections.emptySet();

    /**
     * 权限判断：支持精确码、资源级通配（music:artist:*）与全量通配（*:*:*）。
     */
    public boolean hasPermission(String perm) {
        return PermissionCatalogue.covers(perms, perm);
    }

    /** 是否拥有全部权限（超管）。 */
    public boolean isSuperAdmin() {
        return perms != null && perms.contains(PermissionCatalogue.ALL);
    }
}
