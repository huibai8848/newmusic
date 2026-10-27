package com.example.newmusic.module.socialupgrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** @ 提及关系（FR-25-007）。offset/length 是 UTF-16 下标，与前端 JS 一致。 */
@Data
@TableName("t_post_mention")
public class PostMention implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int SOURCE_POST = 1;
    public static final int SOURCE_COMMENT = 2;
    public static final int SOURCE_MESSAGE = 3;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Integer sourceType;
    private Long sourceId;
    private Long authorId;
    private Long mentionedUserId;
    private Integer offset;
    private Integer length;
    private Integer notified;
    private LocalDateTime createTime;
}
