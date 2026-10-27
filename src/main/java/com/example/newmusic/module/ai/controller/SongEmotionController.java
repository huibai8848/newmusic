package com.example.newmusic.module.ai.controller;

import com.example.newmusic.common.result.R;
import com.example.newmusic.infra.ratelimit.RateLimit;
import com.example.newmusic.module.ai.dto.SongEmotionVO;
import com.example.newmusic.module.ai.service.SongEmotionService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 歌曲情绪 / 场景标签（C 端）。公开读：与歌词一样属于歌曲元数据，不涉及个人数据。
 */
@Api(tags = "AI-歌曲情绪")
@RestController
@RequestMapping("/api/v1/songs")
public class SongEmotionController {

    private final SongEmotionService emotionService;

    public SongEmotionController(SongEmotionService emotionService) {
        this.emotionService = emotionService;
    }

    @ApiOperation(value = "歌曲情绪标签", notes = "未标注时 annotated=false，数值为中性默认值")
    @GetMapping("/{songId}/emotion")
    @RateLimit(limit = 120, windowSeconds = 60, type = "ai")
    public R<SongEmotionVO> emotion(@PathVariable Long songId) {
        return R.ok(emotionService.get(songId));
    }
}
