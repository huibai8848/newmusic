package com.example.newmusic.module.admin.vo;

import io.swagger.annotations.ApiModel;
import lombok.Data;

import java.io.Serializable;

@Data
@ApiModel("完整手机号（敏感查看）")
public class SensitivePhoneVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String phone;
}
