package com.example.newmusic.module.ai.provider;

import com.example.newmusic.module.ai.config.AiScene;
import com.example.newmusic.module.ai.gateway.AiRequest;
import com.example.newmusic.module.ai.support.AiEmotionVocabulary;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性本地 Provider（默认）。
 *
 * <p><b>它不是「假装有 AI」。</b>本机没有可用的模型账户，但网关的缓存、预算、限流、
 * 成本、降级、审核、批处理都必须能被端到端验收。用真实模型做验收既不稳定（同输入不同输出）
 * 又要花钱，因此提供一个纯函数式的替身：
 * <ul>
 *   <li>同一输入永远同一输出，便于断言缓存与降级；</li>
 *   <li>成本按与真实模型相同的公式折算，报表与预算逻辑得到真实锻炼；</li>
 *   <li>模型名带 {@code mock-} 前缀，后台成本报表可一眼区分「演示数据」与「真实调用」。</li>
 * </ul>
 *
 * <p>翻译质量不做承诺：内置了一小份词表，未命中的行会以 {@code 〔译〕} 前缀原样返回。
 * 这个前缀是刻意的——让人看到就知道这是占位翻译，而不是读起来像模像样、实际错译的文本。
 * 接入真实模型后（{@code newmusic.ai.provider=http}）本类完全不会被调用。
 */
@Slf4j
@Component
public class MockAiProvider implements AiProvider {

    private static final Pattern BLOCK = Pattern.compile("<<<(.*?)>>>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("《(.*?)》");
    private static final String MOCK_PREFIX = "〔译〕";

    private static final Map<String, String> DICTIONARY;

    static {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("you are my sunshine", "你是我的阳光");
        map.put("my only sunshine", "我唯一的阳光");
        map.put("you make me happy", "你让我快乐");
        map.put("when skies are grey", "当天空灰暗时");
        map.put("you'll never know, dear", "你永远不会知道，亲爱的");
        map.put("how much i love you", "我有多么爱你");
        map.put("please don't take my sunshine away", "请不要带走我的阳光");
        map.put("hello", "你好");
        map.put("goodbye", "再见");
        map.put("i love you", "我爱你");
        map.put("love", "爱");
        map.put("sunshine", "阳光");
        map.put("night", "夜晚");
        map.put("dream", "梦");
        DICTIONARY = Collections.unmodifiableMap(map);
    }

    private final ObjectMapper objectMapper;

    public MockAiProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public AiProviderResult chat(AiRequest request, String model, long timeoutMs) {
        AiScene scene = AiScene.of(request.getScene());
        if (scene == null) {
            throw new IllegalArgumentException("未知 AI 场景：" + request.getScene());
        }
        String content = render(scene, request);
        int inputTokens = estimate(request.getSystemPrompt()) + estimate(request.getUserPrompt());
        return AiProviderResult.builder()
                .content(content)
                .model(model)
                .inputTokens(inputTokens)
                .outputTokens(estimate(content))
                .build();
    }

    private String render(AiScene scene, AiRequest request) {
        String prompt = request.getUserPrompt() == null ? "" : request.getUserPrompt();
        switch (scene) {
            case LYRIC_TRANS:
                return translate(extractBlock(prompt));
            case LYRIC_SUMMARY:
                return summarize(prompt);
            case EMOTION_TAG:
                return emotion(prompt);
            case SEARCH_PARSE:
                return json(mapOf("intent", "search", "keywords", Collections.emptyList()));
            case SEARCH_EXPLAIN:
                return "这是按关键词与标签匹配得到的搜索结果。";
            case PLAYLIST_GEN:
                return json(mapOf("name", "为你准备的歌单", "songIds", Collections.emptyList()));
            case HUM_DETECT:
                return json(mapOf("matched", false, "confidence", 0));
            case AUDIT:
                return json(mapOf("pass", true, "reason", "mock"));
            case REC_RERANK:
                return json(single("songIds", Collections.emptyList()));
            default:
                return "";
        }
    }

    private String translate(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            String line = lines.get(i);
            if (line.trim().isEmpty()) {
                // 空行原样保留：行数必须与输入严格一致，调用方据此校验对齐
                continue;
            }
            String key = line.trim().toLowerCase(Locale.ROOT)
                    .replace('’', '\'')
                    .replaceAll("[,.!?;:]+$", "");
            String translated = DICTIONARY.get(key);
            sb.append(translated == null ? MOCK_PREFIX + line.trim() : translated);
        }
        return sb.toString();
    }

    private String summarize(String prompt) {
        List<String> lines = extractBlock(prompt);
        String first = lines.isEmpty() ? "" : lines.get(0).trim();
        String title = titleOf(prompt);
        if (first.isEmpty()) {
            return "《" + title + "》以旋律与氛围为主，没有可摘要的歌词文本。";
        }
        return "《" + title + "》以「" + first + "」开篇，围绕反复出现的核心意象展开，情绪集中而克制。";
    }

    /**
     * 情绪标签。关键词命中决定标签，数值由提示词哈希决定——两者都稳定可复现。
     * 提示词里出现 {@code （无歌词）} 时置信度压到阈值以下，用于验收「低置信度转人工」。
     */
    private String emotion(String prompt) {
        String text = prompt.toLowerCase(Locale.ROOT);
        List<String> emotions = new ArrayList<>();
        List<String> scenes = new ArrayList<>();
        if (text.contains("sunshine") || text.contains("happy")) {
            emotions.add("快乐");
            scenes.add("清晨");
        }
        if (text.contains("love") || text.contains("dear")) {
            emotions.add("浪漫");
        }
        if (text.contains("grey") || text.contains("away") || text.contains("don't")) {
            emotions.add("思念");
            scenes.add("独处");
        }
        if (emotions.isEmpty()) {
            emotions.add("平静");
        }
        if (scenes.isEmpty()) {
            scenes.add("通勤");
        }
        boolean noLyric = prompt.contains("（无歌词）");
        int hash = Math.abs(prompt.hashCode());
        double valence = AiEmotionVocabulary.clamp(noLyric ? 0.5 : 0.3 + (hash % 50) / 100d);
        double arousal = AiEmotionVocabulary.clamp(0.2 + (hash / 7 % 50) / 100d);
        double energy = AiEmotionVocabulary.clamp(0.2 + (hash / 13 % 50) / 100d);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("valence", valence);
        body.put("arousal", arousal);
        body.put("energy", energy);
        body.put("emotionTags", emotions);
        body.put("sceneTags", scenes);
        body.put("confidence", noLyric ? 0.42 : 0.86);
        body.put("reason", noLyric ? "缺少歌词文本，仅依据曲风推断" : "根据歌词关键词与情感词推断");
        return json(body);
    }

    private List<String> extractBlock(String prompt) {
        Matcher matcher = BLOCK.matcher(prompt);
        if (!matcher.find()) {
            return Collections.emptyList();
        }
        String body = matcher.group(1);
        if (body.startsWith("\n")) {
            body = body.substring(1);
        }
        if (body.endsWith("\n")) {
            body = body.substring(0, body.length() - 1);
        }
        // -1 保留末尾空行：行数对齐比「好看」重要
        return Arrays.asList(body.split("\n", -1));
    }

    private String titleOf(String prompt) {
        Matcher matcher = TITLE.matcher(prompt);
        return matcher.find() ? matcher.group(1) : "未知歌曲";
    }

    private String json(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Mock 序列化失败", e);
        }
    }

    private Map<String, Object> single(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    private Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    /** 粗略 token 估算：中日韩字符约 1 token/字，西文约 1 token/4 字符。 */
    private int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp >= 0x4E00 && cp <= 0x9FFF) {
                cjk++;
            } else {
                other++;
            }
            i += Character.charCount(cp);
        }
        return Math.max(1, cjk + (other + 3) / 4);
    }
}
