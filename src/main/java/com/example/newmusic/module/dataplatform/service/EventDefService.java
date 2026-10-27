package com.example.newmusic.module.dataplatform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.query.PageQuery;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.dataplatform.dto.EventDefSaveReq;
import com.example.newmusic.module.dataplatform.dto.TrackEventReq;
import com.example.newmusic.module.dataplatform.entity.EventDef;
import com.example.newmusic.module.dataplatform.mapper.EventDefMapper;
import com.example.newmusic.module.dataplatform.support.EventNameValidator;
import com.example.newmusic.module.dataplatform.support.TrackSanitizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** 埋点定义管理（FR-26-001，先文档后埋点）。 */
@Service
public class EventDefService {

    private final EventDefMapper eventDefMapper;
    private final TrackIngestService trackIngestService;

    public EventDefService(EventDefMapper eventDefMapper, TrackIngestService trackIngestService) {
        this.eventDefMapper = eventDefMapper;
        this.trackIngestService = trackIngestService;
    }

    public PageResult<EventDef> list(PageQuery query, String domain, Integer status) {
        LambdaQueryWrapper<EventDef> wrapper = Wrappers.lambdaQuery();
        if (domain != null && !domain.trim().isEmpty()) {
            wrapper.eq(EventDef::getDomain, domain.trim());
        }
        if (status != null) {
            wrapper.eq(EventDef::getStatus, status);
        }
        if (query.hasKeyword()) {
            String kw = query.getKeyword();
            wrapper.and(w -> w.like(EventDef::getEventName, kw).or().like(EventDef::getEventCnName, kw));
        }
        wrapper.orderByAsc(EventDef::getPriority).orderByAsc(EventDef::getId);
        IPage<EventDef> page = eventDefMapper.selectPage(query.toPage(), wrapper);
        return PageResult.of(page);
    }

    public EventDef get(Long id) {
        EventDef def = eventDefMapper.selectById(id);
        if (def == null) {
            throw new BizException(ResultCode.CONTENT_NOT_FOUND, "埋点定义不存在");
        }
        return def;
    }

    @Transactional
    public EventDef create(EventDefSaveReq req) {
        String error = EventNameValidator.validate(req.getEventName());
        if (error != null) {
            throw new BizException(ResultCode.PARAM_INVALID, error);
        }
        Long exists = eventDefMapper.selectCount(
                Wrappers.<EventDef>lambdaQuery().eq(EventDef::getEventName, req.getEventName()));
        if (exists != null && exists > 0) {
            throw new BizException(ResultCode.DUPLICATE_DATA, "事件名已存在");
        }
        EventDef def = new EventDef();
        def.setEventName(req.getEventName().trim());
        apply(def, req);
        def.setVersion(req.getVersion() == null || req.getVersion().trim().isEmpty() ? "v1" : req.getVersion().trim());
        def.setStatus(req.getStatus() == null ? 1 : req.getStatus());
        eventDefMapper.insert(def);
        trackIngestService.reloadDefinitions();
        return def;
    }

    /**
     * 属性只增不减（BR-26-009）：任何语义字段的修改都会让版本号 +1，
     * 便于按版本回溯「这条埋点当时是什么定义」。
     */
    @Transactional
    public EventDef update(Long id, EventDefSaveReq req) {
        EventDef def = get(id);
        String error = EventNameValidator.validate(req.getEventName());
        if (error != null) {
            throw new BizException(ResultCode.PARAM_INVALID, error);
        }
        if (!def.getEventName().equals(req.getEventName())) {
            Long exists = eventDefMapper.selectCount(Wrappers.<EventDef>lambdaQuery()
                    .eq(EventDef::getEventName, req.getEventName())
                    .ne(EventDef::getId, id));
            if (exists != null && exists > 0) {
                throw new BizException(ResultCode.DUPLICATE_DATA, "事件名已存在");
            }
        }
        boolean semanticChanged = !equalsSafe(def.getEventCnName(), req.getEventCnName())
                || !equalsSafe(def.getTriggerDesc(), req.getTriggerDesc())
                || !equalsSafe(def.getProperties(), req.getProperties())
                || !equalsSafe(def.getEventName(), req.getEventName());
        def.setEventName(req.getEventName().trim());
        apply(def, req);
        if (semanticChanged) {
            def.setVersion(nextVersion(def.getVersion()));
        }
        if (req.getStatus() != null) {
            def.setStatus(req.getStatus());
        }
        eventDefMapper.updateById(def);
        trackIngestService.reloadDefinitions();
        return def;
    }

    /** 废弃而非物理删除：历史数据仍需按定义解释。 */
    @Transactional
    public void retire(Long id) {
        EventDef def = get(id);
        def.setStatus(2);
        eventDefMapper.updateById(def);
        trackIngestService.reloadDefinitions();
    }

    /** 埋点验证：只校验，不落库（后台联调用）。 */
    public Map<String, Object> validate(TrackEventReq req) {
        Map<String, Object> result = new LinkedHashMap<>();
        String eventName = req.getEvent();
        if (eventName == null || eventName.trim().isEmpty()) {
            eventName = req.getEventName();
        }
        String nameError = EventNameValidator.validate(eventName);
        EventDef def = nameError == null ? eventDefMapper.selectOne(
                Wrappers.<EventDef>lambdaQuery().eq(EventDef::getEventName, eventName).last("LIMIT 1")) : null;
        boolean valid = nameError == null && def != null && def.getStatus() == 1;
        result.put("valid", valid);
        result.put("eventName", eventName);
        result.put("nameError", nameError);
        result.put("definition", def);
        result.put("documented", def != null);
        result.put("strippedProperties", TrackSanitizer.sanitize(
                req.getProperties() == null ? req.getCommon() : req.getProperties()));
        if (def == null && nameError == null) {
            result.put("message", "事件未登记，按 BR-26-001 不予采集");
        }
        return result;
    }

    private void apply(EventDef def, EventDefSaveReq req) {
        def.setEventCnName(req.getEventCnName());
        def.setDomain(req.getDomain() == null || req.getDomain().trim().isEmpty()
                ? domainOf(req.getEventName()) : req.getDomain().trim());
        def.setTriggerDesc(req.getTriggerDesc());
        def.setProperties(req.getProperties());
        def.setPriority(req.getPriority() == null ? 2 : req.getPriority());
        def.setIsSampled(Boolean.TRUE.equals(req.getSampled()) ? 1 : 0);
        def.setSampleRate(req.getSampleRate() == null ? BigDecimal.ONE : req.getSampleRate());
        def.setOwner(req.getOwner());
    }

    private static String domainOf(String eventName) {
        int idx = eventName == null ? -1 : eventName.indexOf('_');
        return idx > 0 ? eventName.substring(0, idx) : "unknown";
    }

    private static String nextVersion(String version) {
        if (version != null && version.matches("v\\d+")) {
            return "v" + (Integer.parseInt(version.substring(1)) + 1);
        }
        return "v1";
    }

    private static boolean equalsSafe(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
