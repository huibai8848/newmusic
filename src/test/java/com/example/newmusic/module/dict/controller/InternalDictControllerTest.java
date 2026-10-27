package com.example.newmusic.module.dict.controller;

import com.example.newmusic.module.dict.config.DictProperties;
import com.example.newmusic.module.dict.entity.IkDictWord;
import com.example.newmusic.module.dict.mapper.IkDictWordMapper;
import com.example.newmusic.module.dict.mapper.IkStopwordMapper;
import com.example.newmusic.module.dict.mapper.PinyinDictMapper;
import com.example.newmusic.module.dict.mapper.SynonymMapper;
import com.example.newmusic.module.dict.service.DictSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内部词典接口的 IP 白名单单测（模块 11，待办「非白名单 IP 拒绝分支」）。
 *
 * <p>为什么需要单测：`verify-11.mjs` 只能从本机 127.0.0.1 发起请求，
 * 「白名单外被拒」那条路径在进程外无法构造，脚本里一直是跳过项。
 * MockMvc 可以直接设置 `remoteAddr`，把这半条分支补上。
 *
 * <p>钉住两件事：
 * <ol>
 *   <li>白名单外的来源必须 403，且响应里不能带出任何词典内容；</li>
 *   <li>**不信任 `X-Forwarded-For` / `X-Real-IP`**——它们由客户端随意填写，
 *       若用来取来源 IP，任何人都能伪造 127.0.0.1 读到整份词典。</li>
 * </ol>
 *
 * <p>用 standaloneSetup：不启动 Spring 容器、不连库、不经过安全过滤器链。
 * 这里要验的是控制器自己的判断，而不是安全配置；白名单之外的访问控制在部署侧
 * 由 `DICT_INTERNAL_IPS` 收紧（见 application.yml）。
 */
@DisplayName("InternalDictController IP 白名单")
class InternalDictControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DictProperties properties = new DictProperties();
        // 与 application.yml 的 DICT_INTERNAL_IPS 默认值保持一致（两种 IPv6 回环写法都列出来，
        // 因为 getRemoteAddr() 在 JDK 上可能返回任一形式）
        properties.setInternalIps(Arrays.asList("127.0.0.1", "0:0:0:0:0:0:0:1", "::1"));

        IkDictWordMapper wordMapper = mock(IkDictWordMapper.class);
        IkDictWord row = new IkDictWord();
        row.setWord("晴天");
        row.setFrequency(100);
        row.setPos("n");
        row.setEnabled(1);
        row.setUpdateTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        when(wordMapper.selectList(any())).thenReturn(Collections.singletonList(row));

        DictSnapshotService snapshotService = new DictSnapshotService(
                wordMapper, mock(IkStopwordMapper.class), mock(SynonymMapper.class),
                mock(PinyinDictMapper.class), properties);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new InternalDictController(snapshotService, properties))
                .build();
    }

    /** 设置来源 IP。MockMvc 默认的 remoteAddr 是 127.0.0.1，测外网来源必须显式改写。 */
    private static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @Test
    @DisplayName("白名单外 IP：403，且不返回词典内容与 ETag")
    void rejectsIpOutsideWhitelist() throws Exception {
        mockMvc.perform(get("/api/v1/internal/dict/ik")
                        .param("type", "main")
                        .with(remoteAddr("203.0.113.7")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(containsString("forbidden")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("晴天"))))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG));
    }

    @Test
    @DisplayName("伪造 X-Forwarded-For: 127.0.0.1 不能绕过白名单")
    void ignoresSpoofedForwardedFor() throws Exception {
        mockMvc.perform(get("/api/v1/internal/dict/ik")
                        .param("type", "main")
                        .header("X-Forwarded-For", "127.0.0.1")
                        .with(remoteAddr("198.51.100.9")))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("forbidden")));
    }

    @Test
    @DisplayName("伪造 X-Real-IP: 127.0.0.1 不能绕过白名单")
    void ignoresSpoofedRealIp() throws Exception {
        mockMvc.perform(get("/api/v1/internal/dict/ik")
                        .param("type", "main")
                        .header("X-Real-IP", "127.0.0.1")
                        .with(remoteAddr("198.51.100.10")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("白名单内 127.0.0.1：200 + 词典正文 + ETag（确认上面的 403 不是接口整体坏掉）")
    void allowsLoopback() throws Exception {
        mockMvc.perform(get("/api/v1/internal/dict/ik")
                        .param("type", "main")
                        .with(remoteAddr("127.0.0.1")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(containsString("晴天 100 n")))
                .andExpect(header().exists(HttpHeaders.ETAG));
    }

    @Test
    @DisplayName("白名单内的 IPv6 回环地址同样放行（长短两种写法）")
    void allowsIpv6Loopback() throws Exception {
        for (String ip : new String[] {"0:0:0:0:0:0:0:1", "::1"}) {
            mockMvc.perform(get("/api/v1/internal/dict/ik")
                            .param("type", "main")
                            .with(remoteAddr(ip)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("白名单内但 type 非法：400，且错误信息列出合法取值")
    void rejectsUnknownTypeFromAllowedIp() throws Exception {
        mockMvc.perform(get("/api/v1/internal/dict/ik")
                        .param("type", "nosuchtype")
                        .with(remoteAddr("127.0.0.1")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("main|stopword|synonym|pinyin")));
    }

    @Test
    @DisplayName("白名单判断先于 type 校验：外网来源不泄漏「有哪些 type」")
    void whitelistCheckRunsBeforeTypeValidation() throws Exception {
        mockMvc.perform(get("/api/v1/internal/dict/ik")
                        .param("type", "nosuchtype")
                        .with(remoteAddr("203.0.113.8")))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("main|stopword"))));
    }
}
