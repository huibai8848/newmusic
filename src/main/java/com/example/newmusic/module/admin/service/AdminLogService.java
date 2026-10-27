package com.example.newmusic.module.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.module.admin.dto.AdminLogQueryReq;
import com.example.newmusic.module.admin.dto.LoginLogQueryReq;
import com.example.newmusic.module.admin.entity.LoginLog;
import com.example.newmusic.module.admin.entity.OperationLog;
import com.example.newmusic.module.admin.mapper.LoginLogMapper;
import com.example.newmusic.module.admin.mapper.OperationLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;

/**
 * 操作日志 / 登录日志查询。日志表只读，接口层不提供任何修改入口。
 *
 * <p>{@code onlySelf} 由 Controller 依据权限决定：只有 {@code admin:log:view:self}
 * 的运营只能看到自己的操作与登录记录，避免越权窥探其他管理员行为。
 */
@Service
public class AdminLogService {

    private static final int MAX_EXPORT = 20000;
    private static final String HEADER = "ID,时间,操作人ID,操作人,类型,目标类型,目标ID,描述,IP,结果,错误";

    private final OperationLogMapper operationLogMapper;
    private final LoginLogMapper loginLogMapper;

    public AdminLogService(OperationLogMapper operationLogMapper, LoginLogMapper loginLogMapper) {
        this.operationLogMapper = operationLogMapper;
        this.loginLogMapper = loginLogMapper;
    }

    public PageResult<OperationLog> operations(AdminLogQueryReq query, boolean onlySelf) {
        query.normalize();
        return PageResult.of(operationLogMapper.selectPage(query.toPage(),
                operationWrapper(query, onlySelf)), query.isTruncated());
    }

    public PageResult<LoginLog> loginLogs(LoginLogQueryReq query, boolean onlySelf) {
        query.normalize();
        QueryWrapper<LoginLog> wrapper = new QueryWrapper<>();
        if (onlySelf) {
            wrapper.eq("user_id", UserContext.requireUserId()).eq("login_type", LoginLog.TYPE_ADMIN);
        }
        if (query.getLoginType() != null) {
            wrapper.eq("login_type", query.getLoginType());
        }
        if (query.getResult() != null) {
            wrapper.eq("result", query.getResult());
        }
        if (Boolean.TRUE.equals(query.getSuspicious())) {
            wrapper.eq("suspicious", 1);
        }
        if (StringUtils.hasText(query.getIp())) {
            wrapper.eq("ip", query.getIp());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.like("username", query.getKeyword());
        }
        if (query.getStartDate() != null) {
            wrapper.ge("create_time", query.getStartDate().atStartOfDay());
        }
        if (query.getEndDate() != null) {
            wrapper.le("create_time", query.getEndDate().atTime(LocalTime.MAX));
        }
        wrapper.orderByDesc("create_time").orderByDesc("id");
        return PageResult.of(loginLogMapper.selectPage(query.toPage(), wrapper), query.isTruncated());
    }

    /** 导出操作日志 CSV（仅超管）。最多 20000 行，同样不含请求快照。 */
    public void exportOperations(AdminLogQueryReq query, boolean onlySelf,
                                 OutputStream outputStream) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append('﻿').append(HEADER).append('\n');
        int pageNum = 1;
        int exported = 0;
        while (exported < MAX_EXPORT) {
            query.setPageNum(pageNum);
            query.setPageSize(100);
            PageResult<OperationLog> page = PageResult.of(
                    operationLogMapper.selectPage(query.toPage(), operationWrapper(query, onlySelf)));
            if (page.getList() == null || page.getList().isEmpty()) {
                break;
            }
            for (OperationLog log : page.getList()) {
                sb.append(csv(String.valueOf(log.getId()))).append(',')
                        .append(csv(log.getCreateTime() == null ? "" : log.getCreateTime().toString())).append(',')
                        .append(csv(log.getOperatorId() == null ? "" : String.valueOf(log.getOperatorId()))).append(',')
                        .append(csv(log.getOperatorName())).append(',')
                        .append(csv(log.getOpType())).append(',')
                        .append(csv(log.getTargetType())).append(',')
                        .append(csv(log.getTargetId())).append(',')
                        .append(csv(log.getDescription())).append(',')
                        .append(csv(log.getIp())).append(',')
                        .append(csv(Integer.valueOf(1).equals(log.getResult()) ? "成功" : "失败")).append(',')
                        .append(csv(log.getErrorMsg()))
                        .append('\n');
                exported++;
                if (exported >= MAX_EXPORT) {
                    break;
                }
            }
            if (!page.isHasNext()) {
                break;
            }
            pageNum++;
        }
        outputStream.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private QueryWrapper<OperationLog> operationWrapper(AdminLogQueryReq query, boolean onlySelf) {
        QueryWrapper<OperationLog> wrapper = new QueryWrapper<>();
        if (onlySelf) {
            wrapper.eq("operator_id", UserContext.requireUserId());
        } else if (query.getOperatorId() != null) {
            wrapper.eq("operator_id", query.getOperatorId());
        }
        if (StringUtils.hasText(query.getOpType())) {
            wrapper.eq("op_type", query.getOpType());
        }
        if (StringUtils.hasText(query.getTargetType())) {
            wrapper.eq("target_type", query.getTargetType());
        }
        if (query.getResult() != null) {
            wrapper.eq("result", query.getResult());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.and(q -> q.like("description", query.getKeyword())
                    .or().like("operator_name", query.getKeyword())
                    .or().like("target_id", query.getKeyword()));
        }
        if (query.getStartDate() != null) {
            wrapper.ge("create_time", query.getStartDate().atStartOfDay());
        }
        if (query.getEndDate() != null) {
            wrapper.le("create_time", query.getEndDate().atTime(LocalTime.MAX));
        }
        wrapper.orderByDesc("create_time").orderByDesc("id");
        return wrapper;
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
