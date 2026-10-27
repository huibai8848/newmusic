package com.example.newmusic.module.dict.service;

import com.example.newmusic.common.context.LoginUser;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.module.dict.entity.DictChangeLog;
import com.example.newmusic.module.dict.mapper.DictChangeLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 变更历史写入（BR-20-068）。
 *
 * <p>日志失败不能影响主操作：词典本身已经改成功了，因为审计表写不进去就回滚，
 * 等于让一次正确的运营操作失败。因此这里是 catch 后 warn，而不是把异常抛给调用方。
 */
@Slf4j
@Service
public class DictChangeLogService {

    private final DictChangeLogMapper mapper;
    private final ObjectMapper objectMapper;

    public DictChangeLogService(DictChangeLogMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void success(String dictType, String changeType, Long targetId,
                        Object before, Object after, String remark) {
        write(dictType, changeType, targetId, before, after, remark, "SUCCESS");
    }

    public void failure(String dictType, String changeType, Long targetId,
                        Object before, Object after, String remark) {
        write(dictType, changeType, targetId, before, after, remark, "FAIL");
    }

    private void write(String dictType, String changeType, Long targetId,
                       Object before, Object after, String remark, String result) {
        try {
            LoginUser operator = UserContext.get();
            DictChangeLog entry = new DictChangeLog();
            entry.setDictType(dictType);
            entry.setChangeType(changeType);
            entry.setTargetId(targetId);
            entry.setBeforeValue(json(before));
            entry.setAfterValue(json(after));
            entry.setOperatorId(operator == null ? null : operator.getUserId());
            entry.setOperatorName(operator == null ? "SYSTEM"
                    : (operator.getNickname() == null ? operator.getUsername() : operator.getNickname()));
            entry.setRemark(remark);
            entry.setResult(result);
            mapper.insert(entry);
        } catch (RuntimeException e) {
            log.warn("写词典变更历史失败 type={} change={} cause={}", dictType, changeType, e.toString());
        }
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
