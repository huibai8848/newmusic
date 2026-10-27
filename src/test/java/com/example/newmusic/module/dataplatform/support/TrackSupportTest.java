package com.example.newmusic.module.dataplatform.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 埋点采集的纯函数单测（模块 17 FR-26-001 / BR-26-002 / BR-26-005）。
 *
 * <p>只测不依赖 Spring、Redis、MySQL 的三个 support 类：事件名规范、脱敏、IP 归属。
 * 这条链路的其余部分（幂等、限流、采样）依赖 Redis，放在 verify-17 里做运行时验收。
 */
@DisplayName("埋点采集 support 纯函数")
class TrackSupportTest {

    // ------------------------------------------------------------ 事件名

    @Test
    @DisplayName("合法事件名：2~6 段全小写蛇形")
    void validEventNames() {
        assertNull(EventNameValidator.validate("player_song_play"));
        assertNull(EventNameValidator.validate("user_register_success"));
        assertNull(EventNameValidator.validate("a_b_c_d_e_f"));
        // 事件目录与 PRD 漏斗 SQL 用的两段事件必须能通过，否则 page_view 全进脏数据
        assertNull(EventNameValidator.validate("page_view"));
        assertNull(EventNameValidator.validate("search_submit"));
        assertTrue(EventNameValidator.isValid("data_quality_check"));
    }

    @Test
    @DisplayName("非法事件名：段数、大小写、首字符、超长")
    void invalidEventNames() {
        assertEquals("事件名为空", EventNameValidator.validate("   "));
        assertTrue(EventNameValidator.validate("pageview").contains("规范"));
        assertTrue(EventNameValidator.validate("Player_song_play").contains("规范"));
        assertTrue(EventNameValidator.validate("1player_song_play").contains("规范"));
        assertTrue(EventNameValidator.validate("a_b_c_d_e_f_g").contains("规范"));
        assertTrue(EventNameValidator.validate("a__b").contains("规范"));

        String tooLong = "a_" + repeat('b', 63) + "_c";
        assertTrue(EventNameValidator.validate(tooLong).contains("超过"));
    }

    // ------------------------------------------------------------ 脱敏

    @Test
    @DisplayName("密码/token 类整键删除，连密文都不留")
    void dropsSecretKeys() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("password", "P@ssw0rd");
        raw.put("accessToken", "eyJhbGciOi");
        raw.put("credit_card_cvv", "123");
        raw.put("song_id", "1001");

        Map<String, Object> clean = TrackSanitizer.sanitize(raw);

        assertFalse(clean.containsKey("password"));
        assertFalse(clean.containsKey("accessToken"));
        assertFalse(clean.containsKey("credit_card_cvv"));
        assertEquals("1001", clean.get("song_id"));
    }

    @Test
    @DisplayName("手机号/邮箱按键名与整值两种方式掩码")
    void masksPhoneAndEmail() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("phone", "13800138000");
        raw.put("contact_email", "alice@example.com");
        // 搜索词里直接输入手机号：键名不敏感，但整值敏感
        raw.put("keyword", "13800138000");

        Map<String, Object> clean = TrackSanitizer.sanitize(raw);

        assertEquals("138****8000", clean.get("phone"));
        assertEquals("a***@example.com", clean.get("contact_email"));
        assertEquals("138****8000", clean.get("keyword"));
    }

    @Test
    @DisplayName("嵌套结构递归脱敏，普通文本不被误伤")
    void sanitizesNested() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("token", "secret");
        inner.put("email", "bob@test.cn");

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("context", inner);
        raw.put("title", "晴天");
        raw.put("lyric", "故事的小黄花 13800138000");

        @SuppressWarnings("unchecked")
        Map<String, Object> clean = TrackSanitizer.sanitize(raw);

        @SuppressWarnings("unchecked")
        Map<String, Object> cleanInner = (Map<String, Object>) clean.get("context");
        assertFalse(cleanInner.containsKey("token"));
        assertEquals("b***@test.cn", cleanInner.get("email"));
        assertEquals("晴天", clean.get("title"));
        // 手机号藏在普通文本里时不做部分替换，避免把歌词改得面目全非
        assertEquals("故事的小黄花 13800138000", clean.get("lyric"));
    }

    @Test
    @DisplayName("脱敏不修改入参")
    void doesNotMutateInput() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("password", "keep-me");
        raw.put("phone", "13800138000");

        TrackSanitizer.sanitize(raw);

        assertEquals("keep-me", raw.get("password"));
        assertEquals("13800138000", raw.get("phone"));
    }

    @Test
    @DisplayName("空属性返回空 Map 而不是 null")
    void emptyProperties() {
        assertTrue(TrackSanitizer.sanitize(null).isEmpty());
        assertTrue(TrackSanitizer.sanitize(new LinkedHashMap<>()).isEmpty());
    }

    // ------------------------------------------------------------ IP 归属

    @Test
    @DisplayName("内网/本机 IP 归为「内网」，空值归为「未知」")
    void localIpHasNoCity() {
        for (String ip : Arrays.asList("127.0.0.1", "::1", "10.1.2.3", "192.168.1.5", "172.16.0.1")) {
            assertEquals("内网", IpRegionSupport.resolve(ip), "IP：" + ip);
        }
        assertEquals("未知", IpRegionSupport.resolve(null));
        assertEquals("未知", IpRegionSupport.resolve("  "));
    }

    @Test
    @DisplayName("没有 IP 库时公网 IP 返回「未知」且稳定，不猜省份")
    void publicIpIsUnknownButStable() {
        String first = IpRegionSupport.resolve("223.5.5.5");
        assertEquals("未知", first);
        assertEquals(first, IpRegionSupport.resolve("223.5.5.5"));
    }

    private static String repeat(char ch, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(ch);
        }
        return builder.toString();
    }
}
