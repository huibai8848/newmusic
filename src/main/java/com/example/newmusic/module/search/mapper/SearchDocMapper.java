package com.example.newmusic.module.search.mapper;

import com.example.newmusic.module.search.engine.SearchCursor;
import com.example.newmusic.module.search.engine.SearchRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 搜索用的**只读**跨表投影（系分 D-44）。
 *
 * <p>模块 01 定的硬约束是「禁止跨模块 Mapper 访问」，防的是「A 模块注入 B 模块的 Mapper，
 * 把 B 的领域逻辑绕过去」。搜索是一次只读跨域投影（歌曲 + 歌词 + 标签 + 歌手 + 专辑 + 歌单），
 * 天然不属任何单一领域。两条路：把搜索 SQL 塞进 {@code SongMapper}（污染曲库模块，
 * 且制造反向依赖），或自建只读 Mapper（依赖方向干净）。选后者。
 *
 * <p><b>它只读、不写</b>，也不引用曲库模块的任何 Bean——判为合规并在此备案。
 * 唯一引用的外部类型是 {@link SearchRequest}/{@link SearchCursor.Pos}，
 * 都是搜索模块自己的东西。
 *
 * <p>SQL 全在 {@code resources/mapper/search/SearchDocMapper.xml}。
 * 不用注解写是因为打分表达式要按 target 分四种，加上过滤、排序、游标，
 * 注解里的字符串拼接会难以阅读和 diff。
 */
@Mapper
public interface SearchDocMapper {

    // ---------- 歌曲 ----------

    /**
     * @param sort               {@link SearchRequest.Sort} 的小写名（{@code relevance}/{@code hot}/
     *                           {@code newest}/{@code duration_asc}/{@code duration_desc}）。
     *                           <b>为什么单独传字符串而不是在 XML 里比较枚举</b>：MyBatis 的 OGNL
     *                           比较枚举要写全限定常量（{@code @...Sort@RELEVANCE}），
     *                           一旦枚举改名就得同步改 SQL，而改错只会在运行期炸。
     *                           传小写名之后，SQL 只依赖接口参数，枚举重构不影响它
     * @param pos                游标位置；{@code null} 时用 {@code offset} 翻页
     * @param includeUnplayable  false = 只返回 {@code copyright_status = 1}（BR-15-003 / D-42）
     * @param lyricOnly          true = 只保留命中歌词的歌（{@code type=lyric}）
     */
    List<SearchRow> searchSongs(@Param("req") SearchRequest req,
                                @Param("sort") String sort,
                                @Param("pos") SearchCursor.Pos pos,
                                @Param("includeUnplayable") boolean includeUnplayable,
                                @Param("lyricOnly") boolean lyricOnly,
                                @Param("limit") int limit,
                                @Param("offset") int offset);

    /** 命中总数。**游标翻页时不调用**：这是另一遍全表扫，代价与主查询同量级 */
    long countSongs(@Param("req") SearchRequest req,
                    @Param("includeUnplayable") boolean includeUnplayable,
                    @Param("lyricOnly") boolean lyricOnly);

    // ---------- 歌手 / 专辑 / 歌单 ----------

    List<SearchRow> searchArtists(@Param("req") SearchRequest req,
                                  @Param("pos") SearchCursor.Pos pos,
                                  @Param("limit") int limit,
                                  @Param("offset") int offset);

    long countArtists(@Param("req") SearchRequest req);

    List<SearchRow> searchAlbums(@Param("req") SearchRequest req,
                                 @Param("pos") SearchCursor.Pos pos,
                                 @Param("limit") int limit,
                                 @Param("offset") int offset);

    long countAlbums(@Param("req") SearchRequest req);

    List<SearchRow> searchPlaylists(@Param("req") SearchRequest req,
                                    @Param("pos") SearchCursor.Pos pos,
                                    @Param("limit") int limit,
                                    @Param("offset") int offset);

    long countPlaylists(@Param("req") SearchRequest req);

    // ---------- 联想（FR-15-003） ----------
    //
    // 联想是**前缀**匹配（BR-15-016 的 completion 语义），与主检索的 `%kw%` 不同——
    // 主检索要「包含」，联想要「从这儿开始」。四个方法分开写而不是加一个 flag：
    // 它们的列集合与排序都不同（歌手的 extra 是曲目数，歌曲的 extra 是歌手名），
    // 用一个方法 + 条件拼装的 SQL 会比四个朴素查询更难读。

    List<SearchRow> suggestArtists(@Param("kw") String keyword,
                                   @Param("py") String pinyinKeyword,
                                   @Param("limit") int limit);

    List<SearchRow> suggestSongs(@Param("kw") String keyword,
                                 @Param("py") String pinyinKeyword,
                                 @Param("limit") int limit);

    List<SearchRow> suggestAlbums(@Param("kw") String keyword,
                                  @Param("py") String pinyinKeyword,
                                  @Param("limit") int limit);

    List<SearchRow> suggestPlaylists(@Param("kw") String keyword,
                                     @Param("limit") int limit);
}
