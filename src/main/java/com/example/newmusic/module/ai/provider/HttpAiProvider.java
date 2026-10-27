package com.example.newmusic.module.ai.provider;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.ai.config.AiProperties;
import com.example.newmusic.module.ai.gateway.AiRequest;
import com.example.newmusic.module.ai.gateway.ChatMessage;
import com.example.newmusic.module.ops.breaker.CircuitBreakerService;
import com.example.newmusic.module.ops.chaos.ChaosState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容的 HTTP Provider。
 *
 * <p>只用 {@code /chat/completions} 一个端点，凡是兼容 OpenAI 协议的服务（通义、DeepSeek、
 * 自建 vLLM 等）都能直接接。不引入各家 SDK：本模块只需要一次 POST，引入 SDK 会带来
 * 版本冲突与新的密钥管理面。
 *
 * <p><b>密钥只来自环境变量 {@code AI_API_KEY}。</b>Base URL、模型名可写配置，密钥不行。
 * 日志里绝不打印请求头；错误响应体截断到 200 字符，避免上游把密钥回显到错误信息里。
 *
 * <p><b>超时在这里再收一次。</b>{@code SimpleClientHttpRequestFactory} 的 readTimeout
 * 是本次请求的硬上限，即使场景配置更宽，网关也会先抛超时并走降级。
 */
@Slf4j
@Component
public class HttpAiProvider implements AiProvider {

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final CircuitBreakerService circuitBreakerService;
    private final ChaosState chaosState;

    public HttpAiProvider(AiProperties properties, ObjectMapper objectMapper,
                          CircuitBreakerService circuitBreakerService, ChaosState chaosState) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.circuitBreakerService = circuitBreakerService;
        this.chaosState = chaosState;
    }

    @Override
    public String name() {
        return "http";
    }

    @Override
    public AiProviderResult chat(AiRequest request, String model, long timeoutMs) {
        try {
            // 模块19：AI 是全站最脆弱的外部依赖，熔断打开时不再打上游（AC-28-002-1）
            return circuitBreakerService.call(CircuitBreakerService.LLM,
                    () -> doChat(request, model, timeoutMs));
        } catch (CircuitBreakerService.OpenException e) {
            log.warn("AI 熔断器打开，直接降级 model={}", model);
            throw new BizException(ResultCode.AI_UNAVAILABLE,
                    "AI 服务暂时不可用（熔断保护中），请稍后再试");
        }
    }

    private AiProviderResult doChat(AiRequest request, String model, long timeoutMs) {
        // 混沌演练：按爆炸半径模拟上游超时，验证熔断/降级不是纸面配置
        if (chaosState.shouldInject(ChaosState.FAULT_AI_TIMEOUT)) {
            log.warn("AI 超时混沌注入 model={}", model);
            throw new BizException(ResultCode.AI_UNAVAILABLE, "AI 服务响应超时，请稍后再试");
        }
        String apiKey = resolveApiKey();
        String baseUrl = properties.getHttp().getBaseUrl();
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(baseUrl)) {
            throw new BizException(ResultCode.AI_UNAVAILABLE, "AI 服务未配置，请联系管理员");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages(request));
        body.put("temperature", request.getTemperature() == null ? 0.7 : request.getTemperature());
        body.put("max_tokens", request.getMaxTokens() == null ? 512 : request.getMaxTokens());
        if (Boolean.TRUE.equals(request.getJsonMode())) {
            body.put("response_format", singleton("type", "json_object"));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        int readTimeout = (int) (timeoutMs > 0 ? timeoutMs : properties.getHttp().getReadTimeoutMs());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getHttp().getConnectTimeoutMs());
        factory.setReadTimeout(readTimeout);
        RestTemplate restTemplate = new RestTemplate(factory);

        String url = trimTrailingSlash(baseUrl) + "/chat/completions";
        long started = System.currentTimeMillis();
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            return parse(response.getBody(), model);
        } catch (ResourceAccessException e) {
            log.warn("AI 上游超时 model={} elapsed={}ms cause={}",
                    model, System.currentTimeMillis() - started, e.toString());
            throw new BizException(ResultCode.AI_UNAVAILABLE, "AI 服务响应超时，请稍后再试", e);
        } catch (HttpStatusCodeException e) {
            String detail = e.getResponseBodyAsString();
            if (detail != null && detail.length() > 200) {
                detail = detail.substring(0, 200);
            }
            log.warn("AI 上游返回错误 status={} model={} body={}", e.getRawStatusCode(), model, detail);
            throw new BizException(ResultCode.AI_UNAVAILABLE, "AI 服务暂时不可用，请稍后再试", e);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("AI 上游调用失败 model={} cause={}", model, e.toString());
            throw new BizException(ResultCode.AI_UNAVAILABLE, "AI 服务暂时不可用，请稍后再试", e);
        }
    }

    private List<Map<String, String>> messages(AiRequest request) {
        List<Map<String, String>> list = new ArrayList<>();
        if (StringUtils.hasText(request.getSystemPrompt())) {
            list.add(singleton("role", "system", "content", request.getSystemPrompt()));
        }
        if (request.getHistory() != null) {
            for (ChatMessage message : request.getHistory()) {
                if (message != null && message.getRole() != null) {
                    list.add(singleton("role", message.getRole(), "content", message.getContent()));
                }
            }
        }
        list.add(singleton("role", "user", "content",
                request.getUserPrompt() == null ? "" : request.getUserPrompt()));
        return list;
    }

    private AiProviderResult parse(String responseBody, String model) {
        if (!StringUtils.hasText(responseBody)) {
            throw new BizException(ResultCode.AI_UNAVAILABLE, "AI 服务返回为空");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            throw new BizException(ResultCode.AI_UNAVAILABLE, "AI 服务返回格式异常", e);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() == 0) {
            throw new BizException(ResultCode.AI_UNAVAILABLE, "AI 服务未返回结果");
        }
        String content = choices.get(0).path("message").path("content").asText("");
        if (!StringUtils.hasText(content)) {
            throw new BizException(ResultCode.AI_UNAVAILABLE, "AI 服务返回内容为空");
        }
        JsonNode usage = root.path("usage");
        return AiProviderResult.builder()
                .content(content)
                .model(root.path("model").asText(model))
                .inputTokens(usage.path("prompt_tokens").asInt(0))
                .outputTokens(usage.path("completion_tokens").asInt(0))
                .build();
    }

    /** Spring 绑定通常会解析占位符；这里再兜一层，避免把字面量 {@code ${AI_API_KEY:}} 当成 Key 发出去。 */
    private String resolveApiKey() {
        String key = properties.getHttp().getApiKey();
        if (key == null || key.startsWith("${")) {
            return "";
        }
        return key.trim();
    }

    private String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private Map<String, String> singleton(String k1, String v1) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(k1, v1);
        return map;
    }

    private Map<String, String> singleton(String k1, String v1, String k2, String v2) {
        Map<String, String> map = singleton(k1, v1);
        map.put(k2, v2);
        return map;
    }
}
