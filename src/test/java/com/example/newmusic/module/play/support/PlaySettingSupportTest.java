package com.example.newmusic.module.play.support;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.module.play.dto.PlaySettingReq;
import com.example.newmusic.module.play.entity.UserPlaySetting;
import com.example.newmusic.module.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 播放设置取值校验单测（模块 12 FR-21-008 / AC-21-115）。
 *
 * <p>纯单元测试：不启 Spring、不连库。校验逻辑是「用户选择」与「实际生效」之间
 * 唯一的防线，非法值必须报 30061 而不是悄悄回退。
 */
@DisplayName("PlaySettingSupport 播放设置校验")
class PlaySettingSupportTest {

    private static final int INVALID = 30061;

    @Test
    @DisplayName("默认值：登录用户 high、自动降质开、倍速 1.00、音量不参与")
    void defaults() {
        UserPlaySetting s = UserPlaySetting.defaults(42L);
        assertEquals(Long.valueOf(42L), s.getUserId());
        assertEquals("high", s.getDefaultQuality());
        assertEquals("loop", s.getPlayMode());
        assertEquals(Integer.valueOf(1), s.getAutoQuality());
        assertEquals(Integer.valueOf(0), s.getDataSaver());
        assertEquals(0, new BigDecimal("1.00").compareTo(s.getPlaybackRate()));
        assertEquals(Integer.valueOf(0), s.getLyricOffset());
        // 音量不在设置表里：BR-21-076
        assertFalse(hasField("volume"));
    }

    @Test
    @DisplayName("局部更新：只改传入字段，其余保持原值")
    void partialUpdateKeepsOthers() {
        UserPlaySetting s = UserPlaySetting.defaults(42L);
        PlaySettingReq req = new PlaySettingReq();
        req.setDefaultQuality("  LOSSLESS ");
        req.setPlaybackRate(new BigDecimal("2"));
        PlaySettingSupport.apply(s, req);

        assertEquals("lossless", s.getDefaultQuality());
        assertEquals(0, new BigDecimal("2.00").compareTo(s.getPlaybackRate()));
        assertEquals("loop", s.getPlayMode(), "没传的字段不能被顺手清空");
        assertEquals(Integer.valueOf(1), s.getAutoQuality());
    }

    @Test
    @DisplayName("7 档倍速全部接受，且规范化到两位小数")
    void allSevenRatesAccepted() {
        for (BigDecimal rate : PlaySettingSupport.RATES) {
            UserPlaySetting s = UserPlaySetting.defaults(1L);
            PlaySettingReq req = new PlaySettingReq();
            req.setPlaybackRate(rate);
            PlaySettingSupport.apply(s, req);
            assertEquals(0, rate.compareTo(s.getPlaybackRate()));
            assertEquals(2, s.getPlaybackRate().scale());
        }
        assertEquals(7, PlaySettingSupport.RATES.size(), "PRD FR-21-002 恰好 7 档");
        assertEquals(0, new BigDecimal("0.50").compareTo(PlaySettingSupport.RATES.get(0)));
        assertEquals(0, new BigDecimal("2.00").compareTo(PlaySettingSupport.RATES.get(6)));
    }

    @Test
    @DisplayName("非法音质 / 播放模式 / 倍速 / 开关 / 偏移 / 音效全部报 30061")
    void invalidValuesRejected() {
        assertCode(() -> {
            PlaySettingReq req = new PlaySettingReq();
            req.setDefaultQuality("flac");
            PlaySettingSupport.apply(UserPlaySetting.defaults(1L), req);
        });
        assertCode(() -> {
            PlaySettingReq req = new PlaySettingReq();
            req.setPlayMode("random");
            PlaySettingSupport.apply(UserPlaySetting.defaults(1L), req);
        });
        assertCode(() -> {
            PlaySettingReq req = new PlaySettingReq();
            req.setPlaybackRate(new BigDecimal("2.50"));
            PlaySettingSupport.apply(UserPlaySetting.defaults(1L), req);
        });
        assertCode(() -> {
            PlaySettingReq req = new PlaySettingReq();
            req.setAutoQuality(2);
            PlaySettingSupport.apply(UserPlaySetting.defaults(1L), req);
        });
        assertCode(() -> {
            PlaySettingReq req = new PlaySettingReq();
            req.setLyricOffset(5001);
            PlaySettingSupport.apply(UserPlaySetting.defaults(1L), req);
        });
        assertCode(() -> {
            PlaySettingReq req = new PlaySettingReq();
            req.setEqualizerPreset("metal");
            PlaySettingSupport.apply(UserPlaySetting.defaults(1L), req);
        });
        assertCode(() -> {
            PlaySettingReq req = new PlaySettingReq();
            req.setEqualizerBands("60:+3");
            PlaySettingSupport.apply(UserPlaySetting.defaults(1L), req);
        });
    }

    @Test
    @DisplayName("非法值不修改原对象：校验发生在赋值之前（或同字段先校验后赋值）")
    void invalidDoesNotMutate() {
        UserPlaySetting s = UserPlaySetting.defaults(1L);
        PlaySettingReq req = new PlaySettingReq();
        req.setPlayMode("order");
        req.setPlaybackRate(new BigDecimal("3"));
        assertThrows(BizException.class, () -> PlaySettingSupport.apply(s, req));
        assertEquals("order", s.getPlayMode(), "前面已通过校验的字段可以生效，但非法的倍速不能写入");
        assertEquals(0, BigDecimal.ONE.compareTo(s.getPlaybackRate()));
    }

    @Test
    @DisplayName("VIP 判定：null/0/过期都算非 VIP；无到期时间视为有效")
    void vipRule() {
        assertFalse(PlaySettingSupport.isVip(null));
        assertFalse(PlaySettingSupport.isVip(user(0, null)));
        assertFalse(PlaySettingSupport.isVip(user(-1, null)));
        assertFalse(PlaySettingSupport.isVip(user(1, LocalDateTime.now().minusDays(1))));
        assertTrue(PlaySettingSupport.isVip(user(1, null)));
        assertTrue(PlaySettingSupport.isVip(user(1, LocalDateTime.now().plusDays(1))));
    }

    @Test
    @DisplayName("默认音质：登录 high / 游客 standard")
    void defaultQuality() {
        assertEquals("high", PlaySettingSupport.defaultQuality(true));
        assertEquals("standard", PlaySettingSupport.defaultQuality(false));
    }

    @Test
    @DisplayName("歌词偏移边界 ±5000 可接受")
    void lyricOffsetBoundary() {
        PlaySettingReq req = new PlaySettingReq();
        req.setLyricOffset(-5000);
        UserPlaySetting s = UserPlaySetting.defaults(1L);
        PlaySettingSupport.apply(s, req);
        assertEquals(Integer.valueOf(-5000), s.getLyricOffset());

        req.setLyricOffset(5000);
        PlaySettingSupport.apply(s, req);
        assertEquals(Integer.valueOf(5000), s.getLyricOffset());
    }

    @Test
    @DisplayName("EQ 频段为空串时清空；合法 JSON 数组保留")
    void equalizerBands() {
        UserPlaySetting s = UserPlaySetting.defaults(1L);
        PlaySettingReq req = new PlaySettingReq();
        req.setEqualizerBands("[0,0,0,0,0]");
        PlaySettingSupport.apply(s, req);
        assertEquals("[0,0,0,0,0]", s.getEqualizerBands());

        req.setEqualizerBands("   ");
        PlaySettingSupport.apply(s, req);
        assertNull(s.getEqualizerBands());
    }

    // ------------------------------------------------------------ helpers

    private static void assertCode(Runnable action) {
        BizException e = assertThrows(BizException.class, action::run);
        assertEquals(INVALID, e.getCode(), "非法设置必须报 30061，实际 message=" + e.getMessage());
    }

    private static User user(int vipLevel, LocalDateTime expire) {
        User u = new User();
        u.setVipLevel(vipLevel);
        u.setVipExpireTime(expire);
        return u;
    }

    private static boolean hasField(String name) {
        Set<String> fields = new HashSet<>();
        for (java.lang.reflect.Field f : UserPlaySetting.class.getDeclaredFields()) {
            fields.add(f.getName());
        }
        return fields.contains(name);
    }
}
