package com.example.newmusic.common.config;

import com.example.newmusic.module.ops.metric.OpsRequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;
import java.nio.file.Paths;

/**
 * Web 层配置。
 *
 * <p>CORS：开发态允许 Vite Dev Server（5173）跨域直连；生产由 Nginx 同源反代。
 *
 * <p>静态媒体（模块 07）：{@code /media/**} 映射到本地目录，供演示音频与封面使用。
 * 之所以放文件系统而不是 {@code classpath:/static}：音频是二进制大文件，不该打进 jar，
 * 也不该进版本库（{@code media/} 已在根 .gitignore 中）。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** 演示媒体根目录（相对后端进程工作目录，即仓库根） */
    private final String mediaDir;
    private final OpsRequestInterceptor opsRequestInterceptor;

    public WebMvcConfig(@Value("${newmusic.media.dir:./media}") String mediaDir,
                        OpsRequestInterceptor opsRequestInterceptor) {
        this.mediaDir = mediaDir;
        this.opsRequestInterceptor = opsRequestInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 模块19：限流/降级/指标/trace 的统一入口（内部自行排除 actuator/media/预检）
        registry.addInterceptor(opsRequestInterceptor).addPathPatterns("/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                // degraded 是模块 06 的降级标记（D-48）：响应体里也有一份，
                // 但既然要写响应头，就必须暴露出去——否则跨域下浏览器会把它过滤掉，
                // 「两个都做」就退化成「只有体里那份」，白写一个头
                .exposedHeaders("X-Trace-Id", "degraded", "X-Degrade-Level", "Retry-After")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 用绝对路径：Location 解析相对路径时会以进程工作目录为基准，
        // 但 Spring 的 file: 前缀对相对路径的处理在不同启动方式（IDE / mvn / java -jar）下不一致，
        // 先归一化成绝对路径可以避免「IDE 能播、命令行不能播」这类问题。
        String absolute = Paths.get(mediaDir).toAbsolutePath().normalize().toString();
        registry.addResourceHandler("/media/**")
                .addResourceLocations("file:" + absolute + File.separator);
    }
}
