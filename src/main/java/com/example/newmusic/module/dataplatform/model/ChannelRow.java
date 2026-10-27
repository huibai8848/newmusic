package com.example.newmusic.module.dataplatform.model;

import lombok.Data;

/** 用户注册渠道。 */
@Data
public class ChannelRow {
    private Long userId;
    private String channel;
}
