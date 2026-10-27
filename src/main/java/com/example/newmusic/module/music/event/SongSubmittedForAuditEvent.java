package com.example.newmusic.module.music.event;

/**
 * 「歌曲进入待审」事件（模块 09 审核工作台的歌曲来源）。
 *
 * <p>为什么用事件而不是直接调 {@code AuditService}：admin 模块已经依赖 music（审核结论要回写歌曲状态），
 * 反向再依赖一次就构成构造器循环依赖。事件让 music 只负责「我提交了一首待审歌」，
 * 由 admin 侧决定要不要建任务——两边都不需要知道对方的实现。
 *
 * <p>**只在事务内发布、提交后处理**：导入是逐行事务的，若任务先建、歌曲事务回滚，
 * 审核台上会出现一条点进去查无此歌的任务。
 */
public class SongSubmittedForAuditEvent {

    private final Long songId;
    private final String name;
    private final String artistName;
    private final String audioStandard;

    public SongSubmittedForAuditEvent(Long songId, String name, String artistName, String audioStandard) {
        this.songId = songId;
        this.name = name;
        this.artistName = artistName;
        this.audioStandard = audioStandard;
    }

    public Long getSongId() {
        return songId;
    }

    public String getName() {
        return name;
    }

    public String getArtistName() {
        return artistName;
    }

    public String getAudioStandard() {
        return audioStandard;
    }
}
