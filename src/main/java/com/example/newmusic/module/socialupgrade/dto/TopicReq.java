package com.example.newmusic.module.socialupgrade.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

@Data
public class TopicReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "话题名不能为空")
    private String name;
    private Integer isOfficial;
    /** 0正常 1屏蔽 */
    private Integer status;
}
