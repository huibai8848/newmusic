package com.example.newmusic.module.contentsafety.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("t_sensitive_word_version")
public class SensitiveWordVersion implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Integer version;
    private Integer wordCount;
    private String snapshotPath;
    private String snapshotData;
    private Long operatorId;
    private String changeDesc;
    private LocalDateTime createTime;
}
