package com.example.newmusic.module.play.service.impl;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.service.SongService;
import com.example.newmusic.module.play.dto.PlayUrlVO;
import com.example.newmusic.module.play.service.PlayQualityService;
import com.example.newmusic.module.play.service.PlayUrlService;
import com.example.newmusic.module.play.support.PlayUrlSigner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * 播放地址签发实现。
 *
 * <p>跨模块只依赖 {@link SongService} 的<b>服务接口</b>，不注入 {@code SongMapper}
 * （模块边界约束，见模块 01 系分 §2）。版权、上下架、审核、音源存在性的校验
 * 全部复用 {@link SongService#requirePlayable(Long)}，避免两处各写一套规则。
 */
@Slf4j
@Service
public class PlayUrlServiceImpl implements PlayUrlService {

    private static final String QUALITY_STANDARD = "standard";
    private static final String QUALITY_HIGH = "high";
    private static final String QUALITY_LOSSLESS = "lossless";

    /** 音质降级阶梯：请求档位 → 依次尝试的档位 */
    private static final String[][] LADDER = {
            {QUALITY_LOSSLESS, QUALITY_LOSSLESS, QUALITY_HIGH, QUALITY_STANDARD},
            {QUALITY_HIGH, QUALITY_HIGH, QUALITY_STANDARD},
            {QUALITY_STANDARD, QUALITY_STANDARD},
    };

    private static final String REASON_NO_SOURCE = "NO_SOURCE";
    private static final String REASON_VIP_REQUIRED = "VIP_REQUIRED";
    private static final String REASON_LOGIN_REQUIRED = "LOGIN_REQUIRED";

    private final SongService songService;
    private final PlayUrlSigner signer;
    private final PlayQualityService playQualityService;
    private final String cdnBase;

    public PlayUrlServiceImpl(SongService songService,
                              PlayUrlSigner signer,
                              PlayQualityService playQualityService,
                              @Value("${newmusic.play.cdn-base:}") String cdnBase) {
        this.songService = songService;
        this.signer = signer;
        this.playQualityService = playQualityService;
        this.cdnBase = cdnBase == null ? "" : cdnBase.trim();
    }

    @Override
    public PlayUrlVO issue(Long songId, String quality, Long userId) {
        if (songId == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "songId 不能为空");
        }
        // 存在 + 已上架 + 审核通过 + 版权有效 + 至少有一档音源；不满足时抛 30001/30008/30002/30003
        Song song = songService.requirePlayable(songId);

        String requested = normalize(quality);

        // 权限降级发生在「音源降级」之前：先决定用户这一档到底有没有资格听，
        // 再从该档开始沿阶梯找音源。否则非 VIP 请求无损时，会先选中 FLAC
        // 再「降级」成 high，响应里的原因就说不清了。
        boolean loginGated = userId == null && !QUALITY_STANDARD.equals(requested);
        boolean vipGated = QUALITY_LOSSLESS.equals(requested) && userId != null
                && !playQualityService.isVip(userId);
        String effectiveRequest = loginGated ? QUALITY_STANDARD
                : vipGated ? QUALITY_HIGH
                : requested;

        String picked = null;
        String url = null;
        for (String candidate : ladderOf(effectiveRequest)) {
            String value = audioUrlOf(song, candidate);
            if (StringUtils.hasText(value)) {
                picked = candidate;
                url = value;
                break;
            }
        }
        if (url == null) {
            // requirePlayable 已保证 standard 非空，正常走不到这里；留作防御
            throw new BizException(ResultCode.AUDIO_QUALITY_UNAVAILABLE, "该歌曲暂无可用音质");
        }

        long expires = System.currentTimeMillis() / 1000 + signer.ttlSeconds();
        // 签名针对**不含查询串的路径**：这样加/减 expires 参数不会改变签名口径（PRD 的 path 语义）
        String path = stripQuery(url);
        String sign = signer.sign(path, expires, userId);

        PlayUrlVO vo = new PlayUrlVO();
        vo.setSongId(song.getId());
        vo.setQuality(picked);
        vo.setRequestedQuality(requested);
        boolean downgraded = !picked.equals(requested);
        vo.setDowngraded(downgraded);
        if (downgraded) {
            if (loginGated) {
                vo.setDowngradeReason(REASON_LOGIN_REQUIRED);
                vo.setNotice("登录后可切换高清音质，当前以标准音质播放");
            } else if (vipGated) {
                vo.setDowngradeReason(REASON_VIP_REQUIRED);
                vo.setNotice("无损音质需要 VIP，已切换为高清");
            } else {
                vo.setDowngradeReason(REASON_NO_SOURCE);
                vo.setNotice("该歌曲暂无" + labelOf(requested) + "音质，已降级播放");
            }
        }
        vo.setUrl(cdnBase + path + "?expires=" + expires + "&sign=" + sign);
        vo.setDuration(song.getDuration());
        vo.setFormat(formatOf(path));
        vo.setExpiresAt(expires * 1000);
        vo.setPreviewDuration(null);

        log.debug("播放地址签发 songId={} 请求={} 下发={} 降级={} 过期={}",
                song.getId(), requested, picked, vo.isDowngraded(), expires);
        return vo;
    }

    private String normalize(String quality) {
        if (!StringUtils.hasText(quality)) {
            return QUALITY_STANDARD;
        }
        String q = quality.trim().toLowerCase(Locale.ROOT);
        for (String[] row : LADDER) {
            if (row[0].equals(q)) {
                return q;
            }
        }
        // 非法音质按标准处理而不是报错：与「排序字段不在白名单则回退」的宽松约定一致（BR-10-002）
        log.warn("未知音质参数，按 standard 处理 quality={}", quality);
        return QUALITY_STANDARD;
    }

    private String[] ladderOf(String requested) {
        for (String[] row : LADDER) {
            if (row[0].equals(requested)) {
                return row;
            }
        }
        return LADDER[2];
    }

    private String audioUrlOf(Song song, String quality) {
        switch (quality) {
            case QUALITY_LOSSLESS:
                return song.getAudioLossless();
            case QUALITY_HIGH:
                return song.getAudioHigh();
            default:
                return song.getAudioStandard();
        }
    }

    private static String stripQuery(String url) {
        int idx = url.indexOf('?');
        return idx < 0 ? url : url.substring(0, idx);
    }

    private static String labelOf(String quality) {
        switch (quality) {
            case QUALITY_LOSSLESS:
                return "无损";
            case QUALITY_HIGH:
                return "高清";
            default:
                return "标准";
        }
    }

    private static String formatOf(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return "unknown";
        }
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
