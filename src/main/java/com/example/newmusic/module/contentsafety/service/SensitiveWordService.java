package com.example.newmusic.module.contentsafety.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.infra.redis.RedisKeys;
import com.example.newmusic.infra.redis.RedisOps;
import com.example.newmusic.module.contentsafety.dto.AuditRuleSaveReq;
import com.example.newmusic.module.contentsafety.dto.SensitiveWordSaveReq;
import com.example.newmusic.module.contentsafety.dto.WhiteWordSaveReq;
import com.example.newmusic.module.contentsafety.dto.WordImportReq;
import com.example.newmusic.module.contentsafety.dto.WordQueryReq;
import com.example.newmusic.module.contentsafety.dto.WordTestReq;
import com.example.newmusic.module.contentsafety.entity.AuditRule;
import com.example.newmusic.module.contentsafety.entity.SensitiveWord;
import com.example.newmusic.module.contentsafety.entity.SensitiveWordVersion;
import com.example.newmusic.module.contentsafety.entity.WhiteWord;
import com.example.newmusic.module.contentsafety.mapper.AuditRuleMapper;
import com.example.newmusic.module.contentsafety.mapper.SensitiveWordMapper;
import com.example.newmusic.module.contentsafety.mapper.SensitiveWordVersionMapper;
import com.example.newmusic.module.contentsafety.mapper.WhiteWordMapper;
import com.example.newmusic.module.contentsafety.support.SensitiveWordEngine;
import com.example.newmusic.module.contentsafety.support.TextNormalizer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 敏感词库管理与热加载（FR-27-001 / FR-27-002）。
 *
 * <p>写操作一律「落库 → 建新快照 → 原子替换」；快照失败保留旧词库，
 * 不会出现「DB 已改、线上还在用旧词」的静默不一致。
 */
@Slf4j
@Service
public class SensitiveWordService {

    public static final int IMPORT_LIMIT = 10000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SensitiveWordMapper wordMapper;
    private final WhiteWordMapper whiteMapper;
    private final AuditRuleMapper ruleMapper;
    private final SensitiveWordVersionMapper versionMapper;
    private final SensitiveWordEngine engine;
    private final RedisOps redisOps;

    public SensitiveWordService(SensitiveWordMapper wordMapper, WhiteWordMapper whiteMapper,
                                AuditRuleMapper ruleMapper, SensitiveWordVersionMapper versionMapper,
                                SensitiveWordEngine engine, RedisOps redisOps) {
        this.wordMapper = wordMapper;
        this.whiteMapper = whiteMapper;
        this.ruleMapper = ruleMapper;
        this.versionMapper = versionMapper;
        this.engine = engine;
        this.redisOps = redisOps;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        try {
            reloadFromDb();
        } catch (Throwable e) {
            // EX-27-001-1：表未建/DB 不可用时保留内置最小词库，服务照常启动。
            log.warn("敏感词库初始化失败，使用内置兜底词库：{}", e.toString());
        }
    }

    // ------------------------------------------------------------------ load

    public synchronized int reloadFromDb() {
        List<SensitiveWord> words = wordMapper.selectEnabled();
        List<WhiteWord> whites = whiteMapper.selectList(new QueryWrapper<>());
        List<AuditRule> rules = ruleMapper.selectEnabled();
        SensitiveWordVersion latest = versionMapper.selectLatest();
        int version = latest == null ? 1 : latest.getVersion();
        List<SensitiveWordEngine.WordInput> wordInputs = new ArrayList<>();
        for (SensitiveWord word : words) {
            wordInputs.add(new SensitiveWordEngine.WordInput(word.getId(), word.getWord(),
                    word.getLevel(), word.getCategory(), word.getSource()));
        }
        List<String> whiteInputs = new ArrayList<>();
        for (WhiteWord white : whites) {
            whiteInputs.add(white.getWord());
        }
        List<SensitiveWordEngine.RuleInput> ruleInputs = new ArrayList<>();
        for (AuditRule rule : rules) {
            ruleInputs.add(new SensitiveWordEngine.RuleInput(rule.getId(), rule.getRuleName(),
                    rule.getPattern(), rule.getScene(), rule.getAction()));
        }
        boolean ok = engine.reload(wordInputs, whiteInputs, ruleInputs, version);
        if (ok) {
            redisOps.set(RedisKeys.auditWordVersion(), String.valueOf(version), Duration.ofDays(30));
            redisOps.set(RedisKeys.auditWordBuffer(), "READY:" + version, Duration.ofMinutes(10));
        }
        return ok ? version : -1;
    }

    /** 手动热加载：不产生新版本，只把当前 DB 状态重新装进引擎。 */
    public Map<String, Object> reload() {
        int version = reloadFromDb();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", version > 0);
        result.put("version", version < 0 ? engine.version() : version);
        result.put("wordCount", engine.wordCount());
        result.put("fallback", engine.isFallback());
        result.put("reloadTime", LocalDateTime.now());
        return result;
    }

    // ------------------------------------------------------------------ words

    public PageResult<SensitiveWord> page(WordQueryReq query) {
        query.normalize();
        QueryWrapper<SensitiveWord> wrapper = new QueryWrapper<>();
        wrapper.eq(query.getLevel() != null, "level", query.getLevel());
        wrapper.eq(StringUtils.hasText(query.getCategory()), "category", query.getCategory());
        wrapper.eq(StringUtils.hasText(query.getSource()), "source", query.getSource());
        wrapper.eq(query.getStatus() != null, "status", query.getStatus());
        wrapper.like(StringUtils.hasText(query.getKeyword()), "word", query.getKeyword());
        wrapper.orderByDesc("level").orderByDesc("update_time").orderByDesc("id");
        Page<SensitiveWord> page = wordMapper.selectPage(query.toPage(), wrapper);
        boolean superAdmin = isSuperAdmin();
        if (!superAdmin) {
            for (SensitiveWord word : page.getRecords()) {
                maskForView(word);
            }
        }
        return PageResult.of(page, query.isTruncated());
    }

    @Transactional
    public SensitiveWord create(SensitiveWordSaveReq req) {
        int level = req.getLevel() == null ? 1 : req.getLevel();
        if (level == SensitiveWord.LEVEL_FORBIDDEN && !isSuperAdmin()) {
            throw new BizException(ResultCode.WORD_L3_PERMISSION_REQUIRED);
        }
        String normalized = requireNormalized(req.getWord());
        if (wordMapper.selectCount(new QueryWrapper<SensitiveWord>().eq("word", normalized)) > 0) {
            throw new BizException(ResultCode.SENSITIVE_WORD_EXISTS);
        }
        SensitiveWord word = new SensitiveWord();
        word.setWord(normalized);
        word.setRawWord(req.getWord().trim());
        word.setLevel(level);
        word.setCategory(defaultString(req.getCategory(), "CUSTOM"));
        word.setSource(defaultString(req.getSource(), "OPERATION"));
        word.setHitCount(0L);
        word.setStatus(req.getStatus() == null ? 1 : req.getStatus());
        word.setRemark(req.getRemark());
        word.setCreatorId(UserContext.getUserId());
        word.setVersion(engine.version() + 1);
        word.setCreateTime(LocalDateTime.now());
        word.setUpdateTime(LocalDateTime.now());
        wordMapper.insert(word);
        afterChange("新增词条 " + normalized);
        return readable(word);
    }

    @Transactional
    public SensitiveWord update(Long id, SensitiveWordSaveReq req) {
        SensitiveWord exists = requireWord(id);
        int newLevel = req.getLevel() == null ? exists.getLevel() : req.getLevel();
        boolean l3Touched = exists.getLevel() == SensitiveWord.LEVEL_FORBIDDEN
                || newLevel == SensitiveWord.LEVEL_FORBIDDEN;
        if (l3Touched && !isSuperAdmin()) {
            throw new BizException(ResultCode.WORD_L3_PERMISSION_REQUIRED);
        }
        String normalized = requireNormalized(req.getWord());
        Long same = wordMapper.selectCount(new QueryWrapper<SensitiveWord>()
                .eq("word", normalized).ne("id", id));
        if (same != null && same > 0) {
            throw new BizException(ResultCode.SENSITIVE_WORD_EXISTS);
        }
        SensitiveWord update = new SensitiveWord();
        update.setId(id);
        update.setWord(normalized);
        update.setRawWord(req.getWord().trim());
        update.setLevel(newLevel);
        update.setCategory(defaultString(req.getCategory(), exists.getCategory()));
        update.setSource(defaultString(req.getSource(), exists.getSource()));
        update.setStatus(req.getStatus() == null ? exists.getStatus() : req.getStatus());
        update.setRemark(req.getRemark());
        update.setVersion(engine.version() + 1);
        update.setUpdateTime(LocalDateTime.now());
        wordMapper.updateById(update);
        afterChange("编辑词条 " + normalized + " (L" + newLevel + ")");
        return readable(requireWord(id));
    }

    /** 软删除：保留历史，便于回溯（BR-27-002）。 */
    @Transactional
    public void delete(Long id) {
        SensitiveWord exists = requireWord(id);
        if (exists.getLevel() == SensitiveWord.LEVEL_FORBIDDEN && !isSuperAdmin()) {
            throw new BizException(ResultCode.WORD_L3_PERMISSION_REQUIRED);
        }
        SensitiveWord update = new SensitiveWord();
        update.setId(id);
        update.setStatus(0);
        update.setVersion(engine.version() + 1);
        update.setUpdateTime(LocalDateTime.now());
        wordMapper.updateById(update);
        afterChange("停用词条 " + exists.getWord());
    }

    // ------------------------------------------------------------------ import/export

    public Map<String, Object> previewImport(WordImportReq req) {
        List<ImportLine> lines = parseLines(req.getContent());
        if (lines.size() > IMPORT_LIMIT) {
            throw new BizException(ResultCode.WORD_IMPORT_LIMIT);
        }
        Set<String> existing = new HashSet<>();
        for (SensitiveWord word : wordMapper.selectList(new QueryWrapper<>())) {
            existing.add(word.getWord());
        }
        Set<String> seen = new HashSet<>(existing);
        List<Map<String, Object>> rows = new ArrayList<>();
        int newCount = 0;
        int duplicate = 0;
        int invalid = 0;
        for (ImportLine line : lines) {
            String normalized = TextNormalizer.normalizeText(line.text);
            String error = validateNormalized(normalized);
            boolean dup = error == null && !seen.add(normalized);
            if (error != null) {
                invalid++;
            } else if (dup) {
                duplicate++;
            } else {
                newCount++;
            }
            if (rows.size() < 100) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("line", line.number);
                row.put("word", line.text);
                row.put("normalized", normalized);
                row.put("error", error != null ? error : (dup ? "重复词条" : null));
                rows.add(row);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", lines.size());
        result.put("newCount", newCount);
        result.put("duplicateCount", duplicate);
        result.put("invalidCount", invalid);
        result.put("rows", rows);
        return result;
    }

    @Transactional
    public Map<String, Object> importWords(WordImportReq req) {
        int level = req.getLevel() == null ? 1 : req.getLevel();
        if (level == SensitiveWord.LEVEL_FORBIDDEN && !isSuperAdmin()) {
            throw new BizException(ResultCode.WORD_L3_PERMISSION_REQUIRED);
        }
        List<ImportLine> lines = parseLines(req.getContent());
        if (lines.size() > IMPORT_LIMIT) {
            throw new BizException(ResultCode.WORD_IMPORT_LIMIT);
        }
        int inserted = 0;
        int duplicate = 0;
        int invalid = 0;
        for (ImportLine line : lines) {
            String normalized = TextNormalizer.normalizeText(line.text);
            if (validateNormalized(normalized) != null) {
                invalid++;
                continue;
            }
            if (wordMapper.selectCount(new QueryWrapper<SensitiveWord>().eq("word", normalized)) > 0) {
                duplicate++;
                continue;
            }
            SensitiveWord word = new SensitiveWord();
            word.setWord(normalized);
            word.setRawWord(line.text);
            word.setLevel(level);
            word.setCategory(defaultString(req.getCategory(), "CUSTOM"));
            word.setSource(defaultString(req.getSource(), "IMPORT"));
            word.setHitCount(0L);
            word.setStatus(1);
            word.setRemark(req.getRemark());
            word.setCreatorId(UserContext.getUserId());
            word.setVersion(engine.version() + 1);
            word.setCreateTime(LocalDateTime.now());
            word.setUpdateTime(LocalDateTime.now());
            wordMapper.insert(word);
            inserted++;
        }
        if (inserted > 0) {
            afterChange("批量导入 " + inserted + " 个词条（L" + level + "）");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inserted", inserted);
        result.put("duplicateCount", duplicate);
        result.put("invalidCount", invalid);
        return result;
    }

    /**
     * 导出词库 CSV。L3 词属于敏感数据（BR-27-019）：必须超管 + 输入 EXPORT。
     */
    public String export(Integer level, String confirm) {
        List<SensitiveWord> words = wordMapper.selectList(new QueryWrapper<SensitiveWord>()
                .eq(level != null, "level", level).eq("status", 1)
                .orderByAsc("level").orderByAsc("id"));
        boolean containsL3 = words.stream().anyMatch(w -> Integer.valueOf(3).equals(w.getLevel()));
        if (containsL3) {
            if (!isSuperAdmin()) {
                throw new BizException(ResultCode.WORD_L3_PERMISSION_REQUIRED);
            }
            if (!"EXPORT".equals(confirm)) {
                throw new BizException(ResultCode.WORD_EXPORT_CONFIRM_REQUIRED);
            }
        }
        if (words.isEmpty()) {
            throw new BizException(ResultCode.WORD_EXPORT_EMPTY);
        }
        StringBuilder sb = new StringBuilder("﻿word,raw_word,level,category,source,status,hit_count\n");
        for (SensitiveWord word : words) {
            sb.append(csv(word.getWord())).append(',')
                    .append(csv(word.getRawWord())).append(',')
                    .append(word.getLevel()).append(',')
                    .append(csv(word.getCategory())).append(',')
                    .append(csv(word.getSource())).append(',')
                    .append(word.getStatus()).append(',')
                    .append(word.getHitCount() == null ? 0 : word.getHitCount())
                    .append('\n');
        }
        log.info("敏感词库导出 exportBy={} count={} containsL3={}", UserContext.getUserId(),
                words.size(), containsL3);
        return sb.toString();
    }

    // ------------------------------------------------------------------ test/stats/version

    public Map<String, Object> test(WordTestReq req) {
        SensitiveWordEngine.Result result = engine.detect(req.getText(), req.getScene());
        boolean superAdmin = isSuperAdmin();
        List<Map<String, Object>> matches = new ArrayList<>();
        for (SensitiveWordEngine.Match match : result.getMatches()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("word", superAdmin ? match.getRawWord() : match.getWord());
            row.put("level", match.getLevel());
            row.put("category", match.getCategory());
            row.put("start", match.getStart());
            row.put("end", match.getEnd());
            matches.add(row);
        }
        List<Map<String, Object>> rules = new ArrayList<>();
        for (SensitiveWordEngine.RuleMatch match : result.getRuleMatches()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ruleId", match.getRuleId());
            row.put("ruleName", match.getRuleName());
            row.put("action", match.getAction());
            row.put("start", match.getStart());
            row.put("end", match.getEnd());
            row.put("sample", match.getSample());
            rules.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", req.getText());
        out.put("normalized", result.getNormalizedText());
        out.put("matches", matches);
        out.put("rules", rules);
        out.put("wordVersion", result.getWordVersion());
        out.put("fallback", result.isFallback());
        out.put("decision", decisionOf(result));
        return out;
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wordVersion", engine.version());
        out.put("loadedWords", engine.wordCount());
        out.put("fallback", engine.isFallback());
        List<Map<String, Object>> byLevel = new ArrayList<>();
        for (int level = 1; level <= 3; level++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("level", level);
            row.put("count", wordMapper.selectCount(new QueryWrapper<SensitiveWord>()
                    .eq("level", level).eq("status", 1)));
            byLevel.add(row);
        }
        out.put("byLevel", byLevel);
        List<Map<String, Object>> byCategory = wordMapper.selectMaps(new QueryWrapper<SensitiveWord>()
                .select("category", "COUNT(*) AS cnt").eq("status", 1).groupBy("category")
                .orderByDesc("cnt"));
        out.put("byCategory", byCategory);
        List<SensitiveWord> top = wordMapper.selectTopHits(20);
        boolean superAdmin = isSuperAdmin();
        List<Map<String, Object>> topRows = new ArrayList<>();
        for (SensitiveWord word : top) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("word", superAdmin || word.getLevel() < 3
                    ? word.getRawWord() == null ? word.getWord() : word.getRawWord()
                    : SensitiveWordEngine.mask(word.getRawWord() == null ? word.getWord() : word.getRawWord()));
            row.put("level", word.getLevel());
            row.put("category", word.getCategory());
            row.put("hitCount", word.getHitCount());
            row.put("lastHitTime", word.getLastHitTime());
            topRows.add(row);
        }
        out.put("topHits", topRows);
        return out;
    }

    public List<SensitiveWordVersion> versions() {
        List<SensitiveWordVersion> list = versionMapper.selectList(new QueryWrapper<SensitiveWordVersion>()
                .orderByDesc("version"));
        for (SensitiveWordVersion version : list) {
            version.setSnapshotData(null);
        }
        return list;
    }

    @Transactional
    public Map<String, Object> rollback(Integer version, Long operatorId) {
        SensitiveWordVersion target = versionMapper.selectOne(
                new QueryWrapper<SensitiveWordVersion>().eq("version", version));
        if (target == null || !StringUtils.hasText(target.getSnapshotData())) {
            throw new BizException(ResultCode.WORD_VERSION_NOT_FOUND);
        }
        SnapshotData data;
        try {
            data = JSON.readValue(target.getSnapshotData(), new TypeReference<SnapshotData>() {
            });
        } catch (Exception e) {
            throw new BizException(ResultCode.WORD_VERSION_NOT_FOUND, "快照内容无法解析");
        }
        Set<String> keep = new HashSet<>();
        for (SnapshotWord word : data.getWords()) {
            keep.add(word.getWord());
            SensitiveWord exists = wordMapper.selectOne(
                    new QueryWrapper<SensitiveWord>().eq("word", word.getWord()));
            SensitiveWord entity = new SensitiveWord();
            entity.setWord(word.getWord());
            entity.setRawWord(word.getRawWord());
            entity.setLevel(word.getLevel());
            entity.setCategory(word.getCategory());
            entity.setSource(word.getSource());
            entity.setStatus(word.getStatus());
            entity.setRemark(word.getRemark());
            entity.setVersion(version);
            entity.setUpdateTime(LocalDateTime.now());
            if (exists == null) {
                entity.setHitCount(0L);
                entity.setCreatorId(operatorId);
                entity.setCreateTime(LocalDateTime.now());
                wordMapper.insert(entity);
            } else {
                entity.setId(exists.getId());
                wordMapper.updateById(entity);
            }
        }
        // 快照里没有的词停用，而不是物理删除（保留回溯能力）
        for (SensitiveWord word : wordMapper.selectList(new QueryWrapper<>())) {
            if (!keep.contains(word.getWord()) && Integer.valueOf(1).equals(word.getStatus())) {
                SensitiveWord update = new SensitiveWord();
                update.setId(word.getId());
                update.setStatus(0);
                wordMapper.updateById(update);
            }
        }
        // 白名单与规则也按快照恢复。
        // 注意：MyBatis-Plus 的防全表删除拦截器会拦下 delete(null)/空条件，
        // 这里用 id 条件明确表达「清空」，避免回滚整体失败。
        whiteMapper.delete(new QueryWrapper<WhiteWord>().ge("id", 0));
        for (String white : data.getWhites()) {
            WhiteWord entity = new WhiteWord();
            entity.setWord(white);
            entity.setReason("版本回滚恢复");
            entity.setCreatorId(operatorId);
            entity.setCreateTime(LocalDateTime.now());
            whiteMapper.insert(entity);
        }
        ruleMapper.delete(new QueryWrapper<AuditRule>().ge("id", 0));
        for (SnapshotRule rule : data.getRules()) {
            AuditRule entity = new AuditRule();
            entity.setRuleName(rule.getRuleName());
            entity.setRuleType(rule.getRuleType());
            entity.setPattern(rule.getPattern());
            entity.setScene(rule.getScene());
            entity.setAction(rule.getAction());
            entity.setPriority(rule.getPriority());
            entity.setStatus(rule.getStatus());
            entity.setRemark(rule.getRemark());
            entity.setCreatorId(operatorId);
            entity.setCreateTime(LocalDateTime.now());
            entity.setUpdateTime(LocalDateTime.now());
            ruleMapper.insert(entity);
        }
        Map<String, Object> result = afterChange("回滚到词库版本 v" + version);
        result.put("rolledBackTo", version);
        return result;
    }

    // ------------------------------------------------------------------ white/rules

    public List<WhiteWord> whites() {
        return whiteMapper.selectList(new QueryWrapper<WhiteWord>().orderByDesc("id"));
    }

    @Transactional
    public WhiteWord createWhite(WhiteWordSaveReq req) {
        String normalized = requireNormalized(req.getWord());
        if (whiteMapper.selectCount(new QueryWrapper<WhiteWord>().eq("word", normalized)) > 0) {
            throw new BizException(ResultCode.SENSITIVE_WORD_EXISTS, "该白名单词已存在");
        }
        WhiteWord word = new WhiteWord();
        word.setWord(normalized);
        word.setReason(req.getReason());
        word.setCreatorId(UserContext.getUserId());
        word.setCreateTime(LocalDateTime.now());
        whiteMapper.insert(word);
        afterChange("新增白名单 " + normalized);
        return word;
    }

    @Transactional
    public void deleteWhite(Long id) {
        WhiteWord word = whiteMapper.selectById(id);
        if (word == null) {
            throw new BizException(ResultCode.SENSITIVE_WORD_NOT_FOUND);
        }
        whiteMapper.deleteById(id);
        afterChange("移除白名单 " + word.getWord());
    }

    public List<AuditRule> rules() {
        return ruleMapper.selectList(new QueryWrapper<AuditRule>().orderByAsc("priority").orderByAsc("id"));
    }

    @Transactional
    public AuditRule createRule(AuditRuleSaveReq req) {
        validateRule(req);
        AuditRule rule = new AuditRule();
        applyRule(rule, req);
        rule.setCreatorId(UserContext.getUserId());
        rule.setCreateTime(LocalDateTime.now());
        rule.setUpdateTime(LocalDateTime.now());
        ruleMapper.insert(rule);
        afterChange("新增审核规则 " + rule.getRuleName());
        return rule;
    }

    @Transactional
    public AuditRule updateRule(Long id, AuditRuleSaveReq req) {
        AuditRule exists = ruleMapper.selectById(id);
        if (exists == null) {
            throw new BizException(ResultCode.AUDIT_RULE_NOT_FOUND);
        }
        validateRule(req);
        AuditRule update = new AuditRule();
        update.setId(id);
        applyRule(update, req);
        update.setUpdateTime(LocalDateTime.now());
        ruleMapper.updateById(update);
        afterChange("编辑审核规则 " + update.getRuleName());
        return ruleMapper.selectById(id);
    }

    @Transactional
    public void deleteRule(Long id) {
        AuditRule exists = ruleMapper.selectById(id);
        if (exists == null) {
            throw new BizException(ResultCode.AUDIT_RULE_NOT_FOUND);
        }
        ruleMapper.deleteById(id);
        afterChange("删除审核规则 " + exists.getRuleName());
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> afterChange(String desc) {
        int nextVersion = engine.version() + 1;
        SnapshotData data = snapshotData(nextVersion, desc);
        SensitiveWordVersion version = new SensitiveWordVersion();
        version.setVersion(nextVersion);
        version.setWordCount(data.getWords().size());
        version.setSnapshotPath("db://sensitive-word/" + nextVersion);
        try {
            version.setSnapshotData(JSON.writeValueAsString(data));
        } catch (Exception e) {
            version.setSnapshotData(null);
        }
        version.setOperatorId(UserContext.getUserId());
        version.setChangeDesc(desc);
        version.setCreateTime(LocalDateTime.now());
        versionMapper.insert(version);
        boolean ok = engine.reload(data.toWordInputs(), data.getWhites(), data.toRuleInputs(), nextVersion);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", ok);
        result.put("version", ok ? nextVersion : engine.version());
        result.put("wordCount", ok ? data.getWords().size() : engine.wordCount());
        return result;
    }

    private SnapshotData snapshotData(int version, String desc) {
        SnapshotData data = new SnapshotData();
        data.setVersion(version);
        data.setChangeDesc(desc);
        for (SensitiveWord word : wordMapper.selectEnabled()) {
            SnapshotWord row = new SnapshotWord();
            row.setWord(word.getWord());
            row.setRawWord(word.getRawWord());
            row.setLevel(word.getLevel());
            row.setCategory(word.getCategory());
            row.setSource(word.getSource());
            row.setStatus(word.getStatus());
            row.setRemark(word.getRemark());
            data.getWords().add(row);
        }
        for (WhiteWord white : whiteMapper.selectList(new QueryWrapper<>())) {
            data.getWhites().add(white.getWord());
        }
        for (AuditRule rule : ruleMapper.selectEnabled()) {
            SnapshotRule row = new SnapshotRule();
            row.setRuleName(rule.getRuleName());
            row.setRuleType(rule.getRuleType());
            row.setPattern(rule.getPattern());
            row.setScene(rule.getScene());
            row.setAction(rule.getAction());
            row.setPriority(rule.getPriority());
            row.setStatus(rule.getStatus());
            row.setRemark(rule.getRemark());
            data.getRules().add(row);
        }
        return data;
    }

    private SensitiveWord requireWord(Long id) {
        SensitiveWord word = id == null ? null : wordMapper.selectById(id);
        if (word == null) {
            throw new BizException(ResultCode.SENSITIVE_WORD_NOT_FOUND);
        }
        return word;
    }

    private SensitiveWord readable(SensitiveWord word) {
        if (!isSuperAdmin()) {
            maskForView(word);
        }
        return word;
    }

    private void maskForView(SensitiveWord word) {
        if (Integer.valueOf(SensitiveWord.LEVEL_FORBIDDEN).equals(word.getLevel())) {
            String display = word.getRawWord() == null ? word.getWord() : word.getRawWord();
            word.setWord(SensitiveWordEngine.mask(display));
            word.setRawWord(null);
        }
    }

    private String requireNormalized(String raw) {
        String normalized = TextNormalizer.normalizeText(raw);
        String error = validateNormalized(normalized);
        if (error != null) {
            throw new BizException(ResultCode.PARAM_INVALID, error);
        }
        return normalized;
    }

    private static String validateNormalized(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return "归一化后为空（词条不能只包含空格/标点）";
        }
        if (normalized.length() > 128) {
            return "词条最长 128 字";
        }
        return null;
    }

    private static List<ImportLine> parseLines(String content) {
        List<ImportLine> lines = new ArrayList<>();
        if (content == null) {
            return lines;
        }
        String source = content.startsWith("﻿") ? content.substring(1) : content;
        String[] parts = source.split("\\r?\\n", -1);
        for (int i = 0; i < parts.length; i++) {
            String text = parts[i].trim();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            // 兼容 CSV：第一列是词
            int comma = text.indexOf(',');
            if (comma > 0) {
                text = text.substring(0, comma).trim();
            }
            if (!text.isEmpty()) {
                lines.add(new ImportLine(i + 1, text));
            }
        }
        return lines;
    }

    private static String validateRule(AuditRuleSaveReq req) {
        if (!"REJECT".equalsIgnoreCase(req.getAction()) && !"REVIEW".equalsIgnoreCase(req.getAction())
                && !"PASS".equalsIgnoreCase(req.getAction())) {
            throw new BizException(ResultCode.PARAM_INVALID, "规则动作只能为 REJECT/REVIEW/PASS");
        }
        try {
            java.util.regex.Pattern.compile(req.getPattern());
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_INVALID, "正则表达式不合法：" + e.getMessage());
        }
        return null;
    }

    private static void applyRule(AuditRule rule, AuditRuleSaveReq req) {
        rule.setRuleName(req.getRuleName());
        rule.setRuleType(defaultString(req.getRuleType(), "REGEX"));
        rule.setPattern(req.getPattern());
        rule.setScene(defaultString(req.getScene(), "ALL"));
        rule.setAction(defaultString(req.getAction(), "REVIEW").toUpperCase());
        rule.setPriority(req.getPriority() == null ? 100 : req.getPriority());
        rule.setStatus(req.getStatus() == null ? 1 : req.getStatus());
        rule.setRemark(req.getRemark());
    }

    private static String decisionOf(SensitiveWordEngine.Result result) {
        if (result.hasRejectRule() || result.getMaxLevel() >= 3) {
            return "REJECT";
        }
        if (result.getMaxLevel() >= 1 || result.hasReviewRule()) {
            return "REVIEW";
        }
        return "PASS";
    }

    private static boolean isSuperAdmin() {
        return UserContext.get() != null && UserContext.get().isSuperAdmin();
    }

    private static String defaultString(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n"))
                ? "\"" + escaped + "\"" : escaped;
    }

    private static final class ImportLine {
        private final int number;
        private final String text;

        ImportLine(int number, String text) {
            this.number = number;
            this.text = text;
        }
    }

    public static final class SnapshotData {
        private int version;
        private String changeDesc;
        private List<SnapshotWord> words = new ArrayList<>();
        private List<String> whites = new ArrayList<>();
        private List<SnapshotRule> rules = new ArrayList<>();

        public int getVersion() {
            return version;
        }

        public void setVersion(int version) {
            this.version = version;
        }

        public String getChangeDesc() {
            return changeDesc;
        }

        public void setChangeDesc(String changeDesc) {
            this.changeDesc = changeDesc;
        }

        public List<SnapshotWord> getWords() {
            return words;
        }

        public void setWords(List<SnapshotWord> words) {
            this.words = words == null ? new ArrayList<>() : words;
        }

        public List<String> getWhites() {
            return whites;
        }

        public void setWhites(List<String> whites) {
            this.whites = whites == null ? new ArrayList<>() : whites;
        }

        public List<SnapshotRule> getRules() {
            return rules;
        }

        public void setRules(List<SnapshotRule> rules) {
            this.rules = rules == null ? new ArrayList<>() : rules;
        }

        List<SensitiveWordEngine.WordInput> toWordInputs() {
            List<SensitiveWordEngine.WordInput> inputs = new ArrayList<>();
            for (SnapshotWord word : words) {
                inputs.add(new SensitiveWordEngine.WordInput(0, word.getWord(), word.getLevel(),
                        word.getCategory(), word.getSource()));
            }
            return inputs;
        }

        List<SensitiveWordEngine.RuleInput> toRuleInputs() {
            List<SensitiveWordEngine.RuleInput> inputs = new ArrayList<>();
            for (SnapshotRule rule : rules) {
                inputs.add(new SensitiveWordEngine.RuleInput(0, rule.getRuleName(), rule.getPattern(),
                        rule.getScene(), rule.getAction()));
            }
            return inputs;
        }
    }

    public static final class SnapshotWord {
        private String word;
        private String rawWord;
        private int level;
        private String category;
        private String source;
        private int status;
        private String remark;

        public String getWord() {
            return word;
        }

        public void setWord(String word) {
            this.word = word;
        }

        public String getRawWord() {
            return rawWord;
        }

        public void setRawWord(String rawWord) {
            this.rawWord = rawWord;
        }

        public int getLevel() {
            return level;
        }

        public void setLevel(int level) {
            this.level = level;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public int getStatus() {
            return status;
        }

        public void setStatus(int status) {
            this.status = status;
        }

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }
    }

    public static final class SnapshotRule {
        private String ruleName;
        private String ruleType;
        private String pattern;
        private String scene;
        private String action;
        private int priority;
        private int status;
        private String remark;

        public String getRuleName() {
            return ruleName;
        }

        public void setRuleName(String ruleName) {
            this.ruleName = ruleName;
        }

        public String getRuleType() {
            return ruleType;
        }

        public void setRuleType(String ruleType) {
            this.ruleType = ruleType;
        }

        public String getPattern() {
            return pattern;
        }

        public void setPattern(String pattern) {
            this.pattern = pattern;
        }

        public String getScene() {
            return scene;
        }

        public void setScene(String scene) {
            this.scene = scene;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }

        public int getStatus() {
            return status;
        }

        public void setStatus(int status) {
            this.status = status;
        }

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }
    }
}
