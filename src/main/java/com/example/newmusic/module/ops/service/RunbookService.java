package com.example.newmusic.module.ops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.ops.entity.OpsRunbook;
import com.example.newmusic.module.ops.mapper.OpsRunbookMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 应急预案（AC-28-179）。
 *
 * <p>每个告警规则通过 {@code runbook_key} 关联一份处置步骤；告警详情页直接展示，
 * 避免「收到告警 → 去 Wiki 翻文档」的两跳。内容由 SQL 种子维护，接口只读。
 */
@Service
public class RunbookService {

    private final OpsRunbookMapper mapper;

    public RunbookService(OpsRunbookMapper mapper) {
        this.mapper = mapper;
    }

    public List<OpsRunbook> list(String severity) {
        LambdaQueryWrapper<OpsRunbook> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(severity)) {
            wrapper.eq(OpsRunbook::getSeverity, severity.toUpperCase());
        }
        wrapper.orderByAsc(OpsRunbook::getSeverity).orderByAsc(OpsRunbook::getRunbookKey);
        return mapper.selectList(wrapper);
    }

    public OpsRunbook get(String key) {
        OpsRunbook runbook = mapper.selectOne(new LambdaQueryWrapper<OpsRunbook>()
                .eq(OpsRunbook::getRunbookKey, key).last("LIMIT 1"));
        if (runbook == null) {
            throw new BizException(ResultCode.OPS_RUNBOOK_NOT_FOUND);
        }
        return runbook;
    }
}
