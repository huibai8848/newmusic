package com.example.newmusic.module.dataplatform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.common.context.LoginUser;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.query.PageQuery;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.dataplatform.dto.MetricApproveReq;
import com.example.newmusic.module.dataplatform.dto.MetricSaveReq;
import com.example.newmusic.module.dataplatform.entity.MetricChangeLog;
import com.example.newmusic.module.dataplatform.entity.MetricDef;
import com.example.newmusic.module.dataplatform.mapper.MetricChangeLogMapper;
import com.example.newmusic.module.dataplatform.mapper.MetricDefMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标定义与口径变更（FR-26-003）。
 *
 * <p>「口径变更必须审批」不是一句提示：只要修改的是已生效指标的 {@code definition}，
 * 新口径先落 {@code t_metric_change_log} 待审，指标本身的定义保持不动——否则审批还没过，
 * 看板就已经按新口径出数了。
 */
@Slf4j
@Service
public class MetricService {

    private final MetricDefMapper metricDefMapper;
    private final MetricChangeLogMapper changeLogMapper;

    public MetricService(MetricDefMapper metricDefMapper, MetricChangeLogMapper changeLogMapper) {
        this.metricDefMapper = metricDefMapper;
        this.changeLogMapper = changeLogMapper;
    }

    public PageResult<MetricDef> list(PageQuery query, String category, Integer status) {
        LambdaQueryWrapper<MetricDef> wrapper = Wrappers.lambdaQuery();
        if (category != null && !category.trim().isEmpty()) {
            wrapper.eq(MetricDef::getCategory, category.trim());
        }
        if (status != null) {
            wrapper.eq(MetricDef::getStatus, status);
        }
        if (query.hasKeyword()) {
            String kw = query.getKeyword();
            wrapper.and(w -> w.like(MetricDef::getMetricId, kw).or().like(MetricDef::getMetricName, kw));
        }
        wrapper.orderByAsc(MetricDef::getCategory).orderByAsc(MetricDef::getMetricId);
        IPage<MetricDef> page = metricDefMapper.selectPage(query.toPage(), wrapper);
        return PageResult.of(page);
    }

    public MetricDef get(String metricId) {
        MetricDef def = findByMetricId(metricId);
        if (def == null) {
            throw new BizException(ResultCode.CONTENT_NOT_FOUND, "指标不存在");
        }
        return def;
    }

    public Map<String, Object> detail(String metricId) {
        MetricDef def = get(metricId);
        List<MetricChangeLog> logs = changeLogMapper.selectList(
                Wrappers.<MetricChangeLog>lambdaQuery()
                        .eq(MetricChangeLog::getMetricId, metricId)
                        .orderByDesc(MetricChangeLog::getCreateTime)
                        .last("LIMIT 20"));
        MetricChangeLog pending = latestPending(metricId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metric", def);
        result.put("changeLogs", logs);
        result.put("pendingChange", pending);
        return result;
    }

    @Transactional
    public Map<String, Object> create(MetricSaveReq req) {
        if (findByMetricId(req.getMetricId()) != null) {
            throw new BizException(ResultCode.DUPLICATE_DATA, "指标 ID 已存在");
        }
        MetricDef def = new MetricDef();
        def.setMetricId(req.getMetricId().trim());
        apply(def, req);
        def.setVersion(req.getVersion() == null || req.getVersion().trim().isEmpty() ? "v1.0" : req.getVersion().trim());
        def.setStatus(req.getStatus() == null ? 1 : req.getStatus());
        metricDefMapper.insert(def);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metric", def);
        result.put("pendingApproval", false);
        return result;
    }

    @Transactional
    public Map<String, Object> update(String metricId, MetricSaveReq req) {
        MetricDef def = findByMetricId(metricId);
        if (def == null) {
            throw new BizException(ResultCode.CONTENT_NOT_FOUND, "指标不存在");
        }
        boolean definitionChanged = !equalsSafe(def.getDefinition(), req.getDefinition());
        boolean active = def.getStatus() != null && def.getStatus() == 1;

        Map<String, Object> result = new LinkedHashMap<>();
        if (definitionChanged && active) {
            if (isBlank(req.getReason())) {
                throw new BizException(ResultCode.PARAM_INVALID, "修改已生效指标的口径必须填写变更原因");
            }
            MetricChangeLog change = new MetricChangeLog();
            change.setMetricId(metricId);
            change.setOldDefinition(def.getDefinition());
            change.setNewDefinition(req.getDefinition());
            change.setReason(req.getReason());
            LoginUser admin = UserContext.get();
            change.setApplicantId(admin == null ? null : admin.getUserId());
            change.setStatus(0);
            changeLogMapper.insert(change);

            // 非口径字段仍可即时生效；口径本身等审批
            applyWithoutDefinition(def, req);
            metricDefMapper.updateById(def);
            result.put("metric", def);
            result.put("pendingApproval", true);
            result.put("changeLog", change);
            result.put("message", "口径变更已提交审批，审批通过前指标继续使用旧口径");
            return result;
        }

        apply(def, req);
        if (req.getVersion() != null && !req.getVersion().trim().isEmpty()) {
            def.setVersion(req.getVersion().trim());
        } else if (definitionChanged) {
            def.setVersion(nextVersion(def.getVersion()));
        }
        if (req.getStatus() != null) {
            def.setStatus(req.getStatus());
        }
        metricDefMapper.updateById(def);
        result.put("metric", def);
        result.put("pendingApproval", false);
        return result;
    }

    @Transactional
    public MetricChangeLog approve(String metricId, MetricApproveReq req) {
        MetricChangeLog change = latestPending(metricId);
        if (change == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "没有待审批的口径变更");
        }
        if (!Boolean.TRUE.equals(req.getApprove())) {
            change.setStatus(2);
            change.setApprover(operatorName());
            changeLogMapper.updateById(change);
            return change;
        }
        MetricDef def = get(metricId);
        def.setDefinition(change.getNewDefinition());
        def.setVersion(nextVersion(def.getVersion()));
        metricDefMapper.updateById(def);

        change.setStatus(1);
        change.setApprover(operatorName());
        change.setEffectiveDate(parseDate(req.getEffectiveDate()));
        changeLogMapper.updateById(change);
        return change;
    }

    public List<MetricChangeLog> changeLogs(String metricId, Integer status) {
        LambdaQueryWrapper<MetricChangeLog> wrapper = Wrappers.<MetricChangeLog>lambdaQuery()
                .eq(MetricChangeLog::getMetricId, metricId);
        if (status != null) {
            wrapper.eq(MetricChangeLog::getStatus, status);
        }
        wrapper.orderByDesc(MetricChangeLog::getCreateTime);
        return changeLogMapper.selectList(wrapper);
    }

    public void retire(String metricId) {
        MetricDef def = get(metricId);
        def.setStatus(2);
        metricDefMapper.updateById(def);
    }

    // ------------------------------------------------------------------ 工具

    private MetricDef findByMetricId(String metricId) {
        if (isBlank(metricId)) {
            return null;
        }
        return metricDefMapper.selectOne(Wrappers.<MetricDef>lambdaQuery()
                .eq(MetricDef::getMetricId, metricId).last("LIMIT 1"));
    }

    private MetricChangeLog latestPending(String metricId) {
        return changeLogMapper.selectOne(Wrappers.<MetricChangeLog>lambdaQuery()
                .eq(MetricChangeLog::getMetricId, metricId)
                .eq(MetricChangeLog::getStatus, 0)
                .orderByDesc(MetricChangeLog::getCreateTime)
                .last("LIMIT 1"));
    }

    private void apply(MetricDef def, MetricSaveReq req) {
        def.setMetricName(req.getMetricName());
        def.setCategory(isBlank(req.getCategory()) ? "其他" : req.getCategory());
        def.setDefinition(req.getDefinition());
        applyWithoutDefinition(def, req);
    }

    private void applyWithoutDefinition(MetricDef def, MetricSaveReq req) {
        def.setMetricName(req.getMetricName());
        def.setCategory(isBlank(req.getCategory()) ? def.getCategory() : req.getCategory());
        def.setCalculation(req.getCalculation());
        def.setDataSource(req.getDataSource());
        def.setDimensions(req.getDimensions());
        def.setUpdateFreq(isBlank(req.getUpdateFreq()) ? "T+1" : req.getUpdateFreq());
        def.setOwner(req.getOwner());
    }

    private String operatorName() {
        LoginUser admin = UserContext.get();
        return admin == null ? "system" : admin.getUsername();
    }

    static String nextVersion(String version) {
        if (version != null && version.matches("v\\d+\\.\\d+")) {
            int dot = version.indexOf('.');
            return version.substring(0, dot + 1) + (Integer.parseInt(version.substring(dot + 1)) + 1);
        }
        return "v1.1";
    }

    private static LocalDate parseDate(String text) {
        if (isBlank(text)) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(text);
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_INVALID, "effectiveDate 格式应为 yyyy-MM-dd");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean equalsSafe(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
