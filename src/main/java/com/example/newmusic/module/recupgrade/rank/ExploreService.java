package com.example.newmusic.module.recupgrade.rank;

import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.recupgrade.config.RecUpgradeProperties;
import com.example.newmusic.module.recupgrade.model.Candidate;
import com.example.newmusic.module.recupgrade.model.RecallContext;
import com.example.newmusic.module.recupgrade.support.Murmur3;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ε-greedy 探索（FR-24-007 / P1）。
 *
 * <p>10% 的位置留给「模型没把握但可能喜欢」的歌。选择是确定性的：
 * 同一用户同一天得到同一批探索歌曲（用户哈希排序），但每天会换一批。
 * 三条退出机制：用户关闭探索、负反馈、7 天内已探索过。
 *
 * <p><b>绝不引入召回集之外的歌</b>（BR-24-045），探索池就是排序候选中排名靠后的部分。
 */
@Slf4j
@Service
public class ExploreService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final RedisOps redisOps;
    private final RecUpgradeProperties properties;

    public ExploreService(RedisOps redisOps, RecUpgradeProperties properties) {
        this.redisOps = redisOps;
        this.properties = properties;
    }

    public boolean optedOut(Long userId) {
        return userId != null && redisOps.isMember(RedisKeys.REC_EXPLORE_OPT_OUT, String.valueOf(userId));
    }

    public void setOptOut(Long userId, boolean optOut) {
        if (userId == null) {
            return;
        }
        if (optOut) {
            redisOps.addToSet(RedisKeys.REC_EXPLORE_OPT_OUT, String.valueOf(userId), null);
        } else {
            redisOps.removeFromSet(RedisKeys.REC_EXPLORE_OPT_OUT, String.valueOf(userId));
        }
    }

    public void addNegative(Long userId, Long songId) {
        if (userId == null || songId == null) {
            return;
        }
        redisOps.addToSet(RedisKeys.recNegative(userId), String.valueOf(songId), Duration.ofDays(30));
    }

    /**
     * 从候选池尾部挑选探索歌曲。
     *
     * @param candidates 已排序的候选（精排或重排后的顺序）
     * @param count      需要的探索数量
     */
    public List<Candidate> select(RecallContext ctx, List<Candidate> candidates, int count) {
        if (count <= 0 || candidates == null || candidates.isEmpty() || optedOut(ctx.getUserId())) {
            return Collections.emptyList();
        }
        Set<String> negative = ctx.hasUser()
                ? redisOps.members(RedisKeys.recNegative(ctx.getUserId())) : Collections.<String>emptySet();
        Set<String> seen = ctx.hasUser()
                ? redisOps.members(RedisKeys.recExplore(String.valueOf(ctx.getUserId())))
                : Collections.<String>emptySet();
        String day = LocalDate.now().format(DATE);
        String seed = ctx.subject() + '|' + day;

        // 只在后半段里找：前半段是模型最有把握的，拿去探索等于浪费
        int from = Math.max(0, candidates.size() / 2);
        List<Candidate> pool = new ArrayList<>();
        for (int i = from; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            if (candidate.getSongId() == null || negative.contains(String.valueOf(candidate.getSongId()))) {
                continue;
            }
            if (seen.contains(String.valueOf(candidate.getSongId()))) {
                continue;
            }
            pool.add(candidate);
        }
        // 池子不够时放开「7 天不重复」，宁可重复探索也不能一个都不给
        if (pool.size() < count) {
            Set<Long> already = new HashSet<>();
            for (Candidate candidate : pool) {
                already.add(candidate.getSongId());
            }
            for (int i = from; i < candidates.size() && pool.size() < count * 2; i++) {
                Candidate candidate = candidates.get(i);
                if (candidate.getSongId() == null || already.contains(candidate.getSongId())
                        || negative.contains(String.valueOf(candidate.getSongId()))) {
                    continue;
                }
                pool.add(candidate);
            }
        }
        if (pool.isEmpty()) {
            return Collections.emptyList();
        }
        // 确定性打散：hash(seed:songId) 升序，保证同一天同一用户结果稳定
        pool.sort(Comparator.comparingLong(c -> Murmur3.hash32(seed + ':' + c.getSongId())));
        List<Candidate> picked = new ArrayList<>(Math.min(count, pool.size()));
        for (int i = 0; i < pool.size() && picked.size() < count; i++) {
            Candidate candidate = pool.get(i);
            candidate.setExplored(true);
            candidate.setExploreWeight(1.0);
            picked.add(candidate);
        }
        for (Candidate candidate : picked) {
            if (ctx.hasUser()) {
                redisOps.addToSet(RedisKeys.recExplore(String.valueOf(ctx.getUserId())),
                        String.valueOf(candidate.getSongId()), Duration.ofDays(7));
                redisOps.addToSet(RedisKeys.recExplore(ctx.getUserId(), day),
                        String.valueOf(candidate.getSongId()), Duration.ofHours(24));
            }
        }
        return picked;
    }

    /** 探索位计划：从第 8 位开始，每 8 位一个，最多占 ratio。 */
    public List<Integer> positions(int size, double ratio) {
        int need = (int) Math.ceil(size * Math.max(0.0, Math.min(1.0, ratio)));
        List<Integer> positions = new ArrayList<>();
        int start = Math.max(1, properties.getExploreStartPosition());
        int step = Math.max(1, (int) Math.round(1.0 / Math.max(0.01, ratio)));
        for (int pos = start; pos <= size && positions.size() < need; pos += step) {
            positions.add(pos);
        }
        // 起始位之后没有空间时，从尾部补
        for (int pos = size; pos >= 1 && positions.size() < need; pos--) {
            if (!positions.contains(pos)) {
                positions.add(pos);
            }
        }
        Collections.sort(positions);
        return positions;
    }
}
