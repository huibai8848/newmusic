package com.example.newmusic.module.recommend.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.newmusic.common.entity.BaseEntity;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 冷启动口味问卷 t_user_preference（PRD 17 FR-17-006）。
 *
 * <p>字段用逗号串而不是关联表：问卷只在注册时写一次、推荐时整体读取，
 * 量级与「一个用户一行」一致，拆表只会多两次 JOIN。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_preference")
@ApiModel("用户口味偏好")
public class UserPreference extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String styles;
    private String languages;
    private String artists;
    private String mood;
    private Integer source;
}
