package com.example.newmusic.module.socialupgrade.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import javax.validation.constraints.NotEmpty;
import java.util.List;

@Data
@ApiModel("邀请群成员")
public class AddMembersReq {

    @NotEmpty(message = "请选择要邀请的用户")
    private List<Long> userIds;
}
