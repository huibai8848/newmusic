package com.example.newmusic.module.music.service;

import com.example.newmusic.common.query.PageQuery;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.module.music.dto.TagSaveReq;
import com.example.newmusic.module.music.entity.Tag;

import java.util.List;

public interface TagService {

    PageResult<Tag> page(PageQuery query, Integer tagType, Integer isHot, Integer status);

    /** 热门标签（首页/搜索建议用）。 */
    List<Tag> listHot(int limit);

    /** 歌曲已绑定标签。 */
    List<Tag> listBySongId(Long songId);

    Tag detail(Long id);

    Long create(TagSaveReq req);

    void update(Long id, TagSaveReq req);

    void delete(Long id);

    /** 全量覆盖某歌曲的标签（最多 10 个），同步维护 use_count。 */
    void bindSongTags(Long songId, List<Long> tagIds);

    /** 冗余字段维护：标签使用次数增减（歌曲删除/解绑时调用）。 */
    void changeUseCount(Long tagId, int delta);
}
