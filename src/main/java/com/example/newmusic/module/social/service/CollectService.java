package com.example.newmusic.module.social.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.social.config.SocialProperties;
import com.example.newmusic.module.social.dto.CollectMoveReq;
import com.example.newmusic.module.social.dto.CollectReq;
import com.example.newmusic.module.social.dto.FolderSaveReq;
import com.example.newmusic.module.social.entity.Collect;
import com.example.newmusic.module.social.entity.CollectFolder;
import com.example.newmusic.module.social.mapper.CollectFolderMapper;
import com.example.newmusic.module.social.mapper.CollectMapper;
import com.example.newmusic.module.social.support.SocialTargets;
import com.example.newmusic.module.social.support.SocialUserSupport;
import com.example.newmusic.module.social.vo.CollectFolderVO;
import com.example.newmusic.module.social.vo.CollectVO;
import com.example.newmusic.module.user.entity.User;
import com.example.newmusic.module.ws.entity.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 收藏与收藏夹（模块 10 FR-19-006）。
 */
@Slf4j
@Service
public class CollectService {

    private static final int[] MILESTONES = {10, 50, 100, 500, 1000};

    private final CollectMapper collectMapper;
    private final CollectFolderMapper folderMapper;
    private final SocialTargets targets;
    private final SocialUserSupport users;
    private final SocialNotifier notifier;
    private final RedisOps redisOps;
    private final SocialProperties properties;

    public CollectService(CollectMapper collectMapper, CollectFolderMapper folderMapper,
                          SocialTargets targets, SocialUserSupport users, SocialNotifier notifier,
                          RedisOps redisOps, SocialProperties properties) {
        this.collectMapper = collectMapper;
        this.folderMapper = folderMapper;
        this.targets = targets;
        this.users = users;
        this.notifier = notifier;
        this.redisOps = redisOps;
        this.properties = properties;
    }

    @Transactional
    public CollectVO toggle(Long userId, CollectReq req) {
        String type = targets.requireCollectType(req.getTargetType());
        Long targetId = req.getTargetId();
        SocialTargets.TargetInfo info = targets.require(type, targetId, true);
        if (info.isPrivatePlaylist()) {
            throw new BizException(ResultCode.COLLECT_PRIVATE_FORBIDDEN);
        }

        boolean wantCollect = req.getCollect() == null || req.getCollect();
        Long folderId = normalizeFolder(userId, req.getFolderId());

        if (wantCollect) {
            checkFolderCapacity(folderId);
            int inserted = collectMapper.insertIgnore(IdWorker.getId(), userId, type, targetId,
                    folderId, LocalDateTime.now());
            if (inserted > 0) {
                targets.addCollectCount(type, targetId, 1);
                if (folderId != null) {
                    folderMapper.updateItemCount(folderId, 1);
                }
                milestone(userId, type, targetId);
            } else if (folderId != null) {
                Collect existing = find(userId, type, targetId);
                if (existing != null && !Objects.equals(existing.getFolderId(), folderId)) {
                    moveOne(existing, folderId);
                }
            }
        } else {
            Collect existing = find(userId, type, targetId);
            if (existing != null) {
                int deleted = collectMapper.deleteByUserAndTarget(userId, type, targetId);
                if (deleted > 0) {
                    targets.addCollectCount(type, targetId, -1);
                    if (existing.getFolderId() != null) {
                        folderMapper.updateItemCount(existing.getFolderId(), -1);
                    }
                }
            }
        }
        return toVO(find(userId, type, targetId), type, targetId, wantCollect);
    }

    /**
     * 单目标收藏状态（详情页首屏用）。
     *
     * <p>没有这个接口时前端只能翻「我的收藏」第一页来猜状态，
     * 收藏量超过一页就会把已收藏显示成未收藏。
     */
    public CollectVO status(Long userId, String rawType, Long targetId) {
        String type = targets.requireCollectType(rawType);
        targets.require(type, targetId, false);
        Collect existing = find(userId, type, targetId);
        return toVO(existing, type, targetId, existing != null);
    }

    public PageResult<CollectVO> list(Long userId, String rawType, Long folderId,
                                      int pageNum, int pageSize) {
        Page<Collect> page = new Page<>(Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 100));
        QueryWrapper<Collect> wrapper = new QueryWrapper<Collect>()
                .eq("user_id", userId).orderByDesc("create_time").orderByDesc("id");
        if (rawType != null && !rawType.trim().isEmpty()) {
            wrapper.eq("target_type", targets.requireCollectType(rawType));
        }
        if (folderId != null) {
            // folderId=0 约定为「默认收藏夹」（DB 中默认夹为 NULL）
            if (folderId == 0L) {
                wrapper.isNull("folder_id");
            } else {
                wrapper.eq("folder_id", folderId);
            }
        }
        Page<Collect> result = collectMapper.selectPage(page, wrapper);
        return PageResult.of(result, item -> toVO(item, item.getTargetType(),
                item.getTargetId(), true), false);
    }

    public List<CollectFolderVO> folders(Long userId) {
        List<CollectFolderVO> list = new ArrayList<>();
        long defaultCount = collectMapper.selectCount(new QueryWrapper<Collect>()
                .eq("user_id", userId).isNull("folder_id"));

        CollectFolderVO defaultFolder = new CollectFolderVO();
        defaultFolder.setId(null);
        defaultFolder.setName("默认收藏夹");
        defaultFolder.setIsDefault(true);
        defaultFolder.setItemCount((int) defaultCount);
        defaultFolder.setSortOrder(0);
        list.add(defaultFolder);

        for (CollectFolder folder : folderMapper.selectList(new QueryWrapper<CollectFolder>()
                .eq("user_id", userId).orderByAsc("sort_order").orderByAsc("create_time"))) {
            CollectFolderVO vo = new CollectFolderVO();
            vo.setId(folder.getId());
            vo.setName(folder.getName());
            vo.setIsDefault(false);
            vo.setItemCount(folder.getItemCount());
            vo.setSortOrder(folder.getSortOrder());
            vo.setCreateTime(folder.getCreateTime());
            list.add(vo);
        }
        return list;
    }

    @Transactional
    public CollectFolderVO createFolder(Long userId, FolderSaveReq req) {
        if (folderMapper.selectCount(new QueryWrapper<CollectFolder>().eq("user_id", userId))
                >= properties.getFolder().getLimit()) {
            throw new BizException(ResultCode.FOLDER_LIMIT);
        }
        String name = req.getName() == null ? "" : req.getName().trim();
        if (name.isEmpty()) {
            throw new BizException(ResultCode.FOLDER_NAME_DUPLICATED, "请输入收藏夹名称");
        }
        if (folderMapper.selectCount(new QueryWrapper<CollectFolder>()
                .eq("user_id", userId).eq("name", name)) > 0) {
            throw new BizException(ResultCode.FOLDER_NAME_DUPLICATED);
        }
        CollectFolder folder = new CollectFolder();
        folder.setUserId(userId);
        folder.setName(name);
        folder.setIsDefault(0);
        folder.setItemCount(0);
        folder.setSortOrder(folderMapper.selectCount(
                new QueryWrapper<CollectFolder>().eq("user_id", userId)).intValue() + 1);
        try {
            folderMapper.insert(folder);
        } catch (DuplicateKeyException e) {
            throw new BizException(ResultCode.FOLDER_NAME_DUPLICATED);
        }
        CollectFolderVO vo = new CollectFolderVO();
        vo.setId(folder.getId());
        vo.setName(folder.getName());
        vo.setIsDefault(false);
        vo.setItemCount(0);
        vo.setSortOrder(folder.getSortOrder());
        vo.setCreateTime(folder.getCreateTime());
        return vo;
    }

    @Transactional
    public CollectFolderVO renameFolder(Long userId, Long folderId, FolderSaveReq req) {
        CollectFolder folder = requireFolder(userId, folderId);
        if (Integer.valueOf(1).equals(folder.getIsDefault())) {
            throw new BizException(ResultCode.FOLDER_DEFAULT_READONLY);
        }
        String name = req.getName() == null ? "" : req.getName().trim();
        if (name.isEmpty()) {
            throw new BizException(ResultCode.FOLDER_NAME_DUPLICATED, "请输入收藏夹名称");
        }
        if (folderMapper.selectCount(new QueryWrapper<CollectFolder>()
                .eq("user_id", userId).eq("name", name).ne("id", folderId)) > 0) {
            throw new BizException(ResultCode.FOLDER_NAME_DUPLICATED);
        }
        folder.setName(name);
        try {
            folderMapper.updateById(folder);
        } catch (DuplicateKeyException e) {
            throw new BizException(ResultCode.FOLDER_NAME_DUPLICATED);
        }
        CollectFolderVO vo = new CollectFolderVO();
        vo.setId(folder.getId());
        vo.setName(folder.getName());
        vo.setIsDefault(false);
        vo.setItemCount(folder.getItemCount());
        vo.setSortOrder(folder.getSortOrder());
        vo.setCreateTime(folder.getCreateTime());
        return vo;
    }

    /** 删除收藏夹：条目移入默认收藏夹，收藏关系本身不删（PRD FR-19-006）。 */
    @Transactional
    public void deleteFolder(Long userId, Long folderId) {
        CollectFolder folder = requireFolder(userId, folderId);
        if (Integer.valueOf(1).equals(folder.getIsDefault())) {
            throw new BizException(ResultCode.FOLDER_DEFAULT_READONLY);
        }
        collectMapper.moveAll(userId, folderId, null);
        folderMapper.deleteById(folderId);
    }

    @Transactional
    public int move(Long userId, CollectMoveReq req) {
        Long toFolder = normalizeFolder(userId, req.getFolderId());
        checkFolderCapacity(toFolder, req.getCollectIds().size());
        int moved = 0;
        for (Long collectId : req.getCollectIds()) {
            Collect collect = collectMapper.selectById(collectId);
            if (collect == null || !userId.equals(collect.getUserId())) {
                continue;
            }
            if (Objects.equals(collect.getFolderId(), toFolder)) {
                continue;
            }
            moveOne(collect, toFolder);
            moved++;
        }
        return moved;
    }

    // ------------------------------------------------------------------ helpers

    private Collect find(Long userId, String type, Long targetId) {
        return collectMapper.selectOne(new QueryWrapper<Collect>()
                .eq("user_id", userId).eq("target_type", type).eq("target_id", targetId));
    }

    private Long normalizeFolder(Long userId, Long folderId) {
        if (folderId == null) {
            return null;
        }
        requireFolder(userId, folderId);
        return folderId;
    }

    private CollectFolder requireFolder(Long userId, Long folderId) {
        CollectFolder folder = folderId == null ? null : folderMapper.selectById(folderId);
        if (folder == null || !userId.equals(folder.getUserId())) {
            throw new BizException(ResultCode.CONTENT_NOT_FOUND, "收藏夹不存在");
        }
        return folder;
    }

    private void checkFolderCapacity(Long folderId) {
        checkFolderCapacity(folderId, 1);
    }

    private void checkFolderCapacity(Long folderId, int adding) {
        if (folderId == null) {
            return;
        }
        long count = collectMapper.selectCount(new QueryWrapper<Collect>().eq("folder_id", folderId));
        if (count + adding > properties.getFolder().getItemLimit()) {
            throw new BizException(ResultCode.FOLDER_ITEM_LIMIT);
        }
    }

    private void moveOne(Collect collect, Long toFolder) {
        collectMapper.moveToFolder(collect.getId(), collect.getUserId(), toFolder);
        if (collect.getFolderId() != null) {
            folderMapper.updateItemCount(collect.getFolderId(), -1);
        }
        if (toFolder != null) {
            folderMapper.updateItemCount(toFolder, 1);
        }
        collect.setFolderId(toFolder);
    }

    private CollectVO toVO(Collect collect, String type, Long targetId, boolean collected) {
        CollectVO vo = new CollectVO();
        if (collect != null) {
            vo.setId(collect.getId());
            vo.setFolderId(collect.getFolderId());
            vo.setCreateTime(collect.getCreateTime());
        }
        vo.setTargetType(type);
        vo.setTargetId(targetId);
        vo.setCollected(collected);
        try {
            SocialTargets.TargetInfo info = targets.require(type, targetId, false);
            vo.setName(info.getName());
            vo.setCover(info.getCover());
            vo.setSubtitle(info.getSubtitle());
            vo.setOffline(!info.isAvailable());
        } catch (BizException e) {
            vo.setName("内容已删除");
            vo.setOffline(true);
        }
        return vo;
    }

    private void milestone(Long userId, String type, Long targetId) {
        if (!SocialTargets.PLAYLIST.equals(type)) {
            return;
        }
        try {
            SocialTargets.TargetInfo info = targets.require(type, targetId, false);
            long count = collectMapper.countByTarget(type, targetId);
            for (int milestone : MILESTONES) {
                if (count == milestone
                        && redisOps.setIfAbsent(RedisKeys.socialCollectMilestone(type, targetId, milestone),
                        "1", Duration.ofDays(90))) {
                    User from = users.requireUser(userId);
                    notifier.notify(info.getOwnerId(), Notification.TYPE_SYSTEM, "收藏里程碑",
                            "你的歌单已获得 " + milestone + " 次收藏", userId, from,
                            Notification.TARGET_PLAYLIST, targetId);
                }
            }
        } catch (Exception e) {
            log.warn("收藏里程碑通知失败 userId={} targetId={} cause={}", userId, targetId, e.toString());
        }
    }
}
