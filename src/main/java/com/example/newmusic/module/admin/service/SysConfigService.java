package com.example.newmusic.module.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.newmusic.common.context.LoginUser;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.query.PageQuery;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.admin.dto.SysConfigSaveReq;
import com.example.newmusic.module.admin.dto.SysConfigUpdateReq;
import com.example.newmusic.module.admin.entity.SysConfig;
import com.example.newmusic.module.admin.entity.SysConfigHistory;
import com.example.newmusic.module.admin.mapper.SysConfigHistoryMapper;
import com.example.newmusic.module.admin.mapper.SysConfigMapper;
import com.example.newmusic.module.search.support.SensitiveWordSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 系统配置：类型校验 → 落库 → 写变更历史 → 触发已知消费者刷新。
 *
 * <p>秘密类配置键（password/secret/token/private_key…）**拒绝**写入
 * （80026）：配置表会被导出、会被截图，不适合当密钥仓库。
 *
 * <p>热更新：当前唯一从 {@code t_sys_config} 读取的消费者是搜索敏感词词典
 * （{@link SensitiveWordSupport}，5 秒刷新窗口）；修改该键时主动 {@code invalidate()}。
 * 其余配置项只保证「落库 + 可回滚」，是否生效取决于对应模块是否在启动时读取——
 * 不做「改完假装已生效」的表面文章。
 */
@Slf4j
@Service
public class SysConfigService {

    private static final Pattern SECRET_KEY = Pattern.compile(
            ".*(password|passwd|secret|token|private[_-]?key|access[_-]?key|credential).*",
            Pattern.CASE_INSENSITIVE);
    private static final int MAX_VALUE_LENGTH = 10000;

    private final SysConfigMapper configMapper;
    private final SysConfigHistoryMapper historyMapper;
    private final SensitiveWordSupport sensitiveWordSupport;
    private final ObjectMapper objectMapper;

    public SysConfigService(SysConfigMapper configMapper,
                            SysConfigHistoryMapper historyMapper,
                            SensitiveWordSupport sensitiveWordSupport,
                            ObjectMapper objectMapper) {
        this.configMapper = configMapper;
        this.historyMapper = historyMapper;
        this.sensitiveWordSupport = sensitiveWordSupport;
        this.objectMapper = objectMapper;
    }

    public PageResult<SysConfig> page(PageQuery query, String group) {
        query.normalize();
        QueryWrapper<SysConfig> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(group)) {
            wrapper.eq("config_group", group);
        }
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.and(q -> q.like("config_key", query.getKeyword())
                    .or().like("description", query.getKeyword()));
        }
        wrapper.orderByAsc("config_group").orderByAsc("config_key");
        return PageResult.of(configMapper.selectPage(query.toPage(), wrapper), query.isTruncated());
    }

    @Transactional
    public Long create(SysConfigSaveReq req) {
        if (configMapper.selectCount(new QueryWrapper<SysConfig>()
                .eq("config_key", req.getConfigKey())) > 0) {
            throw new BizException(ResultCode.DUPLICATE_DATA, "配置键已存在");
        }
        int type = req.getValueType() == null ? SysConfig.TYPE_STRING : req.getValueType();
        validateKey(req.getConfigKey());
        validateValue(type, req.getConfigValue());

        SysConfig config = new SysConfig();
        config.setConfigKey(req.getConfigKey());
        config.setConfigValue(req.getConfigValue());
        config.setValueType(type);
        config.setConfigGroup(req.getConfigGroup());
        config.setDescription(req.getDescription());
        config.setIsPublic(0);
        config.setStatus(1);
        config.setUpdateBy(UserContext.requireUserId());
        configMapper.insert(config);
        writeHistory(req.getConfigKey(), null, req.getConfigValue(), type,
                "UPDATE", "新增配置");
        invalidateIfNeeded(req.getConfigKey());
        return config.getId();
    }

    @Transactional
    public void update(Long id, SysConfigUpdateReq req) {
        SysConfig config = require(id);
        if (MAX_VALUE_LENGTH < req.getConfigValue().length()) {
            throw new BizException(ResultCode.CONFIG_VALUE_INVALID, "配置值不能超过 10000 字符");
        }
        validateValue(config.getValueType(), req.getConfigValue());

        String oldValue = config.getConfigValue();
        config.setConfigValue(req.getConfigValue());
        config.setUpdateBy(UserContext.requireUserId());
        configMapper.updateById(config);
        writeHistory(config.getConfigKey(), oldValue, req.getConfigValue(),
                config.getValueType(), "UPDATE", req.getRemark());
        invalidateIfNeeded(config.getConfigKey());
        log.info("系统配置已修改 key={} by={}", config.getConfigKey(), UserContext.getUserId());
    }

    public List<SysConfigHistory> history(String configKey) {
        return historyMapper.selectList(new QueryWrapper<SysConfigHistory>()
                .eq("config_key", configKey)
                .orderByDesc("create_time")
                .last("LIMIT 100"));
    }

    public List<SysConfigHistory> historyOfConfig(Long id) {
        return history(require(id).getConfigKey());
    }

    /** 按配置键取（模块19配置中心用键而非自增 ID 操作）。 */
    public SysConfig getByKey(String key) {
        return requireByKey(key);
    }

    public void updateByKey(String key, SysConfigUpdateReq req) {
        update(requireByKey(key).getId(), req);
    }

    public List<SysConfigHistory> historyOfKey(String key) {
        return history(requireByKey(key).getConfigKey());
    }

    @Transactional
    public void rollback(Long historyId) {
        SysConfigHistory history = historyMapper.selectById(historyId);
        if (history == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "历史记录不存在");
        }
        SysConfig config = configMapper.selectOne(new QueryWrapper<SysConfig>()
                .eq("config_key", history.getConfigKey()).last("LIMIT 1"));
        if (config == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "配置已被删除，无法回滚");
        }
        String target = history.getOldValue();
        validateValue(config.getValueType(), target);
        String oldValue = config.getConfigValue();
        config.setConfigValue(target);
        config.setUpdateBy(UserContext.requireUserId());
        configMapper.updateById(config);
        writeHistory(config.getConfigKey(), oldValue, target, config.getValueType(),
                "ROLLBACK", "回滚到历史记录 #" + historyId);
        invalidateIfNeeded(config.getConfigKey());
        log.info("系统配置已回滚 key={} historyId={}", config.getConfigKey(), historyId);
    }

    private SysConfig require(Long id) {
        SysConfig config = configMapper.selectById(id);
        if (config == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "配置不存在");
        }
        return config;
    }

    private SysConfig requireByKey(String key) {
        if (!StringUtils.hasText(key)) {
            throw new BizException(ResultCode.PARAM_INVALID, "配置键不能为空");
        }
        SysConfig config = configMapper.selectOne(new QueryWrapper<SysConfig>()
                .eq("config_key", key).last("LIMIT 1"));
        if (config == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "配置不存在: " + key);
        }
        return config;
    }

    private void validateKey(String key) {
        if (SECRET_KEY.matcher(key).matches()) {
            throw new BizException(ResultCode.CONFIG_SECRET_FORBIDDEN);
        }
    }

    /** 空值一律允许（表示清空）；非空时按声明类型校验。 */
    private void validateValue(Integer valueType, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        int type = valueType == null ? SysConfig.TYPE_STRING : valueType;
        try {
            switch (type) {
                case SysConfig.TYPE_NUMBER:
                    new BigDecimal(value.trim());
                    break;
                case SysConfig.TYPE_BOOLEAN:
                    String normalized = value.trim().toLowerCase(Locale.ROOT);
                    if (!"true".equals(normalized) && !"false".equals(normalized)) {
                        throw new BizException(ResultCode.CONFIG_VALUE_INVALID,
                                "布尔配置只能是 true 或 false");
                    }
                    break;
                case SysConfig.TYPE_JSON:
                    objectMapper.readTree(value);
                    break;
                case SysConfig.TYPE_STRING:
                    break;
                default:
                    throw new BizException(ResultCode.CONFIG_VALUE_INVALID, "未知的配置类型");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ResultCode.CONFIG_VALUE_INVALID,
                    "配置值与类型不匹配：" + e.getMessage());
        }
    }

    private void writeHistory(String key, String oldValue, String newValue, Integer type,
                              String changeType, String remark) {
        LoginUser admin = UserContext.get();
        SysConfigHistory history = new SysConfigHistory();
        history.setConfigKey(key);
        history.setOldValue(oldValue);
        history.setNewValue(newValue);
        history.setValueType(type);
        history.setChangeType(changeType);
        history.setChangedBy(admin == null ? null : admin.getUserId());
        history.setChangedByName(admin == null ? null
                : (StringUtils.hasText(admin.getNickname()) ? admin.getNickname() : admin.getUsername()));
        history.setRemark(remark);
        history.setCreateTime(LocalDateTime.now());
        historyMapper.insert(history);
    }

    private void invalidateIfNeeded(String key) {
        if (SensitiveWordSupport.CONFIG_KEY.equals(key)) {
            sensitiveWordSupport.invalidate();
            log.info("敏感词词典已触发重新加载");
        }
    }
}
