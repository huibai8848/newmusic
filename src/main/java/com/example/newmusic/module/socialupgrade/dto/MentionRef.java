package com.example.newmusic.module.socialupgrade.dto;

import lombok.Data;

import java.io.Serializable;

/** 前端选中 @ 用户后回传的定位信息；offset/length 以 UTF-16 计。 */
@Data
public class MentionRef implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private Integer offset;
    private Integer length;
}
