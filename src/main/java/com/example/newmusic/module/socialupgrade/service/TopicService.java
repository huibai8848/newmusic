package com.example.newmusic.module.socialupgrade.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.socialupgrade.dto.TopicReq;
import com.example.newmusic.module.socialupgrade.entity.Topic;
import com.example.newmusic.module.socialupgrade.entity.UserPost;
import com.example.newmusic.module.socialupgrade.mapper.TopicMapper;
import com.example.newmusic.module.socialupgrade.mapper.UserPostMapper;
import com.example.newmusic.module.socialupgrade.vo.PostVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/** 话题（FR-25-003 话题格式 / P1 话题页）。 */
@Service
public class TopicService {

    private final TopicMapper topicMapper;
    private final UserPostMapper postMapper;
    private final PostAssembler assembler;
    private final RedisOps redisOps;

    public TopicService(TopicMapper topicMapper, UserPostMapper postMapper,
                        PostAssembler assembler, RedisOps redisOps) {
        this.topicMapper = topicMapper;
        this.postMapper = postMapper;
        this.assembler = assembler;
        this.redisOps = redisOps;
    }

    public List<Topic> hot(int limit) {
        return topicMapper.selectHot(Math.min(Math.max(1, limit), 50));
    }

    public List<Topic> search(String keyword, int limit) {
        if (!StringUtils.hasText(keyword)) {
            return Collections.emptyList();
        }
        return topicMapper.search(keyword.trim(), Math.min(Math.max(1, limit), 50));
    }

    public PageResult<PostVO> posts(Long viewerId, String name, int pageNum, int pageSize) {
        if (!StringUtils.hasText(name)) {
            throw new BizException(ResultCode.PARAM_INVALID, "话题名不能为空");
        }
        String topic = name.trim().replace("#", "");
        Topic row = topicMapper.selectByName(topic);
        if (row == null || Integer.valueOf(1).equals(row.getStatus())) {
            return PageResult.empty(pageNum, pageSize);
        }
        int size = Math.min(Math.max(1, pageSize), 50);
        int index = Math.max(1, pageNum);
        long total = postMapper.countByTopic(topic);
        PageResult<PostVO> result = new PageResult<>();
        result.setPageNum(index);
        result.setPageSize(size);
        result.setTotal(total);
        result.setPages((int) ((total + size - 1) / size));
        result.setHasNext((long) index * size < total);
        result.setTruncated(false);
        if (total == 0) {
            result.setList(Collections.emptyList());
            return result;
        }
        List<UserPost> posts = postMapper.selectByTopic(topic, (long) (index - 1) * size, size);
        result.setList(assembler.assemble(posts, viewerId));
        redisOps.zIncrBy(RedisKeys.topicHot(), topic, 1, Duration.ofHours(1));
        return result;
    }

    @Transactional
    public Topic create(TopicReq req) {
        String name = cleanName(req.getName());
        if (topicMapper.selectByName(name) != null) {
            throw new BizException(ResultCode.DUPLICATE_DATA, "话题已存在");
        }
        topicMapper.insertIgnore(IdWorker.getId(), name);
        Topic created = topicMapper.selectByName(name);
        if (created == null) {
            throw new BizException(ResultCode.SYSTEM_BUSY, "话题创建失败");
        }
        return update(created.getId(), req);
    }

    @Transactional
    public Topic update(Long id, TopicReq req) {
        Topic topic = id == null ? null : topicMapper.selectById(id);
        if (topic == null) {
            throw new BizException(ResultCode.CONTENT_NOT_FOUND, "话题不存在");
        }
        String name = cleanName(req.getName());
        if (!name.equals(topic.getName())) {
            Topic other = topicMapper.selectByName(name);
            if (other != null && !other.getId().equals(id)) {
                throw new BizException(ResultCode.DUPLICATE_DATA, "话题已存在");
            }
            topic.setName(name);
        }
        if (req.getIsOfficial() != null) {
            topic.setIsOfficial(req.getIsOfficial() == 0 ? 0 : 1);
        }
        if (req.getStatus() != null) {
            topic.setStatus(req.getStatus() == 0 ? 0 : 1);
        }
        topic.setUpdateTime(java.time.LocalDateTime.now());
        topicMapper.updateById(topic);
        return topic;
    }

    private static String cleanName(String name) {
        String clean = name == null ? "" : name.trim().replace("#", "");
        if (clean.length() < 2 || clean.length() > 20) {
            throw new BizException(ResultCode.PARAM_INVALID, "话题名需为 2-20 字");
        }
        if (clean.contains(" ") || clean.contains("#")) {
            throw new BizException(ResultCode.PARAM_INVALID, "话题名不能包含空格");
        }
        return clean;
    }
}
