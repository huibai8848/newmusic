package com.example.newmusic.infra.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流（FR-12-005）。加在 Controller 方法上，由 {@link RateLimitAspect} 织入。
 *
 * <p>阈值刻意写在注解上而不是配置文件里：限流阈值是接口契约的一部分，
 * 评审时应该能在方法上直接看到，而不是去翻一份几百行的 YAML。
 *
 * <p>未覆盖到的接口不做限流。PRD 的原话是「IP 级覆盖全部公开接口」，这里是有意收窄：
 * 用全局拦截器给所有请求（含静态资源、健康检查）套限流，收益远小于误伤风险（系分 D-28）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /** 窗口内允许的最大请求数 */
    int limit();

    /** 窗口长度（秒） */
    int windowSeconds() default 60;

    /** 计数维度 */
    RateLimitDimension dimension() default RateLimitDimension.IP;

    /**
     * 业务类型，只用于拼 Key 和排查，如 {@code api} / {@code player} / {@code auth}。
     */
    String type() default "api";

    /** 自定义提示语；留空则用错误码默认文案 */
    String message() default "";
}
