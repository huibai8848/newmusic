package com.example.newmusic.module.dataplatform.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.query.PageQuery;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.dataplatform.config.DataPlatformProperties;
import com.example.newmusic.module.dataplatform.dto.AudienceReq;
import com.example.newmusic.module.dataplatform.entity.Audience;
import com.example.newmusic.module.dataplatform.mapper.AudienceMapper;
import com.example.newmusic.module.dataplatform.mapper.ProfileQueryMapper;
import com.example.newmusic.module.dataplatform.model.AudienceRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 人群圈选（FR-26-004 / BR-26-040 ~ 044）。
 *
 * <p>圈选条件用 JSON 表达 AND/OR 嵌套。命中人数 &lt; 100 时**不返回也不落库用户 ID**
 * （BR-26-044）：只给人数，避免拿小样本反推某个具体用户。
 *
 * <p>本地实现是「把相关标签读进内存后逐人判定」。标签量级（10 万 × 20）在 MySQL 上
 * 还能接受；生产应下推到 ClickHouse，接口形状不变。
 */
@Slf4j
@Service
public class AudienceService {

    private static final int MAX_CONDITIONS = 20;
    private static final Set<String> OPS = new HashSet<>(java.util.Arrays.asList(
            "eq", "neq", "in", "not_in", "contains", "gte", "lte", "gt", "lt", "exists"));

    private final AudienceMapper audienceMapper;
    private final ProfileQueryMapper profileMapper;
    private final DataPlatformProperties properties;
    private final ObjectMapper objectMapper;

    public AudienceService(AudienceMapper audienceMapper, ProfileQueryMapper profileMapper,
                           DataPlatformProperties properties, ObjectMapper objectMapper) {
        this.audienceMapper = audienceMapper;
        this.profileMapper = profileMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    // ================================================================== 预览 / 圈选

    public Map<String, Object> preview(AudienceReq req) {
        Eval eval = evaluate(req == null ? null : req.getConditions());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userCount", eval.userIds.size());
        result.put("precise", eval.userIds.size() >= properties.getAudienceMinPrecision());
        result.put("minPrecision", properties.getAudienceMinPrecision());
        result.put("tagNames", eval.tagNames);
        result.put("scannedRows", eval.scannedRows);
        result.put("truncated", eval.truncated);
        result.put("notice", eval.userIds.size() < properties.getAudienceMinPrecision()
                ? "命中人数少于 " + properties.getAudienceMinPrecision() + " 人，按精度保护规则不展示用户明细"
                : "命中人数可查看明细");
        return result;
    }

    public Map<String, Object> create(AudienceReq req) {
        if (req == null || req.getConditions() == null || req.getConditions().isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID, "圈选条件不能为空");
        }
        if (req.getName() == null || req.getName().trim().isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID, "人群名不能为空");
        }
        String name = req.getName().trim();
        if (name.length() > 128) {
            throw new BizException(ResultCode.PARAM_INVALID, "人群名不能超过 128 个字符");
        }
        Eval eval = evaluate(req.getConditions());
        boolean precise = eval.userIds.size() >= properties.getAudienceMinPrecision();

        Audience audience = new Audience();
        audience.setName(name);
        try {
            audience.setConditions(objectMapper.writeValueAsString(req.getConditions()));
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_INVALID, "圈选条件不是合法 JSON");
        }
        audience.setUserCount(eval.userIds.size());
        audience.setUserIds(precise ? toJson(eval.userIds) : null);
        audience.setStatus(1);
        audience.setCreatorId(UserContext.requireUserId());
        audience.setCreateTime(LocalDateTime.now());
        audience.setUpdateTime(LocalDateTime.now());
        audienceMapper.insert(audience);
        return render(audience, precise);
    }

    public PageResult<Map<String, Object>> list(PageQuery query) {
        PageQuery q = query == null ? new PageQuery() : query;
        q.normalize();
        Page<Audience> page = new Page<>(q.getPageNum(), q.getPageSize());
        IPage<Audience> result = audienceMapper.selectPage(page,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Audience>lambdaQuery()
                        .orderByDesc(Audience::getCreateTime));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Audience audience : result.getRecords()) {
            int count = audience.getUserCount() == null ? 0 : audience.getUserCount();
            list.add(render(audience, count >= properties.getAudienceMinPrecision()));
        }
        return PageResult.ofList(result, list);
    }

    public Map<String, Object> detail(Long id) {
        Audience audience = audienceMapper.selectById(id);
        if (audience == null) {
            throw new BizException(ResultCode.CONTENT_NOT_FOUND, "人群不存在");
        }
        int count = audience.getUserCount() == null ? 0 : audience.getUserCount();
        Map<String, Object> result = render(audience, count >= properties.getAudienceMinPrecision());
        if (count >= properties.getAudienceMinPrecision() && audience.getUserIds() != null) {
            try {
                result.put("userIds", objectMapper.readValue(audience.getUserIds(), List.class));
            } catch (Exception e) {
                result.put("userIds", Collections.emptyList());
            }
        }
        return result;
    }

    public Map<String, Object> delete(Long id) {
        Audience audience = audienceMapper.selectById(id);
        if (audience == null) {
            throw new BizException(ResultCode.CONTENT_NOT_FOUND, "人群不存在");
        }
        audienceMapper.deleteById(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", true);
        result.put("id", String.valueOf(id));
        return result;
    }

    // ================================================================== 条件求值

    private Eval evaluate(Map<String, Object> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID, "圈选条件不能为空");
        }
        JsonNode root = objectMapper.valueToTree(conditions);
        int leaves = countLeaves(root);
        if (leaves > MAX_CONDITIONS) {
            throw new BizException(ResultCode.PARAM_INVALID, "圈选条件最多 " + MAX_CONDITIONS + " 个");
        }
        Set<String> tagNames = new LinkedHashSet<>();
        collectTags(root, tagNames);
        if (tagNames.isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID, "圈选条件至少要包含一个标签");
        }

        int limit = Math.max(1000, properties.getAudienceMaxRows());
        List<AudienceRow> rows = profileMapper.audienceRows(new ArrayList<>(tagNames), limit);
        Map<Long, Map<String, String>> byUser = new HashMap<>();
        for (AudienceRow row : rows) {
            if (row.getUserId() == null || row.getTagName() == null) {
                continue;
            }
            byUser.computeIfAbsent(row.getUserId(), k -> new HashMap<>())
                    .put(row.getTagName(), row.getTagValue());
        }
        List<Long> matched = new ArrayList<>();
        for (Map.Entry<Long, Map<String, String>> entry : byUser.entrySet()) {
            if (match(root, entry.getValue())) {
                matched.add(entry.getKey());
            }
        }
        Collections.sort(matched);
        Eval eval = new Eval();
        eval.userIds = matched;
        eval.tagNames = new ArrayList<>(tagNames);
        eval.scannedRows = rows.size();
        eval.truncated = rows.size() >= limit;
        return eval;
    }

    private boolean match(JsonNode node, Map<String, String> tags) {
        if (node == null || node.isNull()) {
            return true;
        }
        JsonNode children = node.get("conditions");
        if (children != null && children.isArray()) {
            String logic = node.path("logic").asText("AND").toUpperCase();
            boolean and = "AND".equals(logic);
            if (children.size() == 0) {
                return true;
            }
            for (JsonNode child : children) {
                boolean ok = match(child, tags);
                if (and && !ok) {
                    return false;
                }
                if (!and && ok) {
                    return true;
                }
            }
            return and;
        }
        String tagName = node.path("tagName").asText(null);
        if (tagName == null || tagName.isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID, "叶子条件缺少 tagName");
        }
        String op = node.path("op").asText("eq");
        if (!OPS.contains(op)) {
            throw new BizException(ResultCode.PARAM_INVALID, "不支持的比较符：" + op);
        }
        String actual = tags.get(tagName);
        if ("exists".equals(op)) {
            return actual != null;
        }
        if (actual == null) {
            return false;
        }
        if ("in".equals(op) || "not_in".equals(op)) {
            JsonNode values = node.get("values");
            if (values == null || !values.isArray()) {
                throw new BizException(ResultCode.PARAM_INVALID, "op=" + op + " 需要 values 数组");
            }
            boolean hit = false;
            for (JsonNode value : values) {
                if (actual.equals(value.asText())) {
                    hit = true;
                    break;
                }
            }
            return "in".equals(op) == hit;
        }
        String expected = node.path("value").asText("");
        switch (op) {
            case "eq":
                return actual.equals(expected);
            case "neq":
                return !actual.equals(expected);
            case "contains":
                return actual.contains(expected);
            case "gte":
            case "lte":
            case "gt":
            case "lt":
                Double left = number(actual);
                Double right = number(expected);
                if (left == null || right == null) {
                    return false;
                }
                int cmp = Double.compare(left, right);
                if ("gte".equals(op)) {
                    return cmp >= 0;
                }
                if ("lte".equals(op)) {
                    return cmp <= 0;
                }
                return "gt".equals(op) ? cmp > 0 : cmp < 0;
            default:
                return false;
        }
    }

    private int countLeaves(JsonNode node) {
        if (node == null) {
            return 0;
        }
        JsonNode children = node.get("conditions");
        if (children != null && children.isArray()) {
            int count = 0;
            for (JsonNode child : children) {
                count += countLeaves(child);
            }
            return count;
        }
        return 1;
    }

    private void collectTags(JsonNode node, Set<String> tags) {
        if (node == null) {
            return;
        }
        JsonNode children = node.get("conditions");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) {
                collectTags(child, tags);
            }
            return;
        }
        String tagName = node.path("tagName").asText(null);
        if (tagName != null && !tagName.isEmpty()) {
            tags.add(tagName);
        }
    }

    private String toJson(List<Long> userIds) {
        try {
            return objectMapper.writeValueAsString(userIds);
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> render(Audience audience, boolean precise) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", String.valueOf(audience.getId()));
        item.put("name", audience.getName());
        item.put("conditions", audience.getConditions());
        item.put("userCount", audience.getUserCount());
        item.put("precise", precise);
        item.put("status", audience.getStatus());
        item.put("creatorId", audience.getCreatorId() == null ? null : String.valueOf(audience.getCreatorId()));
        item.put("createTime", audience.getCreateTime() == null ? null : audience.getCreateTime().toString());
        item.put("notice", precise ? "可查看用户明细" : "命中人数少于 100，按精度保护规则不保存用户明细");
        return item;
    }

    private static Double number(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    private static class Eval {
        List<Long> userIds = new ArrayList<>();
        List<String> tagNames = new ArrayList<>();
        int scannedRows;
        boolean truncated;
    }
}
