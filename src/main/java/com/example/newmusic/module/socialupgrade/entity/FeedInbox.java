package com.example.newmusic.module.socialupgrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 关注流收件箱的 MySQL 兜底（Redis 才是主存储）。 */
@Data
@TableName("t_feed_inbox")
public class FeedInbox implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long postId;
    private Long authorId;
    private Long score;
    private LocalDateTime createTime;
}
