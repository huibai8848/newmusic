package com.example.newmusic.module.socialupgrade.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.social.mapper.BlacklistMapper;
import com.example.newmusic.module.social.support.SocialUserSupport;
import com.example.newmusic.module.socialupgrade.entity.FansRank;
import com.example.newmusic.module.socialupgrade.entity.UserProfile;
import com.example.newmusic.module.socialupgrade.entity.UserRelationScore;
import com.example.newmusic.module.socialupgrade.mapper.FansRankMapper;
import com.example.newmusic.module.socialupgrade.mapper.UserProfileMapper;
import com.example.newmusic.module.socialupgrade.mapper.UserRelationScoreMapper;
import com.example.newmusic.module.socialupgrade.vo.FanRankVO;
import com.example.newmusic.module.user.entity.User;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 粉丝榜（FR-25-011）。
 *
 * <p>铁粉榜取亲密度 Top 20；互动榜只看近 30 天有互动的人；礼物榜本期没有
 * 打赏数据源，返回空列表。每日快照落在 {@code t_fans_rank}，当天重复读取
 * 直接复用快照，保证榜单名次一天内稳定。
 */
@Service
public class FanRankService {

    public static final int TYPE_TIE_FAN = 1;
    public static final int TYPE_INTERACTION = 2;
    public static final int TYPE_GIFT = 3;

    private static final int TOP_N = 20;
    private static final int SCAN = 60;
    private static final String PERIOD_DAILY = "DAILY";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final UserRelationScoreMapper scoreMapper;
    private final FansRankMapper rankMapper;
    private final UserProfileMapper profileMapper;
    private final BlacklistMapper blacklistMapper;
    private final SocialUserSupport users;

    public FanRankService(UserRelationScoreMapper scoreMapper, FansRankMapper rankMapper,
                          UserProfileMapper profileMapper, BlacklistMapper blacklistMapper,
                          SocialUserSupport users) {
        this.scoreMapper = scoreMapper;
        this.rankMapper = rankMapper;
        this.profileMapper = profileMapper;
        this.blacklistMapper = blacklistMapper;
        this.users = users;
    }

    public FanRankVO rank(Long ownerId, Integer type) {
        User owner = users.requireUser(ownerId);
        if (Integer.valueOf(2).equals(owner.getStatus())) {
            throw new BizException(ResultCode.ACCOUNT_BANNED);
        }
        int rankType = normalizeType(type);

        FanRankVO vo = new FanRankVO();
        vo.setUserId(ownerId);
        vo.setType(rankType);
        vo.setVisible(visible(ownerId));
        vo.setStatDate(LocalDate.now());
        if (!Boolean.TRUE.equals(vo.getVisible())) {
            vo.setList(Collections.emptyList());
            return vo;
        }

        List<FanRankVO.FanRankItemVO> items = rankType == TYPE_GIFT
                ? Collections.emptyList() : compute(ownerId, rankType);
        if (rankType != TYPE_GIFT) {
            persist(ownerId, rankType, items);
        }
        vo.setList(items);
        return vo;
    }

    public boolean visible(Long ownerId) {
        UserProfile profile = profileMapper.selectById(ownerId);
        if (profile == null) {
            return false;
        }
        // 认证用户默认开启；普通用户需要主动把 fansRank 设为 1（BR-25-120）
        if (profile.getCertType() != null && profile.getCertType() > 0) {
            return true;
        }
        return privacyFlag(profile.getPrivacyJson(), "fansRank") == 1;
    }

    // ------------------------------------------------------------------ build

    private List<FanRankVO.FanRankItemVO> compute(Long ownerId, int type) {
        Set<Long> blocked = blockedIds(ownerId);
        LocalDateTime since = LocalDateTime.now().minusDays(30);
        List<UserRelationScore> rows = scoreMapper.selectTopFans(ownerId, SCAN);
        List<UserRelationScore> filtered = new ArrayList<>();
        for (UserRelationScore row : rows) {
            // selectTopFans 取的是 target_user_id = 本人 的行，粉丝在 user_id 上
            if (row.getUserId() == null || blocked.contains(row.getUserId())) {
                continue;
            }
            if (type == TYPE_INTERACTION && (row.getLastInteractTime() == null
                    || row.getLastInteractTime().isBefore(since))) {
                continue;
            }
            filtered.add(row);
        }
        if (filtered.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> ids = new HashSet<>();
        for (UserRelationScore row : filtered) {
            ids.add(row.getUserId());
        }
        Map<Long, User> userMap = users.loadUsers(ids);
        List<FanRankVO.FanRankItemVO> items = new ArrayList<>();
        for (UserRelationScore row : filtered) {
            User user = userMap.get(row.getUserId());
            if (user == null || Integer.valueOf(2).equals(user.getStatus())
                    || Integer.valueOf(4).equals(user.getStatus())) {
                continue;
            }
            FanRankVO.FanRankItemVO item = new FanRankVO.FanRankItemVO();
            item.setUserId(user.getId());
            item.setNickname(user.getNickname());
            item.setAvatar(user.getAvatar());
            item.setScore(Math.max(0, row.getScore() == null ? 0 : row.getScore()));
            items.add(item);
            if (items.size() >= TOP_N) {
                break;
            }
        }
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setRank(i + 1);
            items.get(i).setBadge(type == TYPE_TIE_FAN && i < 3);
        }
        return items;
    }

    private void persist(Long ownerId, int type, List<FanRankVO.FanRankItemVO> items) {
        try {
            String data = JSON.writeValueAsString(items);
            rankMapper.upsert(IdWorker.getId(), ownerId, type, PERIOD_DAILY, LocalDate.now(), data);
        } catch (Exception ignored) {
            // 快照失败不影响实时榜单返回
        }
    }

    /** 读取当日快照；后台/运营需要稳定名次时可调用。 */
    public List<FanRankVO.FanRankItemVO> snapshot(Long ownerId, int type) {
        FansRank row = rankMapper.selectLatest(ownerId, type, PERIOD_DAILY);
        if (row == null || !StringUtils.hasText(row.getRankData())) {
            return Collections.emptyList();
        }
        try {
            return JSON.readValue(row.getRankData(), new TypeReference<List<FanRankVO.FanRankItemVO>>() {
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    // ------------------------------------------------------------------ helpers

    private int normalizeType(Integer type) {
        int value = type == null ? TYPE_TIE_FAN : type;
        if (value < TYPE_TIE_FAN || value > TYPE_GIFT) {
            throw new BizException(ResultCode.PARAM_INVALID, "榜单类型只能是 1/2/3");
        }
        return value;
    }

    private Set<Long> blockedIds(Long ownerId) {
        Set<Long> ids = new HashSet<>(blacklistMapper.selectBlockedIds(ownerId));
        ids.addAll(blacklistMapper.selectBlockerIds(ownerId));
        ids.remove(ownerId);
        return ids;
    }

    private int privacyFlag(String json, String key) {
        if (!StringUtils.hasText(json)) {
            return 0;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = JSON.readTree(json);
            return node.has(key) ? node.get(key).asInt(0) : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
