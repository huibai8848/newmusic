package com.example.newmusic.module.play.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 播放地址签名器单测（模块 07 T718 / PRD BR-16-010 / AC-16-119）。
 *
 * <p>纯单元测试，不启 Spring 上下文——{@link PlayUrlSigner} 的唯一依赖是构造参数，
 * {@code verify} 的时间戳也是显式入参，所以过期场景不用靠 {@code Thread.sleep} 构造。
 *
 * <p>故意不写成 {@code @SpringBootTest}：那会拉起数据源，本机没跑过 {@code schema.sql}
 * 时整个类直接报错，把「签名对不对」和「库连不连得上」两件事搅在一起。
 *
 * <p><b>不用 {@code @Nested} 分组</b>：本项目的 surefire 会把外层类的用例记到
 * {@code $Nested} 的报表里，外层类报表显示 {@code Tests run: 0}——照着报表排查的人
 * 会以为这些用例根本没跑。扁平写法则每个用例都落在本类报表下，不会有歧义。
 */
@DisplayName("PlayUrlSigner 播放地址签名")
class PlayUrlSignerTest {

    private static final String SECRET = "test-secret";
    private static final String PATH = "/media/audio/demo-01.wav";
    private static final long EXPIRES = 1_800_000_000L;
    private static final long USER_ID = 1001L;

    private final PlayUrlSigner signer = new PlayUrlSigner(SECRET, 7200);

    // ------------------------------------------------------- 与实现无关的期望值（跨语言金标准）
    /**
     * 下面三条是 <b>用 Node 的 crypto 独立算出来的</b>，不是本实现跑出来的：
     * <pre>
     * node -e "require('crypto').createHmac('sha256','test-secret')
     *            .update('/media/audio/demo-01.wav|1800000000|1001').digest('hex')"
     * </pre>
     * 校验的才是「payload 拼接格式 + HMAC 口径」本身，而不是 Java 实现自己跟自己一致。
     * 换语言实现（CDN / Nginx 的 Lua 校验端）必须算出同一个值，否则线上全是 403。
     */
    private static final String GOLDEN_USER = "62f6e333a92c1dd0f7b9e94438999fc583b24ec9cc0ad3065ecbcb8e33e59032";
    private static final String GOLDEN_GUEST = "675329a6dddb677514e21cc960d6df3ecbef5f9db3ce4d29af34db8136dc15ae";
    private static final String GOLDEN_EXPIRES_PLUS_ONE = "0d6a5eb17ea0453ac8450c97bb55ef48919d5db4698a7340e1f99ff9b40551a9";

    // ------------------------------------------------------------------ 签名生成

    @Test
    @DisplayName("签名与 PRD 公式逐字一致（跨语言期望值）")
    void matchesPrdFormula() {
        assertEquals(GOLDEN_USER, signer.sign(PATH, EXPIRES, USER_ID));
    }

    @Test
    @DisplayName("游客与 userId=0 签名相同：payload 段数恒定")
    void guestIsSentinelZero() {
        assertEquals(GOLDEN_GUEST, signer.sign(PATH, EXPIRES, null));
        assertEquals(GOLDEN_GUEST, signer.sign(PATH, EXPIRES, PlayUrlSigner.GUEST_ID));
        // 反过来：段数若不恒定（写成空串或干脆省掉一段），游客与真实账号就会撞在一起
        assertNotEquals(GOLDEN_GUEST, signer.sign(PATH, EXPIRES, 1L));
    }

    @Test
    @DisplayName("确定性：同输入同输出，且为 64 位小写十六进制")
    void deterministicAndLowerHex() {
        String first = signer.sign(PATH, EXPIRES, USER_ID);
        String second = signer.sign(PATH, EXPIRES, USER_ID);
        assertEquals(first, second, "同输入两次签名必须完全一致");
        assertTrue(first.matches("[0-9a-f]{64}"), "应为 SHA-256 长度的小写十六进制，实际=" + first);
    }

    @Test
    @DisplayName("验签对大小写不敏感（有意宽松，避免客户端改写签名时误伤）")
    void verifyIsCaseInsensitive() {
        String sign = signer.sign(PATH, EXPIRES, USER_ID);
        assertTrue(signer.verify(PATH, EXPIRES, USER_ID, sign.toUpperCase(Locale.ROOT), EXPIRES - 1));
    }

    // ------------------------------------------------------------------ 篡改检测

    @Test
    @DisplayName("原样签名通过")
    void untouchedPasses() {
        String sign = signer.sign(PATH, EXPIRES, USER_ID);
        assertTrue(signer.verify(PATH, EXPIRES, USER_ID, sign, EXPIRES - 1));
    }

    @Test
    @DisplayName("改 userId 复用签名 → 失败（地址不可跨账号）")
    void userIdTampered() {
        String sign = signer.sign(PATH, EXPIRES, USER_ID);
        assertFalse(signer.verify(PATH, EXPIRES, USER_ID + 1, sign, EXPIRES - 1));
        assertFalse(signer.verify(PATH, EXPIRES, anotherAccountId(), sign, EXPIRES - 1));
    }

    @Test
    @DisplayName("更长的 expires 配旧签名 → 失败（防续期）")
    void expiresTampered() {
        String sign = signer.sign(PATH, EXPIRES, USER_ID);
        assertFalse(signer.verify(PATH, EXPIRES + 1, USER_ID, sign, EXPIRES - 1));
        assertEquals(GOLDEN_EXPIRES_PLUS_ONE, signer.sign(PATH, EXPIRES + 1, USER_ID),
                "expires 必须真的进 payload，而不是只做本地比较");
    }

    @Test
    @DisplayName("改 path 复用签名 → 失败（防换歌）")
    void pathTampered() {
        String sign = signer.sign(PATH, EXPIRES, USER_ID);
        assertFalse(signer.verify("/media/audio/demo-02.wav", EXPIRES, USER_ID, sign, EXPIRES - 1));
    }

    @Test
    @DisplayName("签名缺失 / 空串 / 长度不对 → 失败且不抛异常")
    void malformedSign() {
        assertFalse(signer.verify(PATH, EXPIRES, USER_ID, null, EXPIRES - 1));
        assertFalse(signer.verify(PATH, EXPIRES, USER_ID, "", EXPIRES - 1));
        assertFalse(signer.verify(PATH, EXPIRES, USER_ID, "deadbeef", EXPIRES - 1));
        // 长度对但内容错：走常量时间比较那条路，而不是长度短路
        assertFalse(signer.verify(PATH, EXPIRES, USER_ID, repeat('z', 64), EXPIRES - 1));
    }

    @Test
    @DisplayName("换 secret 的签名器互相验不过")
    void differentSecretFails() {
        PlayUrlSigner other = new PlayUrlSigner("another-secret", 7200);
        String sign = other.sign(PATH, EXPIRES, USER_ID);
        assertFalse(signer.verify(PATH, EXPIRES, USER_ID, sign, EXPIRES - 1));
    }

    // ------------------------------------------------------------------ 有效期

    @Test
    @DisplayName("到期前有效")
    void beforeExpiry() {
        String sign = signer.sign(PATH, EXPIRES, USER_ID);
        assertTrue(signer.verify(PATH, EXPIRES, USER_ID, sign, EXPIRES - 1));
    }

    @Test
    @DisplayName("过期后失败，且签名本身是对的（证明是有效期拦下的）")
    void afterExpiry() {
        String sign = signer.sign(PATH, EXPIRES, USER_ID);
        assertEquals(GOLDEN_USER, sign, "前提：签名是对的");
        assertFalse(signer.verify(PATH, EXPIRES, USER_ID, sign, EXPIRES + 1));
    }

    /** 边界取「相等仍有效」（实现为 {@code now > expires} 才拒）。宽松一秒，不为难客户端 */
    @Test
    @DisplayName("now == expires 仍有效（边界）")
    void exactlyAtExpiry() {
        String sign = signer.sign(PATH, EXPIRES, USER_ID);
        assertTrue(signer.verify(PATH, EXPIRES, USER_ID, sign, EXPIRES));
    }

    @Test
    @DisplayName("默认 TTL 为 7200 秒（2 小时），且可配置覆盖")
    void ttl() {
        assertEquals(7200, signer.ttlSeconds());
        assertEquals(60, new PlayUrlSigner(SECRET, 60).ttlSeconds());
    }

    // ------------------------------------------------------------------ 辅助

    /** 一个与 {@link #USER_ID} 取值相差很远的真实账号 ID（防"只在 +1 上碰巧通过"） */
    private static Long anotherAccountId() {
        return 900_000_000_000_000_002L;
    }

    /** JDK 8 没有 {@code String.repeat} */
    private static String repeat(char c, int times) {
        char[] buf = new char[times];
        Arrays.fill(buf, c);
        return new String(buf);
    }
}
