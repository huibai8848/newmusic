package com.example.newmusic.module.admin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 后台操作日志注解。加在 Controller 的写方法上；失败也会留痕。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminOpLog {

    /** CREATE / UPDATE / DELETE / BAN / UNBAN / AUDIT / CONFIG / EXPORT / VIEW_SENSITIVE / ACCESS_DENIED */
    String type();

    /** USER / SONG / ARTIST / ALBUM / ROOM / REPORT / AUDIT / CONFIG / ADMIN_USER */
    String target() default "";

    String description() default "";

    /** 敏感操作：额外通知全部超管（FR-18-019） */
    boolean sensitive() default false;
}
