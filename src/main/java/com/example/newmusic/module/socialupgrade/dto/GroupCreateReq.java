package com.example.newmusic.module.socialupgrade.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import java.util.List;

@Data
@ApiModel("创建群聊")
public class GroupCreateReq {

    @Size(max = 30, message = "群名称最多 30 个字符")
    private String name;

    /** 除创建者外的成员，至少 2 人（群聊总数 3~20） */
    @NotEmpty(message = "请选择群成员")
    private List<Long> memberIds;
}
