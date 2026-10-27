package com.example.newmusic.module.socialupgrade.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class IntimacyVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private Long targetUserId;
    private Integer score;
    private Integer level;
    private String levelName;
    private Boolean scoreVisible;
}
