package com.example.newmusic.module.dict.controller;

import com.example.newmusic.module.dict.config.DictProperties;
import com.example.newmusic.module.dict.service.DictSnapshotService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * IK 远程词典服务（模块 11 FR-20-002）。
 *
 * <p>这不是给前端用的接口，而是给 Elasticsearch 的 {@code analysis-ik} 插件拉取用的：
 * <pre>
 *   PUT /music_song/_settings
 *   { "analysis": { "analyzer": { "ik_smart": { "type": "custom", "tokenizer": "ik_smart" } } } }
 *   # 以及 IK 插件配置 remote_ext_dict = http://<host>:8081/api/v1/internal/dict/ik?type=main
 * </pre>
 *
 * <p><b>为什么不用 {@code IpUtil.getClientIp}</b>：本服务只在同一台机器/内网被 ES 访问，
 * 而 {@code X-Forwarded-For} 是客户端可伪造的。这里取 {@code getRemoteAddr()}，
 * 否则任何人都能加一个 {@code X-Forwarded-For: 127.0.0.1} 读到整份词典。
 *
 * <p>ETag 来自内容 SHA-256，Last-Modified 来自词典行的最大 update_time；
 * 两者都命中时返回 304，IK 不会重复下载未变化的词典（BR-20-009）。
 */
@Slf4j
@Api(tags = "内部接口-远程词典")
@RestController
@RequestMapping("/api/v1/internal/dict")
public class InternalDictController {

    private static final MediaType TEXT_UTF8 = MediaType.parseMediaType("text/plain;charset=UTF-8");
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;

    private final DictSnapshotService snapshotService;
    private final DictProperties properties;

    public InternalDictController(DictSnapshotService snapshotService, DictProperties properties) {
        this.snapshotService = snapshotService;
        this.properties = properties;
    }

    @ApiOperation(value = "IK 远程词典", notes = "type=main|stopword|synonym|pinyin；仅内网 IP 可访问")
    @GetMapping(value = "/ik", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> ik(@RequestParam("type") String type,
                                     @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
                                     @RequestHeader(value = HttpHeaders.IF_MODIFIED_SINCE, required = false) String ifModifiedSince,
                                     HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!allowed(remote)) {
            log.warn("拒绝非白名单来源拉取词典 ip={} type={}", remote, type);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .contentType(TEXT_UTF8)
                    .body("forbidden: dictionary service is internal only");
        }
        if (!DictSnapshotService.isValidType(type)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(TEXT_UTF8)
                    .body("unknown dict type: " + type + " (expected main|stopword|synonym|pinyin)");
        }

        DictSnapshotService.Snapshot snapshot;
        try {
            snapshot = snapshotService.get(type);
        } catch (RuntimeException e) {
            // 词典表没建/库挂了：给 ES 一个明确的 503，让 IK 保留上一份词典继续工作
            log.warn("构建词典快照失败 type={} cause={}", type, e.toString());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(TEXT_UTF8)
                    .body("dictionary temporarily unavailable: " + e.getMessage());
        }

        String etag = "\"" + snapshot.getVersion() + "\"";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(TEXT_UTF8);
        headers.setETag(etag);
        headers.setLastModified(snapshot.getLastModified());
        headers.setCacheControl("no-cache");

        if (notModified(ifNoneMatch, ifModifiedSince, etag, snapshot.getLastModified())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(headers).build();
        }
        snapshotService.markPulled(type);
        return ResponseEntity.ok().headers(headers).body(snapshot.getContent());
    }

    /**
     * RFC 7232：If-None-Match 存在时优先于 If-Modified-Since，且两者都只对 GET 有意义。
     * 这里只做精确/通配匹配——IK 只会回传我们上一条响应里的 ETag，不需要处理弱校验。
     */
    private boolean notModified(String ifNoneMatch, String ifModifiedSince, String etag, Instant lastModified) {
        if (ifNoneMatch != null && !ifNoneMatch.trim().isEmpty()) {
            String value = ifNoneMatch.trim();
            if ("*".equals(value)) {
                return true;
            }
            for (String candidate : value.split(",")) {
                String item = candidate.trim();
                if (item.startsWith("W/")) {
                    item = item.substring(2);
                }
                if (etag.equals(item)) {
                    return true;
                }
            }
            return false;
        }
        if (ifModifiedSince != null && !ifModifiedSince.trim().isEmpty()) {
            try {
                ZonedDateTime since = ZonedDateTime.parse(ifModifiedSince.trim(), HTTP_DATE);
                return !lastModified.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                        .isAfter(since.withZoneSameInstant(ZoneOffset.UTC).toInstant());
            } catch (DateTimeParseException e) {
                // 头格式非法按「没有条件」处理：宁可多传一次，也不错误地 304
                return false;
            }
        }
        return false;
    }

    private boolean allowed(String remote) {
        if (remote == null) {
            return false;
        }
        String ip = remote.trim();
        if (ip.startsWith("[") && ip.endsWith("]")) {
            ip = ip.substring(1, ip.length() - 1);
        }
        for (String allowed : properties.getInternalIps()) {
            if (allowed == null) {
                continue;
            }
            String item = allowed.trim();
            if ("*".equals(item) || item.equalsIgnoreCase(ip)) {
                return true;
            }
            // 兼容配置里写 localhost 的开发场景
            if ("localhost".equalsIgnoreCase(item)
                    && ("127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip))) {
                return true;
            }
        }
        return false;
    }
}
