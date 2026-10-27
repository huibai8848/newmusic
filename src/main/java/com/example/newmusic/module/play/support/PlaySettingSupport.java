package com.example.newmusic.module.play.support;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.play.dto.PlaySettingReq;
import com.example.newmusic.module.play.entity.UserPlaySetting;
import com.example.newmusic.module.user.entity.User;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 播放设置的取值字典与校验（模块 12 FR-21-008）。
 *
 * <p>抽成无状态工具类而不是塞进 Service：校验逻辑是纯函数，可以被零依赖单测直接覆盖；
 * HTTP/缓存/落库的细节留在 {@code PlaySettingService}。
 *
 * <p>非法值**一律报 30061**，不做「悄悄回退到默认值」——音质/倍速这类设置如果被静默改写，
 * 用户看到的选择与实际听到的不一致，比直接报错更难排查。
 */
public final class PlaySettingSupport {

    public static final String QUALITY_STANDARD = "standard";
    public static final String QUALITY_HIGH = "high";
    public static final String QUALITY_LOSSLESS = "lossless";

    /** 从高到低的展示顺序 */
    public static final List<String> QUALITIES =
            Collections.unmodifiableList(Arrays.asList(QUALITY_LOSSLESS, QUALITY_HIGH, QUALITY_STANDARD));

    public static final Map<String, String> QUALITY_LABELS;
    public static final Map<String, String> QUALITY_BITRATES;
    public static final Map<String, String> QUALITY_FORMATS;

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(QUALITY_STANDARD, "标准");
        labels.put(QUALITY_HIGH, "高清");
        labels.put(QUALITY_LOSSLESS, "无损");
        QUALITY_LABELS = Collections.unmodifiableMap(labels);

        Map<String, String> bitrates = new LinkedHashMap<>();
        bitrates.put(QUALITY_STANDARD, "128 kbps");
        bitrates.put(QUALITY_HIGH, "320 kbps");
        bitrates.put(QUALITY_LOSSLESS, "900~1100 kbps");
        QUALITY_BITRATES = Collections.unmodifiableMap(bitrates);

        Map<String, String> formats = new LinkedHashMap<>();
        formats.put(QUALITY_STANDARD, "mp3");
        formats.put(QUALITY_HIGH, "mp3");
        formats.put(QUALITY_LOSSLESS, "flac");
        QUALITY_FORMATS = Collections.unmodifiableMap(formats);
    }

    private static final Set<String> PLAY_MODES =
            new HashSet<>(Arrays.asList("order", "loop", "single", "shuffle"));

    /** 7 档倍速，与 PRD FR-21-002 完全一致（0.5x~2.0x） */
    public static final List<BigDecimal> RATES = Collections.unmodifiableList(Arrays.asList(
            new BigDecimal("0.50"), new BigDecimal("0.75"), BigDecimal.ONE.setScale(2),
            new BigDecimal("1.25"), new BigDecimal("1.50"), new BigDecimal("1.75"), new BigDecimal("2.00")));

    private static final Set<String> LYRIC_MODES =
            new HashSet<>(Arrays.asList("original", "translation", "original_translation", "none"));

    private static final Set<String> THEMES = new HashSet<>(Arrays.asList("system", "light", "dark"));

    private static final Set<String> EQ_PRESETS = new HashSet<>(Arrays.asList(
            "default", "pop", "rock", "vocal", "bass", "treble", "classical", "custom"));

    private static final int LYRIC_OFFSET_LIMIT = 5000;
    private static final int EQ_BANDS_MAX_LENGTH = 120;

    private PlaySettingSupport() {
    }

    /**
     * 把请求里的非空字段合并到设置对象上。
     *
     * @throws BizException 30061，message 指明具体字段与合法取值
     */
    public static void apply(UserPlaySetting setting, PlaySettingReq req) {
        if (req == null) {
            return;
        }
        if (req.getDefaultQuality() != null) {
            setting.setDefaultQuality(quality(req.getDefaultQuality()));
        }
        if (req.getPlayMode() != null) {
            String mode = lower(req.getPlayMode(), "playMode");
            if (!PLAY_MODES.contains(mode)) {
                throw invalid("playMode 只能是 order/loop/single/shuffle");
            }
            setting.setPlayMode(mode);
        }
        if (req.getAutoQuality() != null) {
            setting.setAutoQuality(flag(req.getAutoQuality(), "autoQuality"));
        }
        if (req.getDataSaver() != null) {
            setting.setDataSaver(flag(req.getDataSaver(), "dataSaver"));
        }
        if (req.getFadeEnabled() != null) {
            setting.setFadeEnabled(flag(req.getFadeEnabled(), "fadeEnabled"));
        }
        if (req.getEqualizerPreset() != null) {
            String preset = lower(req.getEqualizerPreset(), "equalizerPreset");
            if (!EQ_PRESETS.contains(preset)) {
                throw invalid("equalizerPreset 只能是 " + String.join("/", EQ_PRESETS));
            }
            setting.setEqualizerPreset(preset);
        }
        if (req.getEqualizerBands() != null) {
            String bands = req.getEqualizerBands().trim();
            if (bands.isEmpty()) {
                setting.setEqualizerBands(null);
            } else {
                if (bands.length() > EQ_BANDS_MAX_LENGTH || !bands.startsWith("[") || !bands.endsWith("]")) {
                    throw invalid("equalizerBands 必须是不超过 " + EQ_BANDS_MAX_LENGTH + " 字符的 JSON 数组");
                }
                setting.setEqualizerBands(bands);
            }
        }
        if (req.getPlaybackRate() != null) {
            setting.setPlaybackRate(rate(req.getPlaybackRate()));
        }
        if (req.getLyricMode() != null) {
            String mode = lower(req.getLyricMode(), "lyricMode");
            if (!LYRIC_MODES.contains(mode)) {
                throw invalid("lyricMode 只能是 original/translation/original_translation/none");
            }
            setting.setLyricMode(mode);
        }
        if (req.getLyricOffset() != null) {
            int offset = req.getLyricOffset();
            if (offset < -LYRIC_OFFSET_LIMIT || offset > LYRIC_OFFSET_LIMIT) {
                throw invalid("lyricOffset 必须在 ±" + LYRIC_OFFSET_LIMIT + " 毫秒内");
            }
            setting.setLyricOffset(offset);
        }
        if (req.getTheme() != null) {
            String theme = lower(req.getTheme(), "theme");
            if (!THEMES.contains(theme)) {
                throw invalid("theme 只能是 system/light/dark");
            }
            setting.setTheme(theme);
        }
        if (req.getAutoDownload() != null) {
            setting.setAutoDownload(flag(req.getAutoDownload(), "autoDownload"));
        }
    }

    /** 规范化音质代码；非法值报 30061。 */
    public static String quality(String raw) {
        String value = lower(raw, "defaultQuality");
        if (!QUALITIES.contains(value)) {
            throw invalid("defaultQuality 只能是 standard/high/lossless");
        }
        return value;
    }

    /** 7 档倍速校验，返回保留两位小数的值。 */
    public static BigDecimal rate(BigDecimal value) {
        for (BigDecimal allowed : RATES) {
            if (allowed.compareTo(value) == 0) {
                return allowed;
            }
        }
        throw invalid("playbackRate 只能是 0.50/0.75/1.00/1.25/1.50/1.75/2.00");
    }

    /**
     * 是否有有效 VIP。
     *
     * <p>本期没有支付体系（PRD 21 §2），VIP 只由运营配置 {@code t_user.vip_level}；
     * 到期时间非空且已过期视为非 VIP。
     */
    public static boolean isVip(User user) {
        if (user == null || user.getVipLevel() == null || user.getVipLevel() <= 0) {
            return false;
        }
        LocalDateTime expire = user.getVipExpireTime();
        return expire == null || expire.isAfter(LocalDateTime.now());
    }

    /** 登录用户默认 high，游客默认 standard（BR-21-001）。 */
    public static String defaultQuality(boolean loggedIn) {
        return loggedIn ? QUALITY_HIGH : QUALITY_STANDARD;
    }

    private static Integer flag(Integer value, String field) {
        if (value == null || (value != 0 && value != 1)) {
            throw invalid(field + " 只能是 0 或 1");
        }
        return value;
    }

    private static String lower(String raw, String field) {
        if (raw == null) {
            throw invalid(field + " 不能为空");
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static BizException invalid(String detail) {
        return new BizException(ResultCode.PLAY_SETTING_INVALID, detail);
    }
}
