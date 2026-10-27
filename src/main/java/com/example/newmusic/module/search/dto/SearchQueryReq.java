package com.example.newmusic.module.search.dto;

import cn.hutool.core.util.StrUtil;
import com.example.newmusic.common.query.PageQuery;
import com.example.newmusic.module.search.engine.SearchRequest;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 搜索请求参数（§5.2）。
 *
 * <p>继承 {@link PageQuery}：分页、关键词归一、长度上限这些通用规则在模块 01 就定好了
 * （FR-10-001），重写一遍只会让两处慢慢长歪。
 *
 * <p><b>关键词长度超限走不到 `70002`。</b>{@code PageQuery.normalize()} 是**静默截断**到 50 字符的，
 * 所以「搜索关键词过长」在正常路径上永远触发不了。`70002` 仍然保留在码表里（PRD 05 的完整性），
 * 但不要指望能在验收里看到它——这一点写在系分 §5.5 里，免得后来者以为漏实现了。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ApiModel("搜索请求")
public class SearchQueryReq extends PageQuery {

    private static final long serialVersionUID = 1L;

    /** 合法的 type 取值，同时也是 {@link SearchRequest.Target} 的映射来源 */
    public static final String TYPE_ALL = "all";
    public static final String TYPE_SONG = "song";
    public static final String TYPE_ARTIST = "artist";
    public static final String TYPE_ALBUM = "album";
    public static final String TYPE_PLAYLIST = "playlist";
    public static final String TYPE_LYRIC = "lyric";

    @ApiModelProperty(value = "搜索类型", allowableValues = "all,song,artist,album,playlist,lyric", example = "all")
    private String type = TYPE_ALL;

    @ApiModelProperty("游标；from ≥ 1000 时必填（BR-15-010）")
    private String cursor;

    @ApiModelProperty("语种，多值逗号分隔")
    private String language;

    @ApiModelProperty("流派，多值逗号分隔")
    private String genre;

    @ApiModelProperty("年代，多值逗号分隔：80s/90s/00s/10s/20s")
    private String era;

    @ApiModelProperty("时长区间，多值逗号分隔：lt3/3to5/gt5")
    private String duration;

    @ApiModelProperty(value = "排序", allowableValues = "relevance,hot,newest,duration_asc,duration_desc")
    private String sort = "relevance";

    public void normalize() {
        super.normalize();
        if (type == null || type.trim().isEmpty()) {
            type = TYPE_ALL;
        } else {
            type = type.trim().toLowerCase(Locale.ROOT);
        }
        if (!isKnownType(type)) {
            // 未知 type 退回 all 而不是报错：搜索接口是「越宽松越安全」的读接口，
            // 一个手打错的 type 没必要让用户看到一个红色提示
            type = TYPE_ALL;
        }
        if (cursor != null) {
            cursor = cursor.trim();
            if (cursor.isEmpty()) {
                cursor = null;
            }
        }
        if (sort == null || sort.trim().isEmpty()) {
            sort = "relevance";
        } else {
            sort = sort.trim().toLowerCase(Locale.ROOT);
        }
    }

    private boolean isKnownType(String value) {
        switch (value) {
            case TYPE_ALL:
            case TYPE_SONG:
            case TYPE_ARTIST:
            case TYPE_ALBUM:
            case TYPE_PLAYLIST:
            case TYPE_LYRIC:
                return true;
            default:
                return false;
        }
    }

    /** {@code type} → 引擎的检索目标。{@code all} 没有对应的 Target（它是四次检索，由 Service 拆） */
    public SearchRequest.Target target() {
        switch (type) {
            case TYPE_ARTIST:
                return SearchRequest.Target.ARTIST;
            case TYPE_ALBUM:
                return SearchRequest.Target.ALBUM;
            case TYPE_PLAYLIST:
                return SearchRequest.Target.PLAYLIST;
            case TYPE_LYRIC:
                return SearchRequest.Target.LYRIC;
            case TYPE_SONG:
            default:
                return SearchRequest.Target.SONG;
        }
    }

    /** 排序参数 → 枚举。未知取值回退「相关性」，与 type 同样按「宽松」处理 */
    public SearchRequest.Sort sortEnum() {
        switch (sort) {
            case "hot":
                return SearchRequest.Sort.HOT;
            case "newest":
                return SearchRequest.Sort.NEWEST;
            case "duration_asc":
                return SearchRequest.Sort.DURATION_ASC;
            case "duration_desc":
                return SearchRequest.Sort.DURATION_DESC;
            case "relevance":
            default:
                return SearchRequest.Sort.RELEVANCE;
        }
    }

    public Set<String> languages() {
        return split(language);
    }

    public Set<String> genres() {
        return split(genre);
    }

    public Set<String> eras() {
        return split(era);
    }

    public Set<String> durations() {
        return split(duration);
    }

    /** 逗号分隔 → 去重集合。用 LinkedHashSet 保持请求给出的顺序（SQL 的 IN 顺序不影响结果，但便于排查） */
    private Set<String> split(String raw) {
        if (StrUtil.isBlank(raw)) {
            return Collections.emptySet();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }
}
