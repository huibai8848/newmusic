package com.example.newmusic.tools;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 开发工具：生成 `sql/data.sql` 中种子账号的 BCrypt 密文。
 *
 * <p>运行：{@code mvn -q test -Dtest=PasswordHashGenerator}
 *
 * <p>不依赖 Spring 容器与数据库，可独立执行。**每次运行结果不同**（BCrypt 自带随机盐），
 * 但都能校验通过，直接复制输出粘贴到 data.sql 即可。
 */
class PasswordHashGenerator {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(10);

    @Test
    void printSeedPasswordHashes() {
        String[][] seeds = {
                {"admin", "admin123"},
                {"operator", "operator123"},
                {"demo", "demo123456"},
        };
        for (String[] seed : seeds) {
            String hash = ENCODER.encode(seed[1]);
            System.out.println("SEED_HASH " + seed[0] + " " + seed[1] + " " + hash
                    + " verify=" + ENCODER.matches(seed[1], hash));
        }
    }
}
