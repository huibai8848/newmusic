package com.example.newmusic.module.contentsafety.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("t_sensitive_word")
public class SensitiveWord implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int LEVEL_SUSPECT = 1;
    public static final int LEVEL_HIGH = 2;
    public static final int LEVEL_FORBIDDEN = 3;

    public static final int STATUS_DISABLED = 0;
    public static final int STATUS_ENABLED = 1;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String word;
    private String rawWord;
    private Integer level;
    private String category;
    private String source;
    private Long hitCount;
    private LocalDateTime lastHitTime;
    private Integer status;
    private String remark;
    private Long creatorId;
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
