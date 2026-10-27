package com.example.newmusic.common.security;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 权限点目录与通配展开（《02-用户角色与权限矩阵》2.4 / 3.x）。
 *
 * <p>DB 中 `t_permission.perm_code` 允许登记资源级通配码（如 {@code music:artist:*}）。
 * Spring Security 的 {@code hasAuthority} 只做精确匹配，因此在**构建登录态时**
 * 把通配码展开成具体动作码（{@link #expand(Collection)}），
 * 同时 {@link #covers(Set, String)} 保留一份运行时兜底判断。
 *
 * <p>未登记在前缀表中的通配码不展开（记录原样），避免误放大权限。
 */
public final class PermissionCatalogue {

    /** 全量通配（仅超管） */
    public static final String ALL = "*:*:*";

    private static final String WILDCARD = "*";

    /** 资源前缀 → 该资源支持的动作集合。 */
    private static final Map<String, Set<String>> RESOURCE_ACTIONS = new LinkedHashMap<>();

    private static void register(String resource, String... actions) {
        RESOURCE_ACTIONS.put(resource, new LinkedHashSet<>(Arrays.asList(actions)));
    }

    static {
        register("music:song", "read", "create", "update", "delete", "audit", "online", "offline", "manage");
        register("music:artist", "read", "create", "update", "delete", "manage");
        register("music:album", "read", "create", "update", "delete", "manage");
        register("music:playlist", "read", "create", "update", "delete", "recommend", "manage");
        register("music:category", "read", "create", "update", "delete", "manage");
        register("music:tag", "read", "create", "update", "delete", "manage");
        register("music:lyric", "read", "create", "update", "delete", "manage");

        register("user:user", "read", "update", "ban", "unban", "resetPwd", "delete", "export");
        register("user:role", "read", "assign");

        register("social:comment", "read", "create", "delete", "audit");
        register("social:feed", "read", "create", "update", "delete");
        register("social:report", "read", "handle");
        register("social:message", "read");

        register("room:room", "read", "create", "update", "close", "forceClose", "recommend");
        register("room:mic", "queue", "control");

        register("audit:task", "read", "approve", "reject");
        register("audit:sensitive", "read", "create", "update", "delete");
        register("audit:appeal", "handle", "escalate");

        register("recommend:slot", "read", "create", "update", "delete");
        register("recommend:banner", "read", "create", "update", "delete");
    }

    private PermissionCatalogue() {
    }

    /**
     * 展开通配权限码：保留原码，并补上目录中定义的具体动作码。
     */
    public static Set<String> expand(Collection<String> codes) {
        Set<String> result = new LinkedHashSet<>();
        if (CollUtil.isEmpty(codes)) {
            return result;
        }
        for (String code : codes) {
            if (StrUtil.isBlank(code)) {
                continue;
            }
            result.add(code);
            String prefix = wildcardPrefix(code);
            if (prefix == null) {
                continue;
            }
            Set<String> actions = RESOURCE_ACTIONS.get(prefix);
            if (actions == null) {
                continue;
            }
            for (String action : actions) {
                result.add(prefix + ":" + action);
            }
        }
        return result;
    }

    /**
     * 判断已授予的权限码是否覆盖 {@code required}。
     *
     * <p>支持三种形式：精确匹配、资源级通配（{@code music:artist:*}）、全量通配（{@code *:*:*}）。
     */
    public static boolean covers(Collection<String> granted, String required) {
        if (CollUtil.isEmpty(granted) || StrUtil.isBlank(required)) {
            return false;
        }
        if (granted.contains(ALL) || granted.contains(required)) {
            return true;
        }
        // 逐层前缀通配：music:artist:*、music:*、*:*:*
        String prefix = required;
        int idx = prefix.lastIndexOf(':');
        while (idx > 0) {
            prefix = prefix.substring(0, idx);
            if (granted.contains(prefix + ":*")) {
                return true;
            }
            idx = prefix.lastIndexOf(':');
        }
        return false;
    }

    /** 返回 `a:b:` 形式的通配前缀；非通配码返回 null。 */
    private static String wildcardPrefix(String code) {
        if (!code.endsWith(":" + WILDCARD) || ALL.equals(code)) {
            return null;
        }
        return code.substring(0, code.length() - 2);
    }

    /** 目录内所有资源前缀（测试/后台下拉用）。 */
    public static Set<String> resources() {
        return Collections.unmodifiableSet(RESOURCE_ACTIONS.keySet());
    }
}
