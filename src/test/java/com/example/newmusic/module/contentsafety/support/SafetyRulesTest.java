package com.example.newmusic.module.contentsafety.support;

import com.example.newmusic.module.contentsafety.service.MinorProtectionService;
import com.example.newmusic.module.contentsafety.service.UserRestrictionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 与时间/分值直接相关的纯函数规则。
 *
 * <p>青少年的时间窗跨零点是最容易写反的一段；信用分与处置等级的映射
 * 错了不会报错，只会悄悄少扣/多扣分，所以单独钉住。
 */
@DisplayName("内容安全纯规则")
class SafetyRulesTest {

    @Test
    @DisplayName("青少年禁用时段：22:00-08:00 跨零点")
    void disableWindowCrossMidnight() {
        LocalTime start = LocalTime.of(22, 0);
        LocalTime end = LocalTime.of(8, 0);
        assertTrue(MinorProtectionService.inDisableWindow(LocalTime.of(23, 30), start, end));
        assertTrue(MinorProtectionService.inDisableWindow(LocalTime.of(0, 1), start, end));
        assertTrue(MinorProtectionService.inDisableWindow(LocalTime.of(21, 59, 59).plusSeconds(1), start, end));
        assertFalse(MinorProtectionService.inDisableWindow(LocalTime.of(8, 0), start, end));
        assertFalse(MinorProtectionService.inDisableWindow(LocalTime.of(12, 0), start, end));
        assertFalse(MinorProtectionService.inDisableWindow(LocalTime.of(21, 59), start, end));
    }

    @Test
    @DisplayName("同日时间窗（08:00-22:00）按半开区间判断")
    void disableWindowSameDay() {
        LocalTime start = LocalTime.of(8, 0);
        LocalTime end = LocalTime.of(22, 0);
        assertTrue(MinorProtectionService.inDisableWindow(LocalTime.of(8, 0), start, end));
        assertTrue(MinorProtectionService.inDisableWindow(LocalTime.of(21, 59), start, end));
        assertFalse(MinorProtectionService.inDisableWindow(LocalTime.of(22, 0), start, end));
        assertFalse(MinorProtectionService.inDisableWindow(LocalTime.of(7, 0), start, end));
    }

    @Test
    @DisplayName("起止相同/为空 = 不限制")
    void disableWindowNoop() {
        assertFalse(MinorProtectionService.inDisableWindow(LocalTime.NOON, null, LocalTime.of(8, 0)));
        assertFalse(MinorProtectionService.inDisableWindow(LocalTime.NOON, LocalTime.of(8, 0), null));
        assertFalse(MinorProtectionService.inDisableWindow(LocalTime.NOON,
                LocalTime.of(8, 0), LocalTime.of(8, 0)));
    }

    @Test
    @DisplayName("信用分扣减：L1 -5 / L2 -10 / L3 -20 或 -30 / L4 -50 / L5 -100")
    void creditPointsByLevel() {
        assertEquals(5, UserRestrictionService.creditPointsForLevel(1, 0));
        assertEquals(10, UserRestrictionService.creditPointsForLevel(2, 7));
        assertEquals(20, UserRestrictionService.creditPointsForLevel(3, 3));
        assertEquals(30, UserRestrictionService.creditPointsForLevel(3, 7));
        assertEquals(50, UserRestrictionService.creditPointsForLevel(4, 30));
        assertEquals(100, UserRestrictionService.creditPointsForLevel(5, null));
        assertEquals(5, UserRestrictionService.creditPointsForLevel(null, null));
    }
}
