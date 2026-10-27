package com.example.newmusic.module.admin.service;

import com.example.newmusic.module.admin.entity.OperationLog;
import com.example.newmusic.module.admin.mapper.OperationLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 操作日志落库。调用方（切面）已经完成脱敏与截断。
 *
 * <p>**只增不改不删**：没有 update/delete 接口，表上也没有对应列。
 */
@Slf4j
@Service
public class OperationLogService {

    private final OperationLogMapper operationLogMapper;

    public OperationLogService(OperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
    }

    @Async("adminLogExecutor")
    public void save(OperationLog operationLog) {
        try {
            if (operationLog.getCreateTime() == null) {
                operationLog.setCreateTime(LocalDateTime.now());
            }
            operationLogMapper.insert(operationLog);
        } catch (Exception e) {
            log.error("操作日志写入失败 opType={} operator={} cause={}",
                    operationLog.getOpType(), operationLog.getOperatorId(), e.toString());
        }
    }
}
