package com.example.newmusic.module.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.newmusic.module.search.entity.SearchHot;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface SearchHotMapper extends BaseMapper<SearchHot> {

    /**
     * 按「日期 + 词」幂等写入（BR-15-031 的整点落库）。
     *
     * <p>用 {@code UNIQUE KEY uk_hot_date_word(stat_date, keyword)} 做幂等，
     * 而不是先查后写：定时任务会在多个实例上同时跑（本机虽然只有一个进程，
     * 但代码不能假设这一点），先查后写在两个实例之间必然出现重复行或唯一键异常。
     *
     * <p><b>{@code is_top} 只在 INSERT 时给 0，UPDATE 时绝不触碰。</b>
     * 落库任务每小时跑一次，如果它顺手把 is_top 也刷成 0，运营刚设的置顶词会在
     * 下一个整点凭空消失——而这类「配置被后台任务悄悄改掉」的故障极难定位。
     *
     * @param rankNo 1 起；0 表示本次没进榜（保留旧值不覆盖）
     */
    @Insert("INSERT INTO t_search_hot (id, keyword, search_count, rank_no, is_top, stat_date) "
            + "VALUES (#{id}, #{keyword}, #{searchCount}, #{rankNo}, 0, #{statDate}) "
            + "ON DUPLICATE KEY UPDATE search_count = VALUES(search_count), "
            + "rank_no = VALUES(rank_no), update_time = CURRENT_TIMESTAMP(3)")
    int upsert(@Param("id") Long id,
               @Param("keyword") String keyword,
               @Param("searchCount") long searchCount,
               @Param("rankNo") int rankNo,
               @Param("statDate") LocalDate statDate);

    /**
     * 当日的运营置顶词（BR-15-030）。
     *
     * <p>只认**当天**：置顶是运营每天手动做的事，跨天沿用昨天的置顶等于让一个
     * 早已下架的活动词永久挂在榜顶。运营忘了设，就是没有置顶——这是可接受的结果，
     * 而「悄悄沿用历史配置」不是。
     */
    @Select("SELECT * FROM t_search_hot WHERE stat_date = #{statDate} AND is_top = 1 "
            + "ORDER BY rank_no ASC, id ASC LIMIT #{limit}")
    List<SearchHot> selectTops(@Param("statDate") LocalDate statDate, @Param("limit") int limit);

    /**
     * 最近一天的榜单（EX-15-005-2 的空榜重建）。
     *
     * <p>什么时候会用到：Redis 里那个 ZSet 丢了（重启没持久化、被误删、新环境首启）。
     * 没有它，全新部署的热搜榜会一直空着直到有人搜够次数；有了它，
     * 至少能展示上一份已知的榜单。
     */
    @Select("SELECT * FROM t_search_hot "
            + "WHERE stat_date = (SELECT MAX(stat_date) FROM t_search_hot) "
            + "ORDER BY rank_no ASC, id ASC LIMIT #{limit}")
    List<SearchHot> selectLatestRanked(@Param("limit") int limit);
}
