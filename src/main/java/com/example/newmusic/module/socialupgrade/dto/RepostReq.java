package com.example.newmusic.module.socialupgrade.dto;

import lombok.Data;

import javax.validation.constraints.Size;
import java.io.Serializable;

@Data
public class RepostReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @Size(max = 200, message = "转发评论最多 200 字")
    private String content;
}
