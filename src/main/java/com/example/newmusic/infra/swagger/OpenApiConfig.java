package com.example.newmusic.infra.swagger;

import com.example.newmusic.infra.security.JwtProperties;
import io.swagger.annotations.ApiOperation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.service.ApiKey;
import springfox.documentation.service.AuthorizationScope;
import springfox.documentation.service.SecurityReference;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spi.service.contexts.SecurityContext;
import springfox.documentation.oas.annotations.EnableOpenApi;
import springfox.documentation.spring.web.plugins.Docket;

import java.util.Collections;
import java.util.List;

/**
 * Knife4j / Springfox 接口文档（《10-模块01》T014）。
 *
 * <p>访问：{@code http://localhost:8081/doc.html}。
 *
 * <p>注意：Springfox 3.0.0 在 Boot 2.6 下必须把 {@code spring.mvc.pathmatch.matching-strategy}
 * 设为 {@code ant_path_matcher}，否则启动时 documentationPluginsBootstrapper 会失败。
 */
@Configuration
@EnableOpenApi
public class OpenApiConfig {

    private static final String SECURITY_SCHEME = "Authorization";

    private final JwtProperties jwtProperties;

    public OpenApiConfig(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @Bean
    public Docket apiDocket() {
        return new Docket(DocumentationType.OAS_30)
                .apiInfo(apiInfo())
                .select()
                // 只收录带 @ApiOperation 的接口方法，避免内部/工具类被扫描进文档
                .apis(RequestHandlerSelectors.withMethodAnnotation(ApiOperation.class))
                .paths(PathSelectors.ant("/api/**"))
                .build()
                .securitySchemes(Collections.singletonList(
                        new ApiKey(SECURITY_SCHEME, "Authorization", "header")))
                .securityContexts(Collections.singletonList(securityContext()));
    }

    /** 文档页右上角「Authorize」填入 {@code Bearer <accessToken>} 后即可调试受保护接口。 */
    private SecurityContext securityContext() {
        return SecurityContext.builder()
                .securityReferences(defaultAuth())
                .operationSelector(o -> true)
                .build();
    }

    private List<SecurityReference> defaultAuth() {
        AuthorizationScope scope = new AuthorizationScope("global", "accessEverything");
        return Collections.singletonList(new SecurityReference(SECURITY_SCHEME,
                new AuthorizationScope[]{scope}));
    }

    private ApiInfo apiInfo() {
        return new ApiInfoBuilder()
                .title("MusicLive 在线音乐社交平台 API")
                .description("MVP 接口文档（模块 01 曲库 CRUD / 02 前端骨架 / 07 播放器与歌词）。"
                        + "统一响应 R<T>，HTTP 200 除 401/403；ID 为雪花算法，序列化为字符串。")
                .version("v1.0-MVP")
                .termsOfServiceUrl("")
                .license("Internal")
                .build();
    }
}
