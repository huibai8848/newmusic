package com.example.newmusic.module.admin.service;

import com.example.newmusic.module.admin.entity.AuditTask;
import com.example.newmusic.module.admin.mapper.AuditTaskMapper;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.event.SongSubmittedForAuditEvent;
import com.example.newmusic.module.music.mapper.SongMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 待审歌曲 → 审核工作台任务的单测（本次「上架时审核列表里没有这首歌」的回归）。
 *
 * <p>这层测试不连库，锁的是最容易在后续重构中丢掉的几个约定：
 * <ul>
 *   <li>19 位歌曲 ID 必须以**字符串**写入 {@code target_id}，否则前端/审核记录对不上；</li>
 *   <li>同一首歌只能有一条未完成任务（待领/已领），否则审核台会被重复任务刷屏；</li>
 *   <li>以数据库当前 {@code audit_status} 为准：提交后歌曲已被别处审核通过时不能再建任务；</li>
 *   <li>建任务失败不能反向影响歌曲：插入异常必须被吞掉，回填还要继续处理下一首。</li>
 * </ul>
 */
@DisplayName("SongAuditTaskService 待审歌曲任务")
class SongAuditTaskServiceTest {

    private AuditTaskMapper taskMapper;
    private SongMapper songMapper;
    private SongAuditTaskService service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(AuditTaskMapper.class);
        songMapper = mock(SongMapper.class);
        service = new SongAuditTaskService(taskMapper, songMapper);
    }

    @Test
    @DisplayName("待审歌曲建任务：字段齐全、标题/SLA/快照正确")
    void createsTaskForPendingSong() {
        Song song = song(2114675433760747522L, 0, "花海", "周杰伦", "/audio/huahai.mp3");
        when(songMapper.selectById(song.getId())).thenReturn(song);
        when(taskMapper.selectOne(any())).thenReturn(null);
        stubInsert(1001L);

        assertTrue(service.ensureTask(song.getId()));

        AuditTask task = captureInsertedTask();
        assertEquals(SongAuditTaskService.TASK_TYPE_SONG, task.getTaskType());
        assertEquals(SongAuditTaskService.TASK_TYPE_SONG, task.getScene());
        assertEquals(SongAuditTaskService.TASK_TYPE_SONG, task.getTargetType());
        // 19 位 ID 转 Number 会丢精度，必须原样保留字符串
        assertEquals("2114675433760747522", task.getTargetId());
        assertEquals(Integer.valueOf(AuditTask.STATUS_PENDING), task.getStatus());
        assertEquals(Integer.valueOf(AuditTask.PRIORITY_NORMAL), task.getPriority());
        // 后台导入没有 C 端提交人，不能伪造成某个用户
        assertNull(task.getSubmitterId());
        assertEquals("花海 - 周杰伦", task.getTitle());
        assertTrue(task.getContentSnapshot().contains("花海"));
        assertTrue(task.getContentSnapshot().contains("周杰伦"));
        assertTrue(task.getContentSnapshot().contains("/audio/huahai.mp3"));
        assertEquals("后台创建/导入，待人工审核", task.getMachineResult());
        LocalDateTime now = LocalDateTime.now();
        assertTrue(task.getSlaDeadline().isAfter(now.plusHours(23)), "SLA 应约为 24 小时后");
        assertTrue(task.getSlaDeadline().isBefore(now.plusHours(25)), "SLA 应约为 24 小时后");
        assertEquals(Integer.valueOf(0), task.getEscalated());
        assertEquals(Integer.valueOf(0), task.getActionApplied());
        assertEquals(Integer.valueOf(0), task.getVersion());
        verify(taskMapper).insert(any(AuditTask.class));
    }

    @Test
    @DisplayName("歌名/歌手/音源缺失时仍有可读标题，快照提示无法上架")
    void fallsBackWhenMetadataMissing() {
        Song song = song(7L, 0, null, null, null);
        when(songMapper.selectById(7L)).thenReturn(song);
        when(taskMapper.selectOne(any())).thenReturn(null);
        stubInsert(1L);

        assertTrue(service.ensureTask(7L));

        AuditTask task = captureInsertedTask();
        assertEquals("歌曲 #7", task.getTitle());
        assertTrue(task.getContentSnapshot().contains("（缺失，无法上架）"));
    }

    @Test
    @DisplayName("已有未完成任务时不重复建")
    void skipsWhenOpenTaskExists() {
        Song song = song(7L, 0, "晴天", "周杰伦", "/a.mp3");
        AuditTask open = new AuditTask();
        open.setId(88L);
        when(songMapper.selectById(7L)).thenReturn(song);
        when(taskMapper.selectOne(any())).thenReturn(open);

        assertFalse(service.ensureTask(7L));
        verify(taskMapper, never()).insert(any(AuditTask.class));
    }

    @Test
    @DisplayName("审核已通过/歌曲不存在时都不建任务")
    void skipsApprovedOrMissingSong() {
        when(songMapper.selectById(7L)).thenReturn(song(7L, 1, "晴天", "周杰伦", "/a.mp3"));
        assertFalse(service.ensureTask(7L));
        // 已通过的歌连去重查询都不该发
        verify(taskMapper, never()).selectOne(any());

        when(songMapper.selectById(8L)).thenReturn(null);
        assertFalse(service.ensureTask(8L));
        verify(taskMapper, never()).insert(any(AuditTask.class));
    }

    @Test
    @DisplayName("空 ID / 空事件直接忽略，不访问数据库")
    void ignoresNullInputs() {
        assertFalse(service.ensureTask(null));
        service.onSongSubmitted(null);
        service.onSongSubmitted(new SongSubmittedForAuditEvent(null, "x", "y", "/a.mp3"));
        verifyNoInteractions(songMapper, taskMapper);
    }

    @Test
    @DisplayName("事务提交后收到事件 → 按 songId 重新读库并建任务")
    void eventCreatesTask() {
        when(songMapper.selectById(7L)).thenReturn(song(7L, 0, "晴天", "周杰伦", "/a.mp3"));
        when(taskMapper.selectOne(any())).thenReturn(null);
        stubInsert(2L);

        service.onSongSubmitted(new SongSubmittedForAuditEvent(7L, "晴天", "周杰伦", "/a.mp3"));

        verify(taskMapper).insert(any(AuditTask.class));
    }

    @Test
    @DisplayName("插入失败被吞掉：歌曲已提交，不能因审核台故障报错")
    void insertFailureIsSwallowed() {
        when(songMapper.selectById(7L)).thenReturn(song(7L, 0, "晴天", "周杰伦", "/a.mp3"));
        when(taskMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.insert(any(AuditTask.class))).thenThrow(new IllegalStateException("db down"));

        assertFalse(service.ensureTask(7L));
    }

    @Test
    @DisplayName("去重查询异常时宁可重复也继续插入")
    void dedupeQueryFailureStillInserts() {
        when(songMapper.selectById(7L)).thenReturn(song(7L, 0, "晴天", "周杰伦", "/a.mp3"));
        when(taskMapper.selectOne(any())).thenThrow(new IllegalStateException("query timeout"));
        stubInsert(3L);

        assertTrue(service.ensureTask(7L));
        verify(taskMapper).insert(any(AuditTask.class));
    }

    @Test
    @DisplayName("启动回填：只给没有未完成任务的待审歌建，已通过的跳过")
    void backfillCreatesMissingTasksOnly() {
        Song first = song(1L, 0, "甲", "A", "/1.mp3");
        Song second = song(2L, 0, "乙", "B", "/2.mp3");
        Song approved = song(3L, 1, "丙", "C", "/3.mp3");
        when(songMapper.selectList(any())).thenReturn(Arrays.asList(first, second, approved));
        AuditTask open = new AuditTask();
        open.setId(99L);
        // 第一首没有任务；第二首已经有任务
        when(taskMapper.selectOne(any())).thenReturn(null, open);
        stubInsert(10L);

        int created = service.backfillPending(10);

        assertEquals(1, created);
        verify(taskMapper, times(1)).insert(any(AuditTask.class));
    }

    @Test
    @DisplayName("回填单首失败不影响后续歌曲")
    void backfillContinuesAfterOneFailure() {
        when(songMapper.selectList(any())).thenReturn(Arrays.asList(
                song(1L, 0, "甲", "A", "/1.mp3"),
                song(2L, 0, "乙", "B", "/2.mp3")));
        when(taskMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.insert(any(AuditTask.class)))
                .thenThrow(new IllegalStateException("dup key"))
                .thenAnswer(invocation -> {
                    invocation.getArgument(0, AuditTask.class).setId(11L);
                    return 1;
                });

        assertEquals(1, service.backfillPending(10));
        verify(taskMapper, times(2)).insert(any(AuditTask.class));
    }

    @Test
    @DisplayName("回填没有待审歌时返回 0")
    void backfillWithNoSongs() {
        when(songMapper.selectList(any())).thenReturn(Collections.emptyList());
        assertEquals(0, service.backfillPending(500));
        verify(taskMapper, never()).insert(any(AuditTask.class));
    }

    @Test
    @DisplayName("并发撞 uk_audit_open_target：DuplicateKeyException 视为已建，不记失败告警")
    void duplicateKeyIsExpectedOutcome() {
        when(songMapper.selectById(7L)).thenReturn(song(7L, 0, "晴天", "周杰伦", "/a.mp3"));
        when(taskMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.insert(any(AuditTask.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'uk_audit_open_target'"));

        assertFalse(service.ensureTask(7L));
    }

    @Test
    @DisplayName("对账关闭孤儿任务：歌曲已通过/已删除/ID 非法的关闭，仍待审的保留")
    void closesOrphanTasks() {
        when(taskMapper.selectList(any())).thenReturn(Arrays.asList(
                task(1L, "1"),
                task(2L, "2"),
                task(3L, "3"),
                task(4L, "not-a-number")));
        // 1 仍待审 → 保留；2 已通过 → 关闭；3 歌曲已删除 → 关闭；4 无法解析 → 关闭
        when(songMapper.selectById(1L)).thenReturn(song(1L, 0, "甲", "A", "/1.mp3"));
        when(songMapper.selectById(2L)).thenReturn(song(2L, 1, "乙", "B", "/2.mp3"));
        when(songMapper.selectById(3L)).thenReturn(null);
        when(taskMapper.update(isNull(), any())).thenReturn(1);

        assertEquals(3, service.closeOrphanTasks(10));
        verify(taskMapper, times(3)).update(isNull(), any());
        verify(songMapper, never()).selectById(4L);
    }

    @Test
    @DisplayName("关闭单条失败只记日志，继续处理下一条")
    void closeOrphanContinuesAfterFailure() {
        when(taskMapper.selectList(any())).thenReturn(Arrays.asList(task(1L, "1"), task(2L, "2")));
        when(songMapper.selectById(1L)).thenReturn(null);
        when(songMapper.selectById(2L)).thenReturn(null);
        when(taskMapper.update(isNull(), any()))
                .thenThrow(new IllegalStateException("lock wait timeout"))
                .thenReturn(1);

        assertEquals(1, service.closeOrphanTasks(10));
        verify(taskMapper, times(2)).update(isNull(), any());
    }

    @Test
    @DisplayName("reconcile = 回填新建数 + 关闭孤儿数")
    void reconcileCombinesBackfillAndClose() {
        when(songMapper.selectList(any())).thenReturn(Collections.singletonList(
                song(1L, 0, "甲", "A", "/1.mp3")));
        when(taskMapper.selectOne(any())).thenReturn(null);
        stubInsert(21L);
        when(taskMapper.selectList(any())).thenReturn(Collections.singletonList(task(9L, "9")));
        when(songMapper.selectById(9L)).thenReturn(song(9L, 2, "乙", "B", "/9.mp3"));
        when(taskMapper.update(isNull(), any())).thenReturn(1);

        assertEquals(2, service.reconcile(50));
        verify(taskMapper).insert(any(AuditTask.class));
        verify(taskMapper).update(isNull(), any());
    }

    // ---------------------------------------------------------------- helpers

    private Song song(Long id, int auditStatus, String name, String artistName, String audioStandard) {
        Song song = new Song();
        song.setId(id);
        song.setAuditStatus(auditStatus);
        song.setName(name);
        song.setArtistName(artistName);
        song.setAudioStandard(audioStandard);
        return song;
    }

    private AuditTask task(Long id, String targetId) {
        AuditTask task = new AuditTask();
        task.setId(id);
        task.setTaskType(SongAuditTaskService.TASK_TYPE_SONG);
        task.setTargetId(targetId);
        task.setStatus(AuditTask.STATUS_PENDING);
        return task;
    }

    private void stubInsert(Long taskId) {
        when(taskMapper.insert(any(AuditTask.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, AuditTask.class).setId(taskId);
            return 1;
        });
    }

    private AuditTask captureInsertedTask() {
        ArgumentCaptor<AuditTask> captor = ArgumentCaptor.forClass(AuditTask.class);
        verify(taskMapper).insert(captor.capture());
        return captor.getValue();
    }
}
