package com.example.newmusic;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// WebSocket 端点需要真实的 ServletContainer；MOCK 环境没有 javax.websocket 属性，
// 上下文会在 ServletServerContainerFactoryBean 处启动失败。用随机端口跑完整容器。
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NewmusicApplicationTests {

    @Test
    void contextLoads() {
    }

}
