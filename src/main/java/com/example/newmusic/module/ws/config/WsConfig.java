package com.example.newmusic.module.ws.config;

import com.example.newmusic.module.ws.handler.MusicWebSocketHandler;
import com.example.newmusic.module.ws.support.WsHandshakeInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket 装配（PRD 13 §4.2）。
 *
 * <p>三点与 PRD 示例一致、但值得记下来的地方：
 * <ul>
 *   <li>端点只有 {@code /ws} 一个；房间/用户是**频道**概念，不是不同的 URL；</li>
 *   <li>鉴权在 {@link WsHandshakeInterceptor}，不在 Spring Security 过滤器链里——
 *       浏览器 WebSocket 无法自定义 Authorization 头，token 只能走 query；</li>
 *   <li>入站消息上限交给容器（{@link ServletServerContainerFactoryBean}），
 *       出站前的长度检查只是兜底，避免超大帧把 Tomcat 缓冲区打满。</li>
 * </ul>
 */
@Configuration
@EnableWebSocket
@EnableAsync
public class WsConfig implements WebSocketConfigurer {

    private final MusicWebSocketHandler handler;
    private final WsHandshakeInterceptor handshakeInterceptor;
    private final WsProperties properties;

    public WsConfig(MusicWebSocketHandler handler,
                    WsHandshakeInterceptor handshakeInterceptor,
                    WsProperties properties) {
        this.handler = handler;
        this.handshakeInterceptor = handshakeInterceptor;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns(properties.originPatterns());
    }

    /**
     * 容器级消息大小与空闲超时。
     *
     * <p>空闲超时设为 0：真正的 90s 判定由应用层心跳扫描负责（BR-13-007）。
     * 让容器也参与会得到两个互不知情、阈值还不一样的计时器，排障时很难说清是谁关的连接。
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(properties.getMaxMessageSize());
        container.setMaxBinaryMessageBufferSize(properties.getMaxMessageSize());
        container.setMaxSessionIdleTimeout(0L);
        return container;
    }
}
