package com.example.newmusic.module.admin.service;

import com.example.newmusic.common.context.LoginUser;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.util.IpUtil;
import com.example.newmusic.common.util.TraceIdHolder;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.admin.entity.OperationLog;
import com.example.newmusic.module.admin.mapper.AdminUserMapper;
import com.example.newmusic.module.ws.entity.Notification;
import com.example.newmusic.module.ws.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 操作日志切面。
 *
 * <p>脱敏在**进入异步线程之前**完成：密码、Token、TOTP 密钥、手机号等一律替换为
 * {@code ***}，正文截断到 2000 字符。异步落库失败不回滚业务，只打 ERROR。
 */
@Slf4j
@Aspect
@Component
public class OperationLogAspect {

    private static final int MAX_SNAPSHOT = 2000;

    private final OperationLogService operationLogService;
    private final NotificationService notificationService;
    private final AdminUserMapper adminUserMapper;
    private final ObjectMapper objectMapper;

    public OperationLogAspect(OperationLogService operationLogService,
                              NotificationService notificationService,
                              AdminUserMapper adminUserMapper,
                              ObjectMapper objectMapper) {
        this.operationLogService = operationLogService;
        this.notificationService = notificationService;
        this.adminUserMapper = adminUserMapper;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(annotation)")
    public Object around(ProceedingJoinPoint joinPoint, AdminOpLog annotation) throws Throwable {
        long start = System.currentTimeMillis();
        Object result = null;
        Throwable error = null;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            try {
                persist(joinPoint, annotation, result, error, start);
            } catch (Exception e) {
                log.error("操作日志切面失败（业务结果不受影响）", e);
            }
        }
    }

    private void persist(ProceedingJoinPoint joinPoint, AdminOpLog annotation, Object result,
                         Throwable error, long start) {
        HttpServletRequest request = currentRequest();
        LoginUser operator = UserContext.get();

        OperationLog log = new OperationLog();
        log.setOperatorId(operator == null ? 0L : operator.getUserId());
        log.setOperatorName(operator == null ? null : operator.getUsername());
        log.setOpType(annotation.type());
        log.setTargetType(StringUtils.hasText(annotation.target()) ? annotation.target() : null);
        log.setTargetId(resolveTargetId(joinPoint.getArgs(), result));
        log.setDescription(truncate(annotation.description(), 500));
        log.setIp(request == null ? null : IpUtil.getClientIp(request));
        log.setUserAgent(request == null ? null
                : truncate(request.getHeader("User-Agent"), 512));
        log.setResult(error == null ? 1 : 0);
        log.setErrorMsg(error == null ? null : truncate(error.getMessage(), 500));
        log.setTraceId(TraceIdHolder.current());
        log.setAfterSnapshot(snapshot(joinPoint.getArgs(), result));
        log.setCreateTime(java.time.LocalDateTime.now());
        operationLogService.save(log);

        if (annotation.sensitive() && operator != null) {
            notifySuperAdmins(operator, annotation);
        }
    }

    private void notifySuperAdmins(LoginUser operator, AdminOpLog annotation) {
        try {
            List<Long> ids = adminUserMapper.selectSuperAdminIds(operator.getUserId());
            if (ids == null || ids.isEmpty()) {
                return;
            }
            for (Long id : ids) {
                Notification n = new Notification();
                n.setUserId(id);
                n.setNotifyType(Notification.TYPE_SYSTEM);
                n.setTitle("敏感操作提醒");
                n.setContent(operator.getUsername() + " 执行了 " + annotation.type()
                        + "（" + annotation.description() + "）");
                n.setFromUserId(operator.getUserId());
                n.setFromNickname(operator.getNickname());
                n.setIsRead(0);
                notificationService.createAndPush(n);
            }
        } catch (Exception e) {
            log.warn("敏感操作通知超管失败 operator={} cause={}", operator.getUsername(), e.toString());
        }
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes) {
            return ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        }
        return null;
    }

    /** 目标 ID 的启发式：返回值为 Long 优先，其次是第一个 Long 参数（路径变量通常是）。 */
    private String resolveTargetId(Object[] args, Object result) {
        if (result instanceof Long) {
            return String.valueOf(result);
        }
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof Long) {
                    return String.valueOf(arg);
                }
            }
        }
        return null;
    }

    private String snapshot(Object[] args, Object result) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            List<Object> sanitized = new ArrayList<>();
            if (args != null) {
                for (Object arg : args) {
                    sanitized.add(sanitize(arg));
                }
            }
            data.put("args", sanitized);
            data.put("result", sanitize(result));
            return truncate(objectMapper.writeValueAsString(data), MAX_SNAPSHOT);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把参数对象转成 Map 后按键名脱敏；无法序列化的类型退化为类型名。
     * 这样既保留了「改了什么」的可追溯性，又不会把密码写进日志表。
     */
    @SuppressWarnings("unchecked")
    private Object sanitize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        // Servlet 容器对象不能交给 Jackson 反射：它会调用 getWriter()/getOutputStream()，
        // 把响应的输出流占住，之后异常处理器再想写 JSON 就会 500
        // （典型现场：导出接口抛 72005/72006 时反而返回 Spring 默认错误页）。
        if (value instanceof javax.servlet.ServletRequest
                || value instanceof javax.servlet.ServletResponse) {
            return value.getClass().getSimpleName();
        }
        try {
            Object tree = objectMapper.convertValue(value, Object.class);
            return maskTree(tree);
        } catch (Exception e) {
            return value.getClass().getSimpleName();
        }
    }

    @SuppressWarnings("unchecked")
    private Object maskTree(Object node) {
        if (node instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) node;
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String key = entry.getKey();
                if (isSecretKey(key)) {
                    copy.put(key, "***");
                } else if (isPhoneKey(key) && entry.getValue() instanceof String) {
                    copy.put(key, maskPhone((String) entry.getValue()));
                } else {
                    copy.put(key, maskTree(entry.getValue()));
                }
            }
            return copy;
        }
        if (node instanceof java.util.Collection) {
            List<Object> list = new ArrayList<>();
            for (Object item : (java.util.Collection<Object>) node) {
                list.add(maskTree(item));
            }
            return list;
        }
        if (node instanceof Object[]) {
            return Arrays.stream((Object[]) node).map(this::maskTree).collect(java.util.stream.Collectors.toList());
        }
        return node;
    }

    private boolean isSecretKey(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("password") || k.contains("token") || k.contains("secret")
                || k.contains("captcha") || k.contains("authorization");
    }

    private boolean isPhoneKey(String key) {
        return "phone".equalsIgnoreCase(key);
    }

    private String maskPhone(String phone) {
        if (phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
