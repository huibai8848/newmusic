package com.example.newmusic.module.socialupgrade.dto;

import lombok.Data;

import javax.validation.constraints.Size;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

@Data
public class ProfileUpdateReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @Size(max = 20, message = "昵称最多 20 个字符")
    private String nickname;
    @Size(max = 100, message = "简介最多 100 字")
    private String bio;
    private Integer gender;
    private LocalDate birthday;
    @Size(max = 64, message = "地区过长")
    private String region;
    @Size(max = 5, message = "最多 5 个标签")
    private List<String> tags;
    @Size(max = 20, message = "短码 4-20 位字母数字")
    private String userCode;
    /** 0公开 1仅关注者 2仅自己 */
    private Integer profileVisibility;
}
