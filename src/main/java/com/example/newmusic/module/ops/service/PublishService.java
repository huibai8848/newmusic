package com.example.newmusic.module.ops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.newmusic.common.context.LoginUser;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.ops.dto.PublishReq;
import com.example.newmusic.module.ops.dto.RollbackReq;
import com.example.newmusic.module.ops.entity.PublishRecord;
import com.example.newmusic.module.ops.mapper.PublishRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 发布与回滚记录（FR-28-009）。
 *
 * <p><b>能力边界写在最前面：</b>本机没有 K8s / 发布系统 / 制品库，这个服务**不执行真实部署**，
 * 只维护发布状态机与灰度/回滚记录（创建 → 成功/失败 → 回滚）。接真实流水线时，
 * 把 {@link #finish} 与 {@link #rollback} 换成流水线回调即可，表结构与接口不用动。
 *
 * <p>状态机：{@code IN_PROGRESS → SUCCESS|FAILED}；{@code SUCCESS → ROLLED_BACK}。
 * 不回退已回滚记录，也不允许把 FAILED 直接改成 SUCCESS——发布记录是审计证据，不是便签。
 */
@Slf4j
@Service
public class PublishService {

    /** 兼容 PRD/种子里的 ROLLING/BLUE_GREEN/AB 与本模块的 FULL/GRAY 命名。 */
    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
            "FULL", "GRAY", "CANARY", "ROLLING", "BLUE_GREEN", "AB", "ROLLBACK"));

    private final PublishRecordMapper mapper;

    public PublishService(PublishRecordMapper mapper) {
        this.mapper = mapper;
    }

    public Page<PublishRecord> list(String status, String service, int page, int size) {
        LambdaQueryWrapper<PublishRecord> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq(PublishRecord::getStatus, status);
        }
        if (StringUtils.hasText(service)) {
            wrapper.eq(PublishRecord::getService, service);
        }
        wrapper.orderByDesc(PublishRecord::getStartTime).orderByDesc(PublishRecord::getId);
        return mapper.selectPage(new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size))),
                wrapper);
    }

    public PublishRecord get(Long id) {
        PublishRecord record = mapper.selectById(id);
        if (record == null) {
            throw new BizException(ResultCode.OPS_PUBLISH_NOT_FOUND);
        }
        return record;
    }

    @Transactional
    public PublishRecord create(PublishReq req) {
        String type = StringUtils.hasText(req.getPublishType())
                ? req.getPublishType().toUpperCase() : "GRAY";
        if (!TYPES.contains(type) || "ROLLBACK".equals(type)) {
            throw new BizException(ResultCode.PARAM_INVALID,
                    "publishType 只支持 FULL/GRAY/CANARY");
        }
        PublishRecord record = new PublishRecord();
        record.setVersion(req.getVersion());
        record.setService(StringUtils.hasText(req.getService()) ? req.getService() : "newmusic-api");
        record.setEnvironment(StringUtils.hasText(req.getEnvironment()) ? req.getEnvironment() : "prod");
        record.setPublishType(type);
        record.setGitCommit(req.getGitCommit());
        record.setStatus(PublishRecord.STATUS_IN_PROGRESS);
        record.setPublisher(publisher());
        record.setStartTime(LocalDateTime.now());
        record.setRemark(req.getRemark());
        record.setCreateTime(LocalDateTime.now());
        mapper.insert(record);
        log.info("发布创建 id={} version={} type={} publisher={}", record.getId(),
                record.getVersion(), type, record.getPublisher());
        return record;
    }

    @Transactional
    public PublishRecord finish(Long id, boolean success, String message) {
        PublishRecord record = get(id);
        if (!PublishRecord.STATUS_IN_PROGRESS.equals(record.getStatus())) {
            throw new BizException(ResultCode.PARAM_INVALID,
                    "只有 IN_PROGRESS 的发布可以完成，当前为 " + record.getStatus());
        }
        record.setStatus(success ? PublishRecord.STATUS_SUCCESS : PublishRecord.STATUS_FAILED);
        record.setEndTime(LocalDateTime.now());
        if (StringUtils.hasText(message)) {
            record.setRemark(message);
        }
        mapper.updateById(record);
        return record;
    }

    @Transactional
    public Map<String, Object> rollback(Long id, RollbackReq req) {
        PublishRecord record = get(id);
        if (PublishRecord.STATUS_ROLLED_BACK.equals(record.getStatus())) {
            throw new BizException(ResultCode.PARAM_INVALID, "该发布已回滚");
        }
        if (PublishRecord.STATUS_IN_PROGRESS.equals(record.getStatus())) {
            throw new BizException(ResultCode.PARAM_INVALID, "发布进行中，不能回滚");
        }
        String target = req.getTargetVersion();
        if (!StringUtils.hasText(target)) {
            PublishRecord baseline = latestSuccess(record.getService(), record.getEnvironment());
            target = baseline == null ? "v0.19.0" : baseline.getVersion();
        }
        record.setStatus(PublishRecord.STATUS_ROLLED_BACK);
        record.setEndTime(LocalDateTime.now());
        record.setRollbackVersion(target);
        record.setRemark(append(record.getRemark(), "回滚到 " + target
                + (StringUtils.hasText(req.getReason()) ? "：" + req.getReason() : "")));
        mapper.updateById(record);

        PublishRecord rollback = new PublishRecord();
        rollback.setVersion(target);
        rollback.setService(record.getService());
        rollback.setEnvironment(record.getEnvironment());
        rollback.setPublishType("ROLLBACK");
        rollback.setGitCommit(record.getGitCommit());
        rollback.setStatus(PublishRecord.STATUS_SUCCESS);
        rollback.setPublisher(publisher());
        rollback.setStartTime(LocalDateTime.now());
        rollback.setEndTime(LocalDateTime.now());
        rollback.setRollbackVersion(record.getVersion());
        rollback.setRemark("由发布 #" + id + " 回滚产生"
                + (StringUtils.hasText(req.getReason()) ? "：" + req.getReason() : ""));
        rollback.setCreateTime(LocalDateTime.now());
        mapper.insert(rollback);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rolledBack", record);
        result.put("rollbackRecord", rollback);
        result.put("targetVersion", target);
        log.warn("发布回滚 id={} from={} to={} operator={}", id, record.getVersion(), target,
                rollback.getPublisher());
        return result;
    }

    public PublishRecord latestSuccess(String service, String environment) {
        LambdaQueryWrapper<PublishRecord> wrapper = new LambdaQueryWrapper<PublishRecord>()
                .eq(PublishRecord::getStatus, PublishRecord.STATUS_SUCCESS)
                .ne(PublishRecord::getPublishType, "ROLLBACK");
        if (StringUtils.hasText(service)) {
            wrapper.eq(PublishRecord::getService, service);
        }
        if (StringUtils.hasText(environment)) {
            wrapper.eq(PublishRecord::getEnvironment, environment);
        }
        wrapper.orderByDesc(PublishRecord::getEndTime).last("LIMIT 1");
        java.util.List<PublishRecord> list = mapper.selectList(wrapper);
        return list.isEmpty() ? null : list.get(0);
    }

    private static String publisher() {
        LoginUser user = UserContext.get();
        if (user == null) {
            return "system";
        }
        return StringUtils.hasText(user.getUsername()) ? user.getUsername() : String.valueOf(user.getUserId());
    }

    private static String append(String source, String extra) {
        return StringUtils.hasText(source) ? source + "；" + extra : extra;
    }
}
