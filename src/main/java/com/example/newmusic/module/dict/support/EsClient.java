package com.example.newmusic.module.dict.support;

import com.example.newmusic.module.dict.config.DictProperties;
import com.example.newmusic.module.dict.vo.DictAnalyzeVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Elasticsearch REST 客户端（模块 11）。
 *
 * <p>用 {@link RestTemplate} 而不是官方 {@code RestHighLevelClient}：本项目 Java 8、
 * Spring Boot 2.6，引入 HLRC 会带进一大串 Lucene 依赖，而这里只需要
 * {@code _search} / {@code _analyze} / {@code _reload_search_analyzers} / {@code _bulk}
 * 五个端点。REST 直连也让「ES 没装」时的降级路径更容易验证。
 *
 * <p>所有方法在 ES 未启用/不可达时都只返回「不可用」或抛 {@link EsUnavailableException}，
 * 由上层决定是降级还是给运营一个明确提示。
 *
 * <p><b>编码：</b>ES 的响应头是 {@code application/json} 且不带 charset。
 * Spring 默认的 {@code StringHttpMessageConverter} 用 ISO-8859-1 解码，中文会变成乱码，
 * 所以在转换器列表最前面插一个 UTF-8 版本——这是接真实 ES 时才暴露的问题。
 *
 * <p><b>认证：</b>ES 9 默认开启 security；本机把 {@code xpack.security.http.ssl.enabled}
 * 设为 false，因此走 {@code http://} + Basic Auth。用户名/密码只从环境变量注入。
 */
@Slf4j
@Component
public class EsClient {

    private static final long PING_CACHE_MILLIS = 10_000L;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final DictProperties.Es properties;

    private volatile boolean available = false;
    private volatile long lastPing = 0L;
    private volatile String version = null;
    private volatile boolean ikPlugin = false;
    private volatile boolean pinyinPlugin = false;
    private volatile boolean stconvertPlugin = false;

    public EsClient(DictProperties dictProperties, ObjectMapper objectMapper) {
        this.properties = dictProperties.getEs();
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        this.restTemplate = new RestTemplate(factory);
        // 必须放在最前：默认 StringHttpMessageConverter 是 ISO-8859-1，中文 _source 会乱码
        this.restTemplate.getMessageConverters().add(0,
                new StringHttpMessageConverter(StandardCharsets.UTF_8));
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public String url() {
        return properties.getUrl();
    }

    /** 10 秒缓存的健康探测；ES 没配时直接 false，不发请求。 */
    public boolean isAvailable() {
        if (!properties.isEnabled()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastPing < PING_CACHE_MILLIS) {
            return available;
        }
        synchronized (this) {
            if (now - lastPing < PING_CACHE_MILLIS) {
                return available;
            }
            try {
                JsonNode root = exchange(HttpMethod.GET, "/", null);
                version = root.path("version").path("number").asText(null);
                probePlugins();
                available = true;
            } catch (RuntimeException e) {
                available = false;
                log.debug("Elasticsearch 不可达 url={} cause={}", properties.getUrl(), e.toString());
            }
            lastPing = now;
            return available;
        }
    }

    public String getVersion() {
        return version;
    }

    public boolean hasIkPlugin() {
        return ikPlugin;
    }

    public boolean hasPinyinPlugin() {
        return pinyinPlugin;
    }

    public boolean hasStconvertPlugin() {
        return stconvertPlugin;
    }

    /** 触发查询分析器热重载（BR-20-023）。同义词/停用词无需重建索引。 */
    public void reloadSearchAnalyzers() {
        requireAvailable();
        String path = StringUtils.hasText(properties.getIndexAlias())
                ? "/" + properties.getIndexAlias() + "/_reload_search_analyzers"
                : "/_reload_search_analyzers";
        exchange(HttpMethod.POST, path, null);
    }

    /**
     * 分析器测试。优先使用索引上的 `search_analyzer_music`，失败时退回全局 `_analyze`。
     */
    public DictAnalyzeVO analyze(String text, String analyzer) {
        requireAvailable();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("text", text);
        if (StringUtils.hasText(analyzer)) {
            body.put("analyzer", analyzer);
        }
        String index = properties.getAnalyzeIndex();
        JsonNode root;
        try {
            root = exchange(HttpMethod.POST,
                    StringUtils.hasText(index) ? "/" + index + "/_analyze" : "/_analyze", body);
        } catch (RuntimeException e) {
            // 索引还没建 / 分析器名不在该索引上：退回无索引的全局分析
            root = exchange(HttpMethod.POST, "/_analyze", body);
        }
        DictAnalyzeVO vo = new DictAnalyzeVO();
        vo.setText(text);
        vo.setAnalyzer(analyzer);
        vo.setSource("ES");
        for (JsonNode token : root.path("tokens")) {
            vo.getTokens().add(new DictAnalyzeVO.Token(
                    token.path("token").asText(),
                    token.path("start_offset").asInt(),
                    token.path("end_offset").asInt(),
                    token.path("type").asText()));
        }
        return vo;
    }

    /** ES 的搜索入口；由 {@code EsSearchEngine} 调用。 */
    public JsonNode search(String index, JsonNode body) {
        requireAvailable();
        return exchange(HttpMethod.POST, "/" + index + "/_search", body);
    }

    /**
     * Bulk 写入。{@code _bulk} 要求 NDJSON（末尾必须有换行），
     * 不能走 {@link #exchange} 的 JSON 序列化路径。
     */
    public JsonNode bulk(String ndjson) {
        requireAvailable();
        HttpHeaders headers = jsonHeaders();
        headers.setContentType(MediaType.parseMediaType("application/x-ndjson"));
        HttpEntity<String> entity = new HttpEntity<>(ndjson, headers);
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    trimTrailingSlash(properties.getUrl()) + "/_bulk",
                    HttpMethod.POST, entity, String.class);
            if (!StringUtils.hasText(response.getBody())) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(response.getBody());
        } catch (HttpStatusCodeException e) {
            throw new EsUnavailableException("ES bulk 返回 " + e.getRawStatusCode() + "："
                    + DictTextSupport.brief(e.getResponseBodyAsString(), 300), e);
        } catch (Exception e) {
            throw new EsUnavailableException("ES bulk 请求失败：" + e.getMessage(), e);
        }
    }

    /**
     * 索引（或别名）是否存在。别名存在时 HEAD 也会返回 200。
     *
     * <p>不能借 {@link #exchange} 来做：它把 {@code HttpStatusCodeException} 统一包成
     * {@link EsUnavailableException}，404 会被当成「ES 不可达」，首次启动就永远不会建索引。
     */
    public boolean indexExists(String index) {
        requireAvailable();
        String url = trimTrailingSlash(properties.getUrl()) + "/" + index;
        try {
            restTemplate.exchange(url, HttpMethod.HEAD, new HttpEntity<>(jsonHeaders()), Void.class);
            return true;
        } catch (HttpStatusCodeException e) {
            if (e.getRawStatusCode() == 404) {
                return false;
            }
            throw new EsUnavailableException("ES 返回 " + e.getRawStatusCode() + "："
                    + DictTextSupport.brief(e.getResponseBodyAsString(), 300), e);
        } catch (Exception e) {
            throw new EsUnavailableException("ES 请求失败：" + e.getMessage(), e);
        }
    }

    /**
     * 读取索引 mapping 的 {@code _meta.schemaVersion}；别名指向多个索引时取最大值。
     *
     * <p>返回 null 表示旧索引没有写版本号（按 0 处理），由调用方决定是否重建。
     */
    public Integer indexSchemaVersion(String index) {
        requireAvailable();
        JsonNode root = exchange(HttpMethod.GET, "/" + index + "/_mapping", null);
        Integer max = null;
        Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            String concrete = names.next();
            JsonNode value = root.path(concrete).path("mappings").path("_meta").path("schemaVersion");
            if (value.isNumber()) {
                int version = value.asInt();
                if (max == null || version > max) {
                    max = version;
                }
            }
        }
        return max;
    }

    public HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setAcceptCharset(java.util.Collections.singletonList(StandardCharsets.UTF_8));
        if (StringUtils.hasText(properties.getUsername())) {
            headers.setBasicAuth(properties.getUsername(), properties.getPassword());
        }
        return headers;
    }

    public JsonNode exchange(HttpMethod method, String path, JsonNode body) {
        String url = trimTrailingSlash(properties.getUrl()) + path;
        HttpEntity<JsonNode> entity = new HttpEntity<>(body, jsonHeaders());
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, method, entity, String.class);
            if (method == HttpMethod.HEAD || !StringUtils.hasText(response.getBody())) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(response.getBody());
        } catch (HttpStatusCodeException e) {
            throw new EsUnavailableException("ES 返回 " + e.getRawStatusCode() + "："
                    + DictTextSupport.brief(e.getResponseBodyAsString(), 300), e);
        } catch (Exception e) {
            throw new EsUnavailableException("ES 请求失败：" + e.getMessage(), e);
        }
    }

    private void probePlugins() {
        ikPlugin = false;
        pinyinPlugin = false;
        stconvertPlugin = false;
        try {
            JsonNode root = exchange(HttpMethod.GET, "/_nodes/plugins", null);
            List<String> names = new ArrayList<>();
            for (JsonNode node : root.path("nodes")) {
                for (JsonNode plugin : node.path("plugins")) {
                    names.add(plugin.path("name").asText(""));
                }
            }
            for (String name : names) {
                if (name.contains("analysis-ik")) {
                    ikPlugin = true;
                }
                if (name.contains("analysis-pinyin")) {
                    pinyinPlugin = true;
                }
                if (name.contains("analysis-stconvert")) {
                    stconvertPlugin = true;
                }
            }
        } catch (RuntimeException e) {
            log.debug("读取 ES 插件列表失败：{}", e.toString());
        }
    }

    private void requireAvailable() {
        if (!isAvailable()) {
            throw new EsUnavailableException("Elasticsearch 未启用或不可达：" + properties.getUrl(), null);
        }
    }

    private static String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 让上层可以只捕获这一种异常做降级。 */
    public static class EsUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public EsUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
