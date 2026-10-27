package com.example.newmusic.infra.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.example.newmusic.common.context.UserContext;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计字段自动填充（《10-模块01-数据库CRUD》4.2）。
 *
 * <p>无用户上下文的场景（MQ 消费、定时任务、初始化脚本）createBy/updateBy 填 0（系统）。
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    private static final Long SYSTEM_USER = 0L;

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        Long uid = currentUserId();

        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "createBy", Long.class, uid);
        this.strictInsertFill(metaObject, "updateBy", Long.class, uid);
        this.strictInsertFill(metaObject, "version", Integer.class, 0);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        this.strictUpdateFill(metaObject, "updateBy", Long.class, currentUserId());
    }

    private Long currentUserId() {
        Long uid = UserContext.getUserId();
        return uid == null ? SYSTEM_USER : uid;
    }
}
