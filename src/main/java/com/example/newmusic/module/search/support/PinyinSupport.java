package com.example.newmusic.module.search.support;

import cn.hutool.core.util.StrUtil;
import com.example.newmusic.common.util.PinyinUtil;

/**
 * 拼音检索的**搜索侧规则**（FR-15-002，系分 D-35）。
 *
 * <p>纯拼音推导（{@code 周杰伦 → zhoujielun}）在 {@link PinyinUtil}，因为曲库的写路径也要用它，
 * 放在搜索模块里会让曲库反向依赖搜索。这里只留「检索时怎么用拼音」这件事：
 * 也就是 {@link #toSearchPinyin} 所表达的**含中文时拼音列一律不参与**。
 */
public final class PinyinSupport {

    private PinyinSupport() {
    }

    /**
     * 关键词是否**全是 ASCII**（BR-15-014）。
     *
     * <p>这是「要不要查拼音列」的开关：含中文时拼音列一律不参与，
     * 于是「晴天」只会命中原文，「qingtian」只会命中拼音列，两者的排序互不干扰
     * （AC-15-002-4 要断言的正是这一点）。
     */
    public static boolean isAsciiOnly(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把用户输入的关键词转成「可直接与拼音列比较」的形式。
     *
     * <p>去空格、去标点、转小写——存的拼音列没有分隔符，用户的输入有
     * （{@code "zhou jie lun"}、{@code "zhou-jielun"} 都应命中 {@code zhoujielun}）。
     *
     * @return {@code null} 表示**拼音列不参与本次检索**：关键词含非 ASCII 字符
     *         （BR-15-014），或去掉噪音字符后什么都不剩。返回 null 而不是空串是刻意的——
     *         SQL 里 {@code LIKE CONCAT('%', NULL, '%')} 恒为 NULL、恒不命中，
     *         于是「含中文时不查拼音列」不需要在 SQL 里写分支
     */
    public static String toSearchPinyin(String keyword) {
        if (StrUtil.isBlank(keyword) || !isAsciiOnly(keyword)) {
            return null;
        }
        StringBuilder sb = new StringBuilder(keyword.length());
        for (int i = 0; i < keyword.length(); i++) {
            char c = keyword.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
