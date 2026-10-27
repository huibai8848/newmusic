package com.example.newmusic.module.recupgrade.model;

/**
 * 统一召回源接口（FR-24-001 / BR-24-001）。
 *
 * <p>引擎只认这个接口，禁止在 {@code RecallEngine} 里硬编码任何召回逻辑。
 * 新增一个源只需实现本接口并交给 Spring，配置里打开即可。
 */
public interface RecallSource {

    /** 唯一名称，用于配置、埋点、监控（15 个源的名字与 PRD 表格一致）。 */
    String name();

    /** 是否需要用户上下文；游客无 userId 时跳过。 */
    default boolean requiresUser() {
        return true;
    }

    /** 是否启用；具体是否真正调用还受 {@code t_rec_config} 的 RECALL 配置控制。 */
    default boolean enabled(RecallContext ctx) {
        return true;
    }

    /** 召回。只允许返回 ID + 分数，禁止逐首查详情（BR-24-014）。 */
    RecallResult recall(RecallContext ctx);

    /** 单源超时（毫秒）。超时由引擎隔离，不影响其它源。 */
    default int timeoutMs() {
        return 100;
    }

    /** 优先级，数值越大越优先；仅用于调试展示与同分排序。 */
    default int priority() {
        return 0;
    }
}
