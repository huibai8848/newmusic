package com.example.newmusic.module.ops.datasource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明该方法内的读可以走从库（FR-28-006）。
 *
 * <p>只标注「对延迟不敏感」的查询：报表、后台列表、统计。
 * 业务主链路上的「改完立即查」不能标——那正是 BR-28-060 要避免的场景。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Slave {
}
