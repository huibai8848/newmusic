package com.example.newmusic.module.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.module.admin.entity.AuditTask;
import com.example.newmusic.module.admin.mapper.AuditTaskMapper;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.event.SongSubmittedForAuditEvent;
import com.example.newmusic.module.music.mapper.SongMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 把「待审歌曲」变成审核工作台里的一条 SONG 任务。
 *
 * <p>在此之前 `t_audit_task` 只由内容安全机审（{@code ContentAuditService}）和申诉创建。
 * 歌曲走的是另一条线：新增/导入后 `audit_status=0`，但审核台里什么都没有，
 * 管理员只能去歌曲列表里逐个点——「歌曲上架却没有审核任务」就是这个缺口。
 *
 * <p>三条入口共同保证「同一首歌在审核台上只有一条未完成任务」：
 * <ol>
 *   <li>新增/导入/编辑歌曲 → {@link SongSubmittedForAuditEvent}（事务提交后建任务）</li>
 *   <li>启动时回填历史数据，修复上线前已经存在、但审核台里没有任务的待审歌曲</li>
 *   <li>定时对账：补建提交后进程退出而丢掉的任务，并关闭歌曲已删除/已审核的孤儿任务</li>
 * </ol>
 *
 * <p>应用层「先查再插」只能降低概率；`t_audit_task.open_key` 上的唯一索引
 * 才是并发下的最后一道防线（见 `sql/20-audit-task-open-unique.sql`）。
 */
@Slf4j
@Service
public class SongAuditTaskService {

    public static final String TASK_TYPE_SONG = "SONG";

    /** 启动回填一次最多处理多少首：宁可多启动几次，也不要在启动时被历史数据拖住。 */
    private static final int BACKFILL_LIMIT = 500;

    private final AuditTaskMapper taskMapper;
    private final SongMapper songMapper;

    public SongAuditTaskService(AuditTaskMapper taskMapper, SongMapper songMapper) {
        this.taskMapper = taskMapper;
        this.songMapper = songMapper;
    }

    /**
     * 歌曲事务提交后建任务。
     *
     * <p>{@code fallbackExecution = true}：没有活动事务时（直接调 service、单元测试）也要执行；
     * 有事务时只在提交后执行，回滚掉的歌曲不会留下「点进去查无此歌」的任务。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSongSubmitted(SongSubmittedForAuditEvent event) {
        if (event == null || event.getSongId() == null) {
            return;
        }
        try {
            ensureTask(event.getSongId());
        } catch (Exception e) {
            // 建任务失败不影响歌曲本身：此时歌曲事务已提交，这里吞掉只留日志
            log.warn("歌曲审核任务创建失败 songId={} cause={}", event.getSongId(), e.toString());
        }
    }

    /** 历史遗留的待审歌曲：每次启动回填一次，幂等。 */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        try {
            int created = backfillPending(BACKFILL_LIMIT);
            if (created > 0) {
                log.info("启动回填歌曲审核任务 {} 条", created);
            }
        } catch (Exception e) {
            log.warn("启动回填歌曲审核任务失败 cause={}", e.toString());
        }
    }

    /**
     * 给仍然 `audit_status=0` 的歌曲补建任务。
     *
     * @return 实际新建的任务数（已有任务的歌曲不计）
     */
    public int backfillPending(int limit) {
        List<Song> songs = songMapper.selectList(new QueryWrapper<Song>()
                .select("id", "name", "artist_name", "audio_standard", "audit_status")
                .eq("audit_status", 0)
                .orderByAsc("create_time")
                .last("LIMIT " + Math.max(1, limit)));
        int created = 0;
        for (Song song : songs) {
            try {
                if (createTaskIfAbsent(song)) {
                    created++;
                }
            } catch (Exception e) {
                log.warn("回填歌曲审核任务失败 songId={} cause={}", song.getId(), e.toString());
            }
        }
        return created;
    }

    /**
     * 定时对账：补建缺失任务 + 关闭孤儿任务。
     *
     * <p>为什么需要第二件事：歌曲被删除或审核通过后，它原来的 SONG 任务不会自动消失，
     * 审核台里就会留下「点进去查无此歌」的条目；原先只有内容安全侧的定时任务清理领取锁，
     * 没有谁负责关闭这类任务。
     *
     * @return 新建数 + 关闭数，仅供日志展示
     */
    public int reconcile(int limit) {
        int created = backfillPending(limit);
        int closed = closeOrphanTasks(limit);
        if (created > 0 || closed > 0) {
            log.info("歌曲审核任务对账完成 created={} closed={}", created, closed);
        }
        return created + closed;
    }

    /**
     * 关闭「歌曲已删除 / 已不是待审」的未完成任务。
     *
     * <p>用条件更新（`status IN (0,1)`）关闭，多个实例同时跑也只有一次能成功；
     * 单条失败只记日志，不影响其余任务。
     */
    public int closeOrphanTasks(int limit) {
        List<AuditTask> tasks = taskMapper.selectList(Wrappers.<AuditTask>query()
                .select("id", "target_id")
                .eq("task_type", TASK_TYPE_SONG)
                .in("status", AuditTask.STATUS_PENDING, AuditTask.STATUS_CLAIMED)
                .orderByAsc("id")
                .last("LIMIT " + Math.max(1, limit)));
        int closed = 0;
        for (AuditTask task : tasks) {
            try {
                Song song = loadSong(task.getTargetId());
                if (song != null && Integer.valueOf(0).equals(song.getAuditStatus())) {
                    continue;
                }
                LocalDateTime now = LocalDateTime.now();
                int rows = taskMapper.update(null, Wrappers.<AuditTask>update()
                        .eq("id", task.getId())
                        .in("status", AuditTask.STATUS_PENDING, AuditTask.STATUS_CLAIMED)
                        .set("status", AuditTask.STATUS_CLOSED)
                        .set("handled_at", now)
                        .set("handle_result", "AUTO_CLOSE")
                        .set("reject_reason", song == null ? "歌曲已删除" : "歌曲状态已变更")
                        .set("action_applied", 0)
                        .set("update_time", now));
                if (rows > 0) {
                    closed++;
                }
            } catch (Exception e) {
                log.warn("关闭孤儿歌曲审核任务失败 taskId={} cause={}", task.getId(), e.toString());
            }
        }
        return closed;
    }

    /** target_id 是字符串；非法值按「歌曲不存在」处理。 */
    private Song loadSong(String targetId) {
        if (!StringUtils.hasText(targetId)) {
            return null;
        }
        try {
            return songMapper.selectById(Long.valueOf(targetId));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 确保这首歌有一条未完成任务。
     *
     * <p>以数据库当前状态为准再判一次 `audit_status`：事件在提交后处理，
     * 这期间歌曲可能已被别处审核通过，不判就会给一首已通过的歌留下审核任务。
     *
     * @return true 表示本次新建了任务
     */
    public boolean ensureTask(Long songId) {
        if (songId == null) {
            return false;
        }
        Song song = songMapper.selectById(songId);
        return createTaskIfAbsent(song);
    }

    private boolean createTaskIfAbsent(Song song) {
        if (song == null || song.getId() == null
                || !Integer.valueOf(0).equals(song.getAuditStatus())
                || findOpenTask(song.getId()) != null) {
            return false;
        }
        AuditTask task = new AuditTask();
        task.setTaskType(TASK_TYPE_SONG);
        task.setScene(TASK_TYPE_SONG);
        task.setTargetType(TASK_TYPE_SONG);
        task.setTargetId(String.valueOf(song.getId()));
        task.setPriority(AuditTask.PRIORITY_NORMAL);
        task.setStatus(AuditTask.STATUS_PENDING);
        // 后台导入的歌曲没有「C 端提交人」：submitterId 留空，审核结论不伪造站内信收件人
        task.setSubmitterId(null);
        task.setTitle(titleOf(song));
        task.setContentSnapshot(snapshot(song));
        task.setMachineResult("后台创建/导入，待人工审核");
        LocalDateTime now = LocalDateTime.now();
        task.setSlaDeadline(now.plusHours(24));
        task.setEscalated(0);
        task.setActionApplied(0);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        task.setVersion(0);
        try {
            taskMapper.insert(task);
            log.info("创建歌曲审核任务 taskId={} songId={} name={}", task.getId(), song.getId(), song.getName());
            return true;
        } catch (DuplicateKeyException e) {
            // 唯一索引命中：另一个请求已经建好了同一首歌的未完成任务，这是预期结果
            log.info("歌曲审核任务已由并发请求创建 songId={}", song.getId());
            return false;
        } catch (Exception e) {
            log.warn("歌曲审核任务写入失败 songId={} cause={}", song.getId(), e.toString());
            return false;
        }
    }

    /** 未完成任务：待领(0) 或 已领取(1)。已通过/已拒绝/已关闭的历史任务不算。 */
    private Long findOpenTask(Long songId) {
        try {
            AuditTask exists = taskMapper.selectOne(Wrappers.<AuditTask>query()
                    .eq("task_type", TASK_TYPE_SONG)
                    .eq("target_id", String.valueOf(songId))
                    .in("status", AuditTask.STATUS_PENDING, AuditTask.STATUS_CLAIMED)
                    .orderByDesc("id")
                    .last("LIMIT 1"));
            return exists == null ? null : exists.getId();
        } catch (Exception e) {
            // 查询失败时返回 null 继续尝试插入：即使并发下重复插入，
            // 数据库的 uk_audit_open_target 也会兜住，「待审歌曲没有任务」才是更严重的问题
            return null;
        }
    }

    private String titleOf(Song song) {
        String base = StringUtils.hasText(song.getName()) ? song.getName() : ("歌曲 #" + song.getId());
        return StringUtils.hasText(song.getArtistName()) ? base + " - " + song.getArtistName() : base;
    }

    private String snapshot(Song song) {
        StringBuilder sb = new StringBuilder();
        sb.append("歌曲：").append(StringUtils.hasText(song.getName()) ? song.getName() : "-");
        if (StringUtils.hasText(song.getArtistName())) {
            sb.append("\n歌手：").append(song.getArtistName());
        }
        sb.append("\n标准音源：")
                .append(StringUtils.hasText(song.getAudioStandard()) ? song.getAudioStandard() : "（缺失，无法上架）");
        String value = sb.toString();
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }
}
