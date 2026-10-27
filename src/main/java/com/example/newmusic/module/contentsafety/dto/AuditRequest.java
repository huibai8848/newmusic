package com.example.newmusic.module.contentsafety.dto;

import lombok.Data;

import java.util.List;

/**
 * 机审入参（FR-27-003）。内容 ID 允许为空：动态/歌单等需要先落库拿到 ID 再审核，
 * 此时记录里 content_id 为空，但 task 会在拿到 ID 后创建。
 */
@Data
public class AuditRequest {

    /** TEXT/IMAGE/NICKNAME/PLAYLIST/ROOM/DANMAKU/COMMENT/POST/MESSAGE */
    private String contentType;

    private Long contentId;
    private String text;
    private List<String> images;
    private Long userId;
    private String scene;

    /** 人工队列展示用标题；为空时按内容类型生成 */
    private String title;

    public static AuditRequest of(String contentType, Long contentId, String text,
                                  Long userId, String scene) {
        AuditRequest request = new AuditRequest();
        request.setContentType(contentType);
        request.setContentId(contentId);
        request.setText(text);
        request.setUserId(userId);
        request.setScene(scene);
        return request;
    }
}
