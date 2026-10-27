package com.example.newmusic.module.play.service;

import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.service.SongService;
import com.example.newmusic.module.play.dto.PlayQualitiesVO;
import com.example.newmusic.module.play.dto.PlayQualityVO;
import com.example.newmusic.module.play.support.PlaySettingSupport;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.user.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 音质能力查询（模块 12 `GET /play/qualities`）。
 *
 * <p>把「用户权限」与「这首歌有没有该档音源」两件事分开判断：
 * 前者决定入口是否可点，后者决定实际下发会不会降级。两者都不可用时，
 * 优先告诉用户「需要 VIP」，因为那是他能通过运营配置改变的约束。
 */
@Service
public class PlayQualityService {

    private final SongService songService;
    private final UserService userService;
    private final PlaySettingService playSettingService;

    public PlayQualityService(SongService songService,
                              UserService userService,
                              PlaySettingService playSettingService) {
        this.songService = songService;
        this.userService = userService;
        this.playSettingService = playSettingService;
    }

    public PlayQualitiesVO list(Long songId, Long userId) {
        Song song = songId == null ? null : songService.requirePlayable(songId);
        boolean vip = isVip(userId);
        String preferred = playSettingService.defaultQuality(userId);

        PlayQualitiesVO result = new PlayQualitiesVO();
        result.setSongId(songId);
        result.setVip(vip);

        String recommended = null;
        for (String code : PlaySettingSupport.QUALITIES) {
            PlayQualityVO option = new PlayQualityVO();
            option.setCode(code);
            option.setLabel(PlaySettingSupport.QUALITY_LABELS.get(code));
            option.setBitrate(PlaySettingSupport.QUALITY_BITRATES.get(code));
            option.setFormat(PlaySettingSupport.QUALITY_FORMATS.get(code));
            option.setVipOnly(PlaySettingSupport.QUALITY_LOSSLESS.equals(code));

            if (option.isVipOnly() && !vip) {
                option.setAvailable(false);
                option.setUnavailableReason("无损音质需要 VIP");
            } else if (song != null && !hasAudio(song, code)) {
                option.setAvailable(false);
                option.setUnavailableReason("该歌曲暂无" + option.getLabel() + "音质");
            } else {
                option.setAvailable(true);
            }
            result.getQualities().add(option);

            if (recommended == null && option.isAvailable() && code.equals(preferred)) {
                recommended = code;
            }
        }
        if (recommended == null) {
            // 用户偏好不可用（无音源/非 VIP）→ 按 high → standard 的顺序退
            for (String fallback : new String[]{PlaySettingSupport.QUALITY_HIGH, PlaySettingSupport.QUALITY_STANDARD}) {
                for (PlayQualityVO option : result.getQualities()) {
                    if (option.isAvailable() && option.getCode().equals(fallback)) {
                        recommended = fallback;
                        break;
                    }
                }
                if (recommended != null) {
                    break;
                }
            }
        }
        result.setRecommended(recommended);
        return result;
    }

    /** 是否有有效 VIP；游客一律 false。 */
    public boolean isVip(Long userId) {
        if (userId == null) {
            return false;
        }
        User user = userService.getById(userId);
        return PlaySettingSupport.isVip(user);
    }

    private static boolean hasAudio(Song song, String quality) {
        switch (quality) {
            case PlaySettingSupport.QUALITY_LOSSLESS:
                return StringUtils.hasText(song.getAudioLossless());
            case PlaySettingSupport.QUALITY_HIGH:
                return StringUtils.hasText(song.getAudioHigh());
            default:
                return StringUtils.hasText(song.getAudioStandard());
        }
    }
}
