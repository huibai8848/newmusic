package com.example.newmusic.infra.ratelimit;

/**
 * 限流维度（PRD FR-12-005 的维度区分表）。
 *
 * <p>维度决定「按什么标识计数」，标识最终拼进 Key：
 * {@code ml:limit:{type}:{dimension}:{identifier}:{api}}。
 */
public enum RateLimitDimension {

    /** 客户端 IP：防单机刷接口。可被代理池绕过，所以必须与其它维度叠加使用 */
    IP,

    /** 登录用户：防单账号刷接口。未登录时回退到 IP 维度 */
    USER,

    /**
     * 业务账号（登录前的用户名/手机号）：防针对单账号的撞库。
     *
     * <p>标识来自请求体而非请求头，AOP 注解拿不到，故只由
     * {@link RateLimitService#check} 在业务代码里显式调用（如登录流程）。
     */
    ACCOUNT,

    /** 全局：保护下游资源（第三方接口、昂贵查询），与调用方无关 */
    GLOBAL
}
