package com.example.newmusic.module.admin.support;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 图形验证码：4 位字符，PNG 存 Redis 2 分钟，一次使用即删除。
 *
 * <p><b>回显开关</b>：{@code newmusic.admin.captcha-echo=true} 时响应体额外返回
 * {@code code}，仅用于无浏览器的自动化验收。默认值在 application.yml 中跟随环境变量
 * {@code ADMIN_CAPTCHA_ECHO}，生产必须显式置 false（启动日志会 WARN）。
 */
@Slf4j
@Service
public class AdminCaptchaService {

    private static final String CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int LENGTH = 4;
    private static final Duration TTL = Duration.ofMinutes(2);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RedisOps redisOps;
    private final boolean echo;

    public AdminCaptchaService(RedisOps redisOps,
                               @Value("${newmusic.admin.captcha-echo:true}") boolean echo) {
        this.redisOps = redisOps;
        this.echo = echo;
        if (echo) {
            log.warn("后台验证码回显已开启（ADMIN_CAPTCHA_ECHO=true）。仅限本机演示/验收，生产必须关闭");
        }
    }

    public Captcha create() {
        if (!redisOps.isAvailable()) {
            throw new BizException(ResultCode.CACHE_ERROR, "验证码服务暂时不可用");
        }
        String code = randomCode();
        String captchaId = UUID.randomUUID().toString().replace("-", "");
        redisOps.set(RedisKeys.adminCaptcha(captchaId), code, TTL);
        Captcha captcha = new Captcha();
        captcha.setCaptchaId(captchaId);
        captcha.setImage("data:image/png;base64," + render(code));
        captcha.setExpiresIn(TTL.getSeconds());
        if (echo) {
            captcha.setCode(code);
        }
        return captcha;
    }

    /** 校验并消费。验证码错误与过期返回同一个码，不泄露区别。 */
    public void verifyAndConsume(String captchaId, String code) {
        if (captchaId == null || code == null) {
            throw new BizException(ResultCode.ADMIN_CAPTCHA_ERROR);
        }
        String key = RedisKeys.adminCaptcha(captchaId);
        Optional<String> expected = redisOps.get(key);
        // 无论对错都删：错误一次即作废，避免对同一张图暴力尝试
        redisOps.delete(key);
        if (!expected.isPresent()
                || !expected.get().equalsIgnoreCase(code.trim().toUpperCase(Locale.ROOT))) {
            throw new BizException(ResultCode.ADMIN_CAPTCHA_ERROR);
        }
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private String render(String code) {
        int width = 120;
        int height = 44;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(245, 247, 250));
            g.fillRect(0, 0, width, height);

            // 干扰线
            g.setStroke(new BasicStroke(1.2f));
            for (int i = 0; i < 6; i++) {
                g.setColor(new Color(150 + RANDOM.nextInt(80), 150 + RANDOM.nextInt(80),
                        150 + RANDOM.nextInt(80)));
                g.drawLine(RANDOM.nextInt(width), RANDOM.nextInt(height),
                        RANDOM.nextInt(width), RANDOM.nextInt(height));
            }
            // 字符：轻微旋转 + 随机颜色
            for (int i = 0; i < code.length(); i++) {
                double angle = (RANDOM.nextDouble() - 0.5) * 0.35;
                g.setColor(new Color(30 + RANDOM.nextInt(90), 30 + RANDOM.nextInt(90),
                        30 + RANDOM.nextInt(90)));
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
                g.rotate(angle, 22 + i * 25, 30);
                g.drawString(String.valueOf(code.charAt(i)), 14 + i * 25, 33);
                g.rotate(-angle, 22 + i * 25, 30);
            }
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new BizException(ResultCode.SYSTEM_BUSY, "验证码生成失败");
        }
    }

    /** 验证码响应。{@code code} 仅在回显开关打开时非空。 */
    @lombok.Data
    public static class Captcha {
        private String captchaId;
        private String image;
        private long expiresIn;
        private String code;
    }
}
