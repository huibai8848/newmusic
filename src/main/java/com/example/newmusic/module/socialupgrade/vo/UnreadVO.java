package com.example.newmusic.module.socialupgrade.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class UnreadVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private long total;
    /** interaction/follow/message/system/music → 未读数 */
    private Map<String, Long> categories;
    /** 前端展示用：任一分类超过 99 时返回 true */
    private boolean overflow;
}
