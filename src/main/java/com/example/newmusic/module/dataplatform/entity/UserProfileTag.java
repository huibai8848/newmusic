package com.example.newmusic.module.dataplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 用户画像标签（10 类，T+1 更新，BR-26-038/039）。 */
@Data
@TableName("t_user_profile_tag")
public class UserProfileTag {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String tagType;
    private String tagName;
    private String tagValue;
    private String tagVersion;
    private LocalDate updateDate;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
