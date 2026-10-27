package com.example.newmusic.module.user.controller;

import com.example.newmusic.common.result.R;
import com.example.newmusic.module.user.dto.ChangePasswordReq;
import com.example.newmusic.module.user.dto.UserUpdateReq;
import com.example.newmusic.module.user.service.UserService;
import com.example.newmusic.module.user.vo.UserVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@Api(tags = "C端接口-用户")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @ApiOperation(value = "我的资料", notes = "含 roles / perms，供前端按钮级控制")
    @GetMapping("/me")
    public R<UserVO> me() {
        return R.ok(userService.currentUser());
    }

    @ApiOperation(value = "修改我的资料", notes = "昵称唯一；乐观锁冲突返回 10008")
    @PutMapping("/me")
    public R<UserVO> updateMe(@RequestBody @Valid UserUpdateReq req) {
        return R.ok(userService.updateMe(req));
    }

    @ApiOperation(value = "修改我的密码", notes = "被管理员重置过密码时，用临时密码调用；成功后强制改密标记清除")
    @PutMapping("/me/password")
    public R<Void> changePassword(@RequestBody @Valid ChangePasswordReq req) {
        userService.changePassword(req);
        return R.ok();
    }
}
