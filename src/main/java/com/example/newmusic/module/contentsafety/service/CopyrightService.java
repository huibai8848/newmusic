package com.example.newmusic.module.contentsafety.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.contentsafety.dto.ComplaintCreateReq;
import com.example.newmusic.module.contentsafety.dto.ComplaintHandleReq;
import com.example.newmusic.module.contentsafety.dto.CopyrightSaveReq;
import com.example.newmusic.module.contentsafety.entity.CopyrightComplaint;
import com.example.newmusic.module.contentsafety.entity.CopyrightInfo;
import com.example.newmusic.module.contentsafety.mapper.CopyrightComplaintMapper;
import com.example.newmusic.module.contentsafety.mapper.CopyrightInfoMapper;
import com.example.newmusic.module.contentsafety.support.ContactCipher;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.mapper.SongMapper;
import com.example.newmusic.module.music.service.SongService;
import com.example.newmusic.module.ws.entity.Notification;
import com.example.newmusic.module.ws.service.NotificationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 版权信息与侵权投诉（FR-27-008）。
 *
 * <p>授权到期采用「先标记、最后一天自动下架」：
 * 到期前 30/15/7/3/1 天各提醒一次（Redis 当日去重），到期当天调用
 * {@link SongService#changeStatus} 下架，而不是只改本地 status 字段。
 * 投诉联系方式 AES-GCM 加密落库（见 {@link ContactCipher}）。
 */
@Slf4j
@Service
public class CopyrightService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int EXPIRING_DAYS = 30;

    private final CopyrightInfoMapper copyrightMapper;
    private final CopyrightComplaintMapper complaintMapper;
    private final SongMapper songMapper;
    private final SongService songService;
    private final NotificationService notificationService;
    private final ContactCipher contactCipher;
    private final RedisOps redisOps;

    public CopyrightService(CopyrightInfoMapper copyrightMapper,
                            CopyrightComplaintMapper complaintMapper, SongMapper songMapper,
                            SongService songService, NotificationService notificationService,
                            ContactCipher contactCipher, RedisOps redisOps) {
        this.copyrightMapper = copyrightMapper;
        this.complaintMapper = complaintMapper;
        this.songMapper = songMapper;
        this.songService = songService;
        this.notificationService = notificationService;
        this.contactCipher = contactCipher;
        this.redisOps = redisOps;
    }

    public PageResult<CopyrightInfo> page(int pageNum, int pageSize, Integer status, String keyword) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<CopyrightInfo> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                        Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 100));
        QueryWrapper<CopyrightInfo> wrapper = new QueryWrapper<>();
        wrapper.eq(status != null, "status", status);
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like("copyright_owner", keyword).or().like("contract_no", keyword));
        }
        wrapper.orderByAsc("license_end").orderByDesc("id");
        return PageResult.of(copyrightMapper.selectPage(page, wrapper));
    }

    public List<Map<String, Object>> expiring(int days) {
        LocalDate today = LocalDate.now();
        List<CopyrightInfo> list = copyrightMapper.selectExpiring(today.plusDays(
                Math.max(1, Math.min(days, 90))));
        List<Map<String, Object>> out = new ArrayList<>();
        for (CopyrightInfo info : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", String.valueOf(info.getId()));
            row.put("songId", String.valueOf(info.getSongId()));
            row.put("copyrightOwner", info.getCopyrightOwner());
            row.put("licenseEnd", info.getLicenseEnd());
            row.put("daysLeft", info.getLicenseEnd() == null ? null
                    : ChronoUnit.DAYS.between(today, info.getLicenseEnd()));
            row.put("status", info.getStatus());
            out.add(row);
        }
        return out;
    }

    @Transactional
    public CopyrightInfo save(CopyrightSaveReq req) {
        Song song = songMapper.selectById(req.getSongId());
        if (song == null) {
            throw new BizException(ResultCode.CONTENT_NOT_FOUND, "歌曲不存在");
        }
        if (!StringUtils.hasText(req.getCopyrightOwner())
                || req.getLicenseEnd() == null) {
            throw new BizException(ResultCode.COPYRIGHT_INCOMPLETE);
        }
        if (req.getLicenseStart() != null && req.getLicenseStart().isAfter(req.getLicenseEnd())) {
            throw new BizException(ResultCode.PARAM_INVALID, "授权开始时间不能晚于结束时间");
        }
        LocalDate today = LocalDate.now();
        int status = statusOf(req.getLicenseEnd(), today);
        CopyrightInfo exists = copyrightMapper.selectOne(new QueryWrapper<CopyrightInfo>()
                .eq("song_id", req.getSongId()));
        CopyrightInfo info = new CopyrightInfo();
        info.setSongId(req.getSongId());
        info.setCopyrightOwner(req.getCopyrightOwner());
        info.setLicenseType(defaultString(req.getLicenseType(), "NON_EXCLUSIVE"));
        info.setLicenseRegion(req.getLicenseRegion());
        info.setLicenseStart(req.getLicenseStart());
        info.setLicenseEnd(req.getLicenseEnd());
        info.setLicenseScope(req.getLicenseScope());
        info.setContractNo(req.getContractNo());
        info.setContractFile(req.getContractFile());
        info.setStatus(status);
        info.setOperatorId(com.example.newmusic.common.context.UserContext.getUserId());
        info.setUpdateTime(LocalDateTime.now());
        if (exists == null) {
            info.setCreateTime(LocalDateTime.now());
            copyrightMapper.insert(info);
        } else {
            info.setId(exists.getId());
            copyrightMapper.updateById(info);
        }
        return copyrightMapper.selectById(info.getId());
    }

    public CopyrightInfo get(Long id) {
        CopyrightInfo info = copyrightMapper.selectById(id);
        if (info == null) {
            throw new BizException(ResultCode.COPYRIGHT_NOT_FOUND);
        }
        return info;
    }

    public List<CopyrightInfo> bySong(Long songId) {
        return copyrightMapper.selectList(new QueryWrapper<CopyrightInfo>().eq("song_id", songId));
    }

    public boolean regionAllowed(Long songId, String region) {
        List<CopyrightInfo> list = bySong(songId);
        if (list.isEmpty()) {
            return true;
        }
        String wanted = StringUtils.hasText(region) ? region.trim().toUpperCase() : "CN";
        for (CopyrightInfo info : list) {
            if (info.getLicenseEnd() != null && info.getLicenseEnd().isBefore(LocalDate.now())) {
                continue;
            }
            String allowed = info.getLicenseRegion();
            if (!StringUtils.hasText(allowed) || "ALL".equalsIgnoreCase(allowed)
                    || "GLOBAL".equalsIgnoreCase(allowed)) {
                return true;
            }
            for (String part : allowed.split("[,，\\s]+")) {
                if (part.trim().equalsIgnoreCase(wanted)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 版权巡检：更新状态、发送到期提醒、到期自动下架。
     *
     * @return 本日下架的歌曲数
     */
    @Transactional
    public int checkExpiry() {
        LocalDate today = LocalDate.now();
        List<CopyrightInfo> expiring = copyrightMapper.selectExpiring(today.plusDays(EXPIRING_DAYS));
        int offline = 0;
        String day = today.toString();
        for (CopyrightInfo info : expiring) {
            if (info.getLicenseEnd() == null) {
                continue;
            }
            long daysLeft = ChronoUnit.DAYS.between(today, info.getLicenseEnd());
            int status = statusOf(info.getLicenseEnd(), today);
            CopyrightInfo update = new CopyrightInfo();
            update.setId(info.getId());
            update.setStatus(status);
            update.setUpdateTime(LocalDateTime.now());
            copyrightMapper.updateById(update);
            if (daysLeft <= 0) {
                try {
                    songService.changeStatus(info.getSongId(), 0);
                    offline++;
                } catch (Exception e) {
                    log.warn("版权到期自动下架失败 songId={} cause={}", info.getSongId(), e.toString());
                }
                update.setStatus(CopyrightInfo.STATUS_OFFLINE);
                copyrightMapper.updateById(update);
                continue;
            }
            if (daysLeft == 30 || daysLeft == 15 || daysLeft == 7 || daysLeft == 3 || daysLeft == 1) {
                if (redisOps.setIfAbsent(RedisKeys.auditCopyrightNotice(info.getSongId(), day),
                        String.valueOf(daysLeft), Duration.ofDays(2))) {
                    notifyOwner(info, (int) daysLeft);
                }
            }
        }
        // 已过期但不在 30 天窗口内的历史数据，补一次下架判定
        for (CopyrightInfo info : copyrightMapper.selectExpired(today)) {
            if (info.getStatus() != null && info.getStatus() == CopyrightInfo.STATUS_OFFLINE) {
                continue;
            }
            try {
                songService.changeStatus(info.getSongId(), 0);
                CopyrightInfo update = new CopyrightInfo();
                update.setId(info.getId());
                update.setStatus(CopyrightInfo.STATUS_OFFLINE);
                update.setUpdateTime(LocalDateTime.now());
                copyrightMapper.updateById(update);
                offline++;
            } catch (Exception e) {
                log.warn("过期版权补下架失败 songId={} cause={}", info.getSongId(), e.toString());
            }
        }
        if (offline > 0) {
            log.warn("版权到期自动下架 {} 首", offline);
        }
        return offline;
    }

    // ------------------------------------------------------------------ complaints

    @Transactional
    public CopyrightComplaint createComplaint(ComplaintCreateReq req) {
        if (!Boolean.TRUE.equals(req.getStatement())) {
            throw new BizException(ResultCode.PARAM_INVALID, "请确认真实性声明");
        }
        if (req.getSongIds() == null || req.getSongIds().isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID, "请填写侵权歌曲");
        }
        if (!StringUtils.hasText(req.getContact())) {
            throw new BizException(ResultCode.PARAM_INVALID, "请填写联系方式");
        }
        if (!StringUtils.hasText(req.getDescription())) {
            throw new BizException(ResultCode.PARAM_INVALID, "请填写侵权说明");
        }
        for (Long songId : req.getSongIds()) {
            if (songMapper.selectById(songId) == null) {
                throw new BizException(ResultCode.CONTENT_NOT_FOUND, "歌曲不存在：" + songId);
            }
        }
        CopyrightComplaint complaint = new CopyrightComplaint();
        complaint.setComplaintNo(complaintNo());
        complaint.setComplainantName(req.getComplainantName());
        complaint.setComplainantType(defaultString(req.getComplainantType(), "PERSON"));
        complaint.setContactEncrypted(contactCipher.encrypt(req.getContact()));
        complaint.setProofFiles(toJson(req.getProofFiles()));
        complaint.setSongIds(toJson(req.getSongIds()));
        complaint.setDescription(req.getDescription());
        complaint.setStatement(1);
        complaint.setStatus(CopyrightComplaint.STATUS_PENDING);
        complaint.setUploaderNotified(0);
        complaint.setCreateTime(LocalDateTime.now());
        complaint.setUpdateTime(LocalDateTime.now());
        complaintMapper.insert(complaint);
        log.info("侵权投诉登记 complaintId={} no={} songs={}",
                complaint.getId(), complaint.getComplaintNo(), req.getSongIds());
        return sanitize(complaint);
    }

    public PageResult<CopyrightComplaint> complaints(int pageNum, int pageSize, Integer status) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<CopyrightComplaint> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                        Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 100));
        QueryWrapper<CopyrightComplaint> wrapper = new QueryWrapper<>();
        wrapper.eq(status != null, "status", status);
        wrapper.orderByAsc("status").orderByDesc("create_time");
        PageResult<CopyrightComplaint> result = PageResult.of(complaintMapper.selectPage(page, wrapper));
        for (CopyrightComplaint complaint : result.getList()) {
            sanitize(complaint);
        }
        return result;
    }

    public CopyrightComplaint complaint(Long id) {
        CopyrightComplaint complaint = complaintMapper.selectById(id);
        if (complaint == null) {
            throw new BizException(ResultCode.COMPLAINT_NOT_FOUND);
        }
        return sanitize(complaint);
    }

    @Transactional
    public CopyrightComplaint handleComplaint(Long id, ComplaintHandleReq req) {
        CopyrightComplaint complaint = complaintMapper.selectById(id);
        if (complaint == null) {
            throw new BizException(ResultCode.COMPLAINT_NOT_FOUND);
        }
        if (complaint.getStatus() != null
                && complaint.getStatus() != CopyrightComplaint.STATUS_PENDING
                && complaint.getStatus() != CopyrightComplaint.STATUS_PROCESSING) {
            throw new BizException(ResultCode.PARAM_INVALID, "该投诉已处理");
        }
        String action = req.getAction().trim().toUpperCase();
        int status;
        switch (action) {
            case "TAKE_DOWN":
                status = CopyrightComplaint.STATUS_TAKEN_DOWN;
                takeDownSongs(complaint);
                break;
            case "REJECT":
                status = CopyrightComplaint.STATUS_REJECTED;
                break;
            case "NEED_MORE":
                status = CopyrightComplaint.STATUS_PROCESSING;
                break;
            case "COUNTER_NOTICE":
                status = CopyrightComplaint.STATUS_COUNTER;
                break;
            default:
                throw new BizException(ResultCode.PARAM_INVALID, "不支持的处理动作：" + action);
        }
        CopyrightComplaint update = new CopyrightComplaint();
        update.setId(id);
        update.setStatus(status);
        update.setResult(req.getResult());
        update.setOperatorId(com.example.newmusic.common.context.UserContext.getUserId());
        update.setHandleTime(LocalDateTime.now());
        update.setUpdateTime(LocalDateTime.now());
        if (Boolean.TRUE.equals(req.getNotify()) || req.getNotify() == null) {
            update.setUploaderNotified(1);
            notifyUploaders(complaint, action, req.getResult());
        }
        complaintMapper.updateById(update);
        log.info("侵权投诉处理 complaintId={} action={} status={}", id, action, status);
        return sanitize(complaintMapper.selectById(id));
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", copyrightMapper.selectCount(new QueryWrapper<>()));
        out.put("expiring", copyrightMapper.selectCount(new QueryWrapper<CopyrightInfo>()
                .eq("status", CopyrightInfo.STATUS_EXPIRING)));
        out.put("expired", copyrightMapper.selectCount(new QueryWrapper<CopyrightInfo>()
                .eq("status", CopyrightInfo.STATUS_EXPIRED)));
        out.put("complaintsPending", complaintMapper.selectCount(new QueryWrapper<CopyrightComplaint>()
                .eq("status", CopyrightComplaint.STATUS_PENDING)));
        out.put("complaintsTakenDown", complaintMapper.selectCount(new QueryWrapper<CopyrightComplaint>()
                .eq("status", CopyrightComplaint.STATUS_TAKEN_DOWN)));
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private void takeDownSongs(CopyrightComplaint complaint) {
        for (Long songId : parseLongs(complaint.getSongIds())) {
            try {
                songService.changeStatus(songId, 0);
                CopyrightInfo info = copyrightMapper.selectOne(new QueryWrapper<CopyrightInfo>()
                        .eq("song_id", songId));
                if (info != null) {
                    CopyrightInfo update = new CopyrightInfo();
                    update.setId(info.getId());
                    update.setStatus(CopyrightInfo.STATUS_OFFLINE);
                    update.setUpdateTime(LocalDateTime.now());
                    copyrightMapper.updateById(update);
                }
            } catch (Exception e) {
                log.warn("侵权下架失败 songId={} complaintId={} cause={}",
                        songId, complaint.getId(), e.toString());
            }
        }
    }

    private void notifyUploaders(CopyrightComplaint complaint, String action, String result) {
        for (Long songId : parseLongs(complaint.getSongIds())) {
            Song song = songMapper.selectById(songId);
            if (song == null || song.getCreateBy() == null) {
                continue;
            }
            try {
                Notification notification = new Notification();
                notification.setUserId(song.getCreateBy());
                notification.setNotifyType(Notification.TYPE_SYSTEM);
                notification.setTitle("版权投诉处理通知");
                notification.setContent(("TAKE_DOWN".equals(action)
                        ? "您的歌曲《" + song.getName() + "》因版权投诉已下架。"
                        : "您的歌曲《" + song.getName() + "》收到版权投诉，当前处理：" + action)
                        + " " + (result == null ? "" : result));
                notification.setTargetType(Notification.TARGET_SONG);
                notification.setTargetId(songId);
                notification.setCreateTime(LocalDateTime.now());
                notificationService.createAndPush(notification);
            } catch (Exception e) {
                log.warn("版权投诉通知失败 songId={} cause={}", songId, e.toString());
            }
        }
    }

    private void notifyOwner(CopyrightInfo info, int daysLeft) {
        Song song = songMapper.selectById(info.getSongId());
        if (song == null || song.getCreateBy() == null) {
            return;
        }
        try {
            Notification notification = new Notification();
            notification.setUserId(song.getCreateBy());
            notification.setNotifyType(Notification.TYPE_SYSTEM);
            notification.setTitle("版权即将到期");
            notification.setContent("歌曲《" + song.getName() + "》的授权将在 " + daysLeft
                    + " 天后到期（" + info.getLicenseEnd() + "），请及时续约，否则将自动下架。");
            notification.setTargetType(Notification.TARGET_SONG);
            notification.setTargetId(info.getSongId());
            notification.setCreateTime(LocalDateTime.now());
            notificationService.createAndPush(notification);
        } catch (Exception e) {
            log.warn("版权到期提醒失败 songId={} cause={}", info.getSongId(), e.toString());
        }
    }

    private CopyrightComplaint sanitize(CopyrightComplaint complaint) {
        // 列表/详情不回传密文，避免被前端缓存或导出。
        complaint.setContactEncrypted(null);
        return complaint;
    }

    private List<Long> parseLongs(String json) {
        List<Long> out = new ArrayList<>();
        if (!StringUtils.hasText(json)) {
            return out;
        }
        try {
            for (Object item : JSON.readValue(json, new TypeReference<List<Object>>() {
            })) {
                if (item instanceof Number) {
                    out.add(((Number) item).longValue());
                } else {
                    out.add(Long.valueOf(String.valueOf(item)));
                }
            }
        } catch (Exception e) {
            log.warn("投诉歌曲 ID 解析失败：{}", e.toString());
        }
        return out;
    }

    private static int statusOf(LocalDate end, LocalDate today) {
        if (end == null) {
            return CopyrightInfo.STATUS_EXPIRED;
        }
        if (end.isBefore(today)) {
            return CopyrightInfo.STATUS_EXPIRED;
        }
        if (!end.isAfter(today.plusDays(EXPIRING_DAYS))) {
            return CopyrightInfo.STATUS_EXPIRING;
        }
        return CopyrightInfo.STATUS_VALID;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static String complaintNo() {
        return "C" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%04d", java.util.concurrent.ThreadLocalRandom.current().nextInt(10000));
    }

    private static String defaultString(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
