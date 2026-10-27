package com.example.newmusic.module.search.service;

import com.example.newmusic.module.search.dto.SuggestQueryReq;
import com.example.newmusic.module.search.vo.SearchSuggestVO;

/**
 * 搜索联想（FR-15-003 / BR-15-015~021，§4.6）。
 *
 * <p>与主检索的三点不同：**前缀**匹配（不是包含）、按热度排序（不是相关性）、
 * 结果按类型分组（歌手 &gt; 歌曲 &gt; 专辑 &gt; 歌单）。
 *
 * <p>缓存键取关键词的**前 8 个字符**（BR-15-020），于是「周杰伦的歌」与「周杰伦的曲」
 * 共用一份缓存。代价是缓存里存的必须是**裸数据**——高亮区间依赖完整关键词，
 * 每次现算（§4.6）。
 */
public interface SuggestService {

    SearchSuggestVO suggest(SuggestQueryReq request);
}
