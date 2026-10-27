package com.example.newmusic.module.search.service.impl;

import com.example.newmusic.infra.cache.CacheService;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.module.search.config.SearchProperties;
import com.example.newmusic.module.search.dto.SuggestQueryReq;
import com.example.newmusic.module.search.mapper.SearchDocMapper;
import com.example.newmusic.module.search.mapper.SearchRow;
import com.example.newmusic.module.search.service.HotSearchService;
import com.example.newmusic.module.search.service.SuggestService;
import com.example.newmusic.module.search.support.HighlightSupport;
import com.example.newmusic.module.search.support.KeywordSupport;
import com.example.newmusic.module.search.support.PinyinSupport;
import com.example.newmusic.module.search.support.SensitiveWordSupport;
import com.example.newmusic.module.search.vo.SearchSuggestVO;
import com.example.newmusic.module.search.vo.SuggestItemVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class SuggestServiceImpl implements SuggestService {

    private final SearchDocMapper mapper;
    private final CacheService cacheService;
    private final HotSearchService hotSearchService;
    private final SensitiveWordSupport sensitiveWordSupport;
    private final SearchProperties properties;

    public SuggestServiceImpl(SearchDocMapper mapper, CacheService cacheService,
                              HotSearchService hotSearchService,
                              SensitiveWordSupport sensitiveWordSupport,
                              SearchProperties properties) {
        this.mapper = mapper;
        this.cacheService = cacheService;
        this.hotSearchService = hotSearchService;
        this.sensitiveWordSupport = sensitiveWordSupport;
        this.properties = properties;
    }

    @Override
    public SearchSuggestVO suggest(SuggestQueryReq request) {
        request.normalize(properties);
        if (!request.hasKeyword()) {
            // BR-15-019：关键词为空只给热搜词。用户点开搜索框还没打字时，
            // 给一个空下拉是浪费的，给热搜是「别人都在搜什么」的零成本引导
            return withHotFallback(Collections.<SuggestItemVO>emptyList());
        }
        String keyword = request.getKeyword();
        if (sensitiveWordSupport.isBlocked(keyword)) {
            // 与主检索一致：命中敏感词不给建议，也不给热搜兜底——
            // 兜底会让被拦的词看起来「搜到点了」，反而误导
            SearchSuggestVO vo = new SearchSuggestVO();
            vo.setSuggestions(Collections.<SuggestItemVO>emptyList());
            vo.setHotWords(Collections.<String>emptyList());
            return vo;
        }

        String cacheKey = RedisKeys.searchSuggest(prefixOf(keyword));
        CacheService.Lookup<SuggestItemVO[]> cached = cacheService.get(cacheKey, SuggestItemVO[].class);
        List<SuggestItemVO> bare;
        if (cached.isHit()) {
            bare = Arrays.asList(cached.value());
        } else {
            bare = query(keyword, request.getLimit());
            // 缓存里存的是**裸数据**（highlight 恒为 null）：见类与方法注释
            cacheService.put(cacheKey, bare.toArray(new SuggestItemVO[0]),
                    Duration.ofSeconds(properties.getSuggest().getTtlSeconds()));
        }
        if (bare.isEmpty()) {
            return withHotFallback(Collections.<SuggestItemVO>emptyList());
        }
        return withHotFallback(highlight(keyword, bare));
    }

    /**
     * 前缀查询 + 分组截断。
     *
     * <p>顺序是**硬性**的（BR-15-016：歌手 &gt; 歌曲 &gt; 专辑 &gt; 歌单），
     * 做法是「四类各取 limit 条，按优先级拼接后截断」而不是「取满 limit 就停」——
     * 后者会让歌手占满全部位置时歌曲一条都出不来，而用户搜「周杰伦 晴天」时
     * 最想要的恰恰是那首歌。
     */
    private List<SuggestItemVO> query(String keyword, int limit) {
        String pinyin = PinyinSupport.toSearchPinyin(keyword);
        List<SuggestItemVO> items = new ArrayList<>(limit * 2);
        append(items, mapper.suggestArtists(keyword, pinyin, limit), "artist");
        append(items, mapper.suggestSongs(keyword, pinyin, limit), "song");
        append(items, mapper.suggestAlbums(keyword, pinyin, limit), "album");
        append(items, mapper.suggestPlaylists(keyword, limit), "playlist");

        List<SuggestItemVO> filtered = new ArrayList<>(limit);
        for (SuggestItemVO item : items) {
            if (!KeywordSupport.isDisplayable(item.getText()) || sensitiveWordSupport.isBlocked(item.getText())) {
                // BR-15-018：单字符、纯符号、敏感词都不给建议
                continue;
            }
            filtered.add(item);
            if (filtered.size() >= limit) {
                break;
            }
        }
        return filtered;
    }

    private void append(List<SuggestItemVO> target, List<SearchRow> rows, String type) {
        if (rows == null) {
            return;
        }
        for (SearchRow row : rows) {
            SuggestItemVO item = new SuggestItemVO();
            item.setId(row.getId());
            item.setType(type);
            item.setText(row.getName());
            item.setExtra(extraOf(row, type));
            target.add(item);
        }
    }

    private String extraOf(SearchRow row, String type) {
        switch (type) {
            case "song":
            case "album":
                return row.getArtistName();
            case "playlist":
                return row.getSongCount() == null ? null : row.getSongCount() + "首";
            case "artist":
            default:
                return null;
        }
    }

    /**
     * 现算高亮。
     *
     * <p><b>必须新建对象，不能就地改缓存里的实例。</b>{@link CacheService} 的 L1 返回的是
     * 同一个对象引用（它自己的类注释写明「取出的对象视为只读」）。就地设 highlight
     * 会把本次请求的关键词固化进缓存，于是下一个用不同关键词、但命中同一个缓存键的请求
     * 会拿到别人的高亮——一个只在特定输入顺序下才复现的 bug。
     */
    private List<SuggestItemVO> highlight(String keyword, List<SuggestItemVO> bare) {
        String lower = keyword.toLowerCase(Locale.ROOT);
        List<SuggestItemVO> result = new ArrayList<>(bare.size());
        for (SuggestItemVO source : bare) {
            SuggestItemVO copy = new SuggestItemVO();
            copy.setId(source.getId());
            copy.setType(source.getType());
            copy.setText(source.getText());
            copy.setExtra(source.getExtra());
            String text = source.getText();
            // 只有**以关键词开头**才给高亮：热搜兜底带进来的词、以及拼音命中的中文名
            // （用 "zjl" 匹配到「周杰伦」，字符根本对不上）都不能高亮。
            // 给它们返回一个没有 <em> 的「高亮」，只会让前端多一个需要判断的分支
            if (text != null && text.toLowerCase(Locale.ROOT).startsWith(lower)) {
                copy.setHighlight(HighlightSupport.highlight(text, keyword));
            }
            result.add(copy);
        }
        return result;
    }

    private SearchSuggestVO withHotFallback(List<SuggestItemVO> suggestions) {
        SearchSuggestVO vo = new SearchSuggestVO();
        vo.setSuggestions(suggestions);
        // BR-15-021：联想为空时给热搜兜底。非空时也给——前端展示的是「猜你想搜」，
        // 建议与热词并不冲突，而下拉里多几个可点项不会伤害任何人
        vo.setHotWords(hotSearchService.plainWords());
        return vo;
    }

    /**
     * 缓存键的前缀：只取前 N 个字符（BR-15-020）。
     *
     * <p>取「字符」而不是「字节」：中文一个字在 UTF-8 里是 3 字节，
     * 按字节截断会把一个字劈成半个，产生一个永远匹配不上的缓存键。
     * 这个项目里汉字都在 BMP（基本区），一个 char 就是一个字，不必处理代理对。
     */
    private String prefixOf(String keyword) {
        int max = properties.getSuggest().getPrefixLength();
        return keyword.length() <= max ? keyword : keyword.substring(0, max);
    }
}
