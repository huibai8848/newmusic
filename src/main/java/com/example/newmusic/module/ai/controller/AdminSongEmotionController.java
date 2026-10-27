package com.example.newmusic.module.ai.controller;

import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.ai.dto.EmotionUpdateReq;
import com.example.newmusic.module.ai.dto.SongEmotionVO;
import com.example.newmusic.module.ai.service.SongEmotionService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 情绪标注人工校正。
 *
 * <p>路径按 PRD 放在 {@code /admin/songs} 下，但它改的是 AI 数据，所以权限接受
 * {@code admin:ai:edit} 或既有的 {@code music:song:update} 二者之一：
 * PRD 写的 {@code admin:song:edit} 在本项目权限表里不存在，与其新增一个同义权限码，
 * 不如复用已有能力，避免权限矩阵出现两个含义相同的条目。
 */
@Api(tags = "后台接口-AI 情绪校正")
@RestController
@RequestMapping("/api/v1/admin/songs")
public class AdminSongEmotionController {

    private final SongEmotionService emotionService;

    public AdminSongEmotionController(SongEmotionService emotionService) {
        this.emotionService = emotionService;
    }

    @ApiOperation(value = "校正歌曲情绪", notes = "保存后 source=MANUAL，自动批处理不再覆盖")
    @PutMapping("/{id}/emotion")
    @PreAuthorize("hasAnyAuthority('admin:ai:edit','music:song:update')")
    @AdminOpLog(type = "AI_EMOTION_UPDATE", target = "SONG_EMOTION",
            description = "人工校正歌曲情绪标注")
    public R<SongEmotionVO> update(@PathVariable Long id,
                                   @RequestBody @Valid EmotionUpdateReq req) {
        return R.ok(emotionService.updateManual(id, req));
    }
}
