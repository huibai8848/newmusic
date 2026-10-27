package com.example.newmusic.module.dict.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.dict.config.DictProperties;
import com.example.newmusic.module.dict.dto.DictChangeLogQueryReq;
import com.example.newmusic.module.dict.dto.DictImportReq;
import com.example.newmusic.module.dict.dto.DictWordQueryReq;
import com.example.newmusic.module.dict.dto.DictWordSaveReq;
import com.example.newmusic.module.dict.dto.PinyinQueryReq;
import com.example.newmusic.module.dict.dto.PinyinSaveReq;
import com.example.newmusic.module.dict.dto.StopwordQueryReq;
import com.example.newmusic.module.dict.dto.StopwordSaveReq;
import com.example.newmusic.module.dict.dto.SynonymGroupSaveReq;
import com.example.newmusic.module.dict.dto.SynonymQueryReq;
import com.example.newmusic.module.dict.entity.DictChangeLog;
import com.example.newmusic.module.dict.entity.DictSyncLog;
import com.example.newmusic.module.dict.entity.IkDictWord;
import com.example.newmusic.module.dict.entity.IkStopword;
import com.example.newmusic.module.dict.entity.PinyinDict;
import com.example.newmusic.module.dict.entity.SynonymWord;
import com.example.newmusic.module.dict.mapper.DictChangeLogMapper;
import com.example.newmusic.module.dict.mapper.DictSyncLogMapper;
import com.example.newmusic.module.dict.mapper.IkDictWordMapper;
import com.example.newmusic.module.dict.mapper.IkStopwordMapper;
import com.example.newmusic.module.dict.mapper.PinyinDictMapper;
import com.example.newmusic.module.dict.mapper.SynonymMapper;
import com.example.newmusic.module.dict.support.DictTextSupport;
import com.example.newmusic.module.dict.support.EsClient;
import com.example.newmusic.module.dict.support.LocalDictAnalyzer;
import com.example.newmusic.module.dict.vo.DictAnalyzeVO;
import com.example.newmusic.module.dict.vo.DictImportResultVO;
import com.example.newmusic.module.dict.vo.DictStatusVO;
import com.example.newmusic.module.dict.vo.DictSyncResultVO;
import com.example.newmusic.module.dict.vo.SynonymGroupVO;
import com.example.newmusic.module.search.config.SearchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 词典管理（模块 11 FR-20-008）。
 *
 * <p>一个类承接主词典、停用词、同义词、拼音词典四类配置，是因为它们共享三条规则：
 * 词条校验、10 万/5000 上限、变更历史 + 快照失效。拆成四个 Service 只会让
 * 「改完之后要 invalidate」这件事散落在四个地方，迟早漏一个。
 */
@Slf4j
@Service
public class DictAdminService {

    private static final String SOURCE_MANUAL = "MANUAL";
    private static final String SOURCE_AUTO_ARTIST = "AUTO_ARTIST";
    private static final Set<String> DICT_TYPES = new HashSet<>(
            Arrays.asList("MAIN", "NET_SLANG", "REGION"));
    private static final Set<String> SYNONYM_TYPES = new HashSet<>(
            Arrays.asList("EQUIVALENT", "ONE_WAY"));
    private static final Set<String> CATEGORIES = new HashSet<>(Arrays.asList(
            "ARTIST", "GENRE", "LANGUAGE", "ERA", "SCENE", "EMOTION", "OTHER"));

    private final IkDictWordMapper wordMapper;
    private final IkStopwordMapper stopwordMapper;
    private final SynonymMapper synonymMapper;
    private final PinyinDictMapper pinyinMapper;
    private final DictChangeLogMapper changeLogMapper;
    private final DictSyncLogMapper syncLogMapper;
    private final DictSnapshotService snapshotService;
    private final DictChangeLogService changeLogService;
    private final DictArtistSyncService artistSyncService;
    private final EsReindexService reindexService;
    private final PinyinDictRefresher pinyinRefresher;
    private final LocalDictAnalyzer localAnalyzer;
    private final EsClient esClient;
    private final DictProperties properties;
    private final SearchProperties searchProperties;

    public DictAdminService(IkDictWordMapper wordMapper, IkStopwordMapper stopwordMapper,
                            SynonymMapper synonymMapper, PinyinDictMapper pinyinMapper,
                            DictChangeLogMapper changeLogMapper, DictSyncLogMapper syncLogMapper,
                            DictSnapshotService snapshotService, DictChangeLogService changeLogService,
                            DictArtistSyncService artistSyncService, EsReindexService reindexService,
                            PinyinDictRefresher pinyinRefresher, LocalDictAnalyzer localAnalyzer,
                            EsClient esClient, DictProperties properties, SearchProperties searchProperties) {
        this.wordMapper = wordMapper;
        this.stopwordMapper = stopwordMapper;
        this.synonymMapper = synonymMapper;
        this.pinyinMapper = pinyinMapper;
        this.changeLogMapper = changeLogMapper;
        this.syncLogMapper = syncLogMapper;
        this.snapshotService = snapshotService;
        this.changeLogService = changeLogService;
        this.artistSyncService = artistSyncService;
        this.reindexService = reindexService;
        this.pinyinRefresher = pinyinRefresher;
        this.localAnalyzer = localAnalyzer;
        this.esClient = esClient;
        this.properties = properties;
        this.searchProperties = searchProperties;
    }

    // ==================== 主词典 ====================

    public PageResult<IkDictWord> pageWords(DictWordQueryReq req) {
        req.normalize();
        LambdaQueryWrapper<IkDictWord> wrapper = new LambdaQueryWrapper<IkDictWord>()
                .like(req.hasKeyword(), IkDictWord::getWord, req.getKeyword())
                .eq(req.getSource() != null && !req.getSource().isEmpty(),
                        IkDictWord::getSource, upper(req.getSource()))
                .eq(req.getDictType() != null && !req.getDictType().isEmpty(),
                        IkDictWord::getDictType, upper(req.getDictType()))
                .eq(req.getEnabled() != null, IkDictWord::getEnabled, req.getEnabled())
                .orderByDesc(IkDictWord::getUpdateTime)
                .orderByDesc(IkDictWord::getId);
        Page<IkDictWord> page = wordMapper.selectPage(req.toPage(), wrapper);
        return PageResult.of(page, req.isTruncated());
    }

    public IkDictWord createWord(DictWordSaveReq req) {
        String word = validateMainWord(req.getWord());
        if (wordMapper.selectCount(new LambdaQueryWrapper<IkDictWord>()
                .eq(IkDictWord::getWord, word)) > 0) {
            throw new BizException(ResultCode.DICT_WORD_DUPLICATED, "词条已存在：" + word);
        }
        if (wordMapper.selectCount(null) >= properties.getMaxWords()) {
            throw new BizException(ResultCode.DICT_WORD_LIMIT);
        }
        IkDictWord entity = new IkDictWord();
        entity.setWord(word);
        entity.setFrequency(frequencyOf(req.getFrequency()));
        entity.setPos(posOf(req.getPos()));
        entity.setSource(SOURCE_MANUAL);
        entity.setEnabled(enabledOf(req.getEnabled()));
        entity.setDictType(dictTypeOf(req.getDictType()));
        wordMapper.insert(entity);
        snapshotService.invalidate();
        changeLogService.success("MAIN", "CREATE", entity.getId(), null, entity, "新增主词典词条");
        return entity;
    }

    public IkDictWord updateWord(Long id, DictWordSaveReq req) {
        IkDictWord existing = requireWord(id);
        requireManual(existing.getSource());
        String word = validateMainWord(req.getWord());
        if (wordMapper.selectCount(new LambdaQueryWrapper<IkDictWord>()
                .eq(IkDictWord::getWord, word)
                .ne(IkDictWord::getId, id)) > 0) {
            throw new BizException(ResultCode.DICT_WORD_DUPLICATED, "词条已存在：" + word);
        }
        IkDictWord before = copy(existing);
        existing.setWord(word);
        existing.setFrequency(frequencyOf(req.getFrequency()));
        existing.setPos(posOf(req.getPos()));
        existing.setEnabled(enabledOf(req.getEnabled()));
        existing.setDictType(dictTypeOf(req.getDictType()));
        wordMapper.updateById(existing);
        snapshotService.invalidate();
        changeLogService.success("MAIN", "UPDATE", id, before, existing, "修改主词典词条");
        return existing;
    }

    public void deleteWord(Long id) {
        IkDictWord existing = requireWord(id);
        requireManual(existing.getSource());
        wordMapper.deleteById(id);
        snapshotService.invalidate();
        changeLogService.success("MAIN", "DELETE", id, existing, null, "删除主词典词条");
    }

    public String exportWords(String source, String dictType, Integer enabled) {
        List<IkDictWord> words = wordMapper.selectList(new LambdaQueryWrapper<IkDictWord>()
                .eq(source != null && !source.isEmpty(), IkDictWord::getSource, upper(source))
                .eq(dictType != null && !dictType.isEmpty(), IkDictWord::getDictType, upper(dictType))
                .eq(enabled != null, IkDictWord::getEnabled, enabled)
                .orderByAsc(IkDictWord::getWord));
        StringBuilder sb = new StringBuilder("# word\tfrequency\tpos\n");
        for (IkDictWord word : words) {
            sb.append(word.getWord()).append('\t')
                    .append(word.getFrequency() == null ? 100 : word.getFrequency()).append('\t')
                    .append(word.getPos() == null ? "n" : word.getPos()).append('\n');
        }
        return sb.toString();
    }

    public DictImportResultVO importWords(DictImportReq req) {
        DictImportResultVO result = new DictImportResultVO();
        String dictType = dictTypeOf(req.getDictType());
        long total = wordMapper.selectCount(null);
        List<DictTextSupport.Line> lines = DictTextSupport.lines(req.getContent());
        result.setTotal(lines.size());
        for (DictTextSupport.Line line : lines) {
            String[] parts = splitWordLine(line.getText());
            String rawWord = parts[0];
            String invalid = DictTextSupport.validateWord(rawWord,
                    properties.getWordMinLength(), properties.getWordMaxLength());
            if (invalid != null) {
                result.setFailed(result.getFailed() + 1);
                result.getErrors().add(new DictImportResultVO.LineError(line.getNumber(), line.getText(), invalid));
                continue;
            }
            int frequency = intOrDefault(parts.length > 1 ? parts[1] : null, 100);
            if (frequency <= 0) {
                result.setFailed(result.getFailed() + 1);
                result.getErrors().add(new DictImportResultVO.LineError(
                        line.getNumber(), line.getText(), "词频必须是正整数"));
                continue;
            }
            String pos = parts.length > 2 ? posOf(parts[2]) : "n";

            IkDictWord existing = wordMapper.selectOne(new LambdaQueryWrapper<IkDictWord>()
                    .eq(IkDictWord::getWord, rawWord).last("LIMIT 1"));
            if (existing != null) {
                if (!SOURCE_MANUAL.equals(existing.getSource())) {
                    result.setFailed(result.getFailed() + 1);
                    result.getErrors().add(new DictImportResultVO.LineError(
                            line.getNumber(), line.getText(), "自动同步词条不可手工修改"));
                } else if (req.isOverwrite()) {
                    existing.setFrequency(frequency);
                    existing.setPos(pos);
                    existing.setDictType(dictType);
                    wordMapper.updateById(existing);
                    result.setUpdated(result.getUpdated() + 1);
                } else {
                    result.setSkipped(result.getSkipped() + 1);
                }
                continue;
            }
            if (total >= properties.getMaxWords()) {
                result.setFailed(result.getFailed() + 1);
                result.getErrors().add(new DictImportResultVO.LineError(
                        line.getNumber(), line.getText(), "主词典条目已达上限"));
                continue;
            }
            IkDictWord entity = new IkDictWord();
            entity.setWord(rawWord);
            entity.setFrequency(frequency);
            entity.setPos(pos);
            entity.setSource(SOURCE_MANUAL);
            entity.setEnabled(1);
            entity.setDictType(dictType);
            wordMapper.insert(entity);
            total++;
            result.setInserted(result.getInserted() + 1);
        }
        snapshotService.invalidate();
        changeLogService.success("MAIN", "IMPORT", null, null,
                summary(result), "批量导入主词典");
        return result;
    }

    // ==================== 停用词 ====================

    public PageResult<IkStopword> pageStopwords(StopwordQueryReq req) {
        req.normalize();
        LambdaQueryWrapper<IkStopword> wrapper = new LambdaQueryWrapper<IkStopword>()
                .like(req.hasKeyword(), IkStopword::getWord, req.getKeyword())
                .eq(req.getEnabled() != null, IkStopword::getEnabled, req.getEnabled())
                .orderByDesc(IkStopword::getUpdateTime)
                .orderByDesc(IkStopword::getId);
        return PageResult.of(stopwordMapper.selectPage(req.toPage(), wrapper), req.isTruncated());
    }

    public IkStopword createStopword(StopwordSaveReq req) {
        String word = validateStopword(req.getWord());
        if (stopwordMapper.selectCount(new LambdaQueryWrapper<IkStopword>()
                .eq(IkStopword::getWord, word)) > 0) {
            throw new BizException(ResultCode.DICT_WORD_DUPLICATED, "停用词已存在：" + word);
        }
        if (stopwordMapper.selectCount(null) >= properties.getMaxStopwords()) {
            throw new BizException(ResultCode.DICT_STOPWORD_LIMIT);
        }
        IkStopword entity = new IkStopword();
        entity.setWord(word);
        entity.setEnabled(enabledOf(req.getEnabled()));
        stopwordMapper.insert(entity);
        snapshotService.invalidate();
        changeLogService.success("STOPWORD", "CREATE", entity.getId(), null, entity, "新增停用词");
        return entity;
    }

    public IkStopword updateStopword(Long id, StopwordSaveReq req) {
        IkStopword existing = requireStopword(id);
        String word = validateStopword(req.getWord());
        if (stopwordMapper.selectCount(new LambdaQueryWrapper<IkStopword>()
                .eq(IkStopword::getWord, word)
                .ne(IkStopword::getId, id)) > 0) {
            throw new BizException(ResultCode.DICT_WORD_DUPLICATED, "停用词已存在：" + word);
        }
        IkStopword before = copy(existing);
        existing.setWord(word);
        existing.setEnabled(enabledOf(req.getEnabled()));
        stopwordMapper.updateById(existing);
        snapshotService.invalidate();
        changeLogService.success("STOPWORD", "UPDATE", id, before, existing, "修改停用词");
        return existing;
    }

    public void deleteStopword(Long id) {
        IkStopword existing = requireStopword(id);
        stopwordMapper.deleteById(id);
        snapshotService.invalidate();
        changeLogService.success("STOPWORD", "DELETE", id, existing, null, "删除停用词");
    }

    public String exportStopwords() {
        StringBuilder sb = new StringBuilder();
        for (IkStopword word : stopwordMapper.selectList(new LambdaQueryWrapper<IkStopword>()
                .orderByAsc(IkStopword::getWord))) {
            sb.append(word.getWord()).append('\n');
        }
        return sb.toString();
    }

    public DictImportResultVO importStopwords(DictImportReq req) {
        DictImportResultVO result = new DictImportResultVO();
        List<DictTextSupport.Line> lines = DictTextSupport.lines(req.getContent());
        result.setTotal(lines.size());
        long total = stopwordMapper.selectCount(null);
        for (DictTextSupport.Line line : lines) {
            String invalid = stopwordInvalidReason(line.getText());
            if (invalid != null) {
                result.setFailed(result.getFailed() + 1);
                result.getErrors().add(new DictImportResultVO.LineError(line.getNumber(), line.getText(), invalid));
                continue;
            }
            if (stopwordMapper.selectCount(new LambdaQueryWrapper<IkStopword>()
                    .eq(IkStopword::getWord, line.getText())) > 0) {
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }
            if (total >= properties.getMaxStopwords()) {
                result.setFailed(result.getFailed() + 1);
                result.getErrors().add(new DictImportResultVO.LineError(
                        line.getNumber(), line.getText(), "停用词数量已达上限"));
                continue;
            }
            IkStopword entity = new IkStopword();
            entity.setWord(line.getText());
            entity.setEnabled(1);
            stopwordMapper.insert(entity);
            total++;
            result.setInserted(result.getInserted() + 1);
        }
        snapshotService.invalidate();
        changeLogService.success("STOPWORD", "IMPORT", null, null, summary(result), "批量导入停用词");
        return result;
    }

    // ==================== 同义词 ====================

    public PageResult<SynonymGroupVO> pageSynonymGroups(SynonymQueryReq req) {
        req.normalize();
        List<SynonymWord> rows = synonymMapper.selectList(new LambdaQueryWrapper<SynonymWord>()
                .orderByAsc(SynonymWord::getGroupNo)
                .orderByAsc(SynonymWord::getSortOrder)
                .orderByAsc(SynonymWord::getId));
        List<SynonymGroupVO> groups = new ArrayList<>();
        for (SynonymGroupVO group : toGroups(rows)) {
            if (req.getCategory() != null && !req.getCategory().isEmpty()
                    && !req.getCategory().equalsIgnoreCase(group.getCategory())) {
                continue;
            }
            if (req.getEnabled() != null && !req.getEnabled().equals(group.getEnabled())) {
                continue;
            }
            if (req.hasKeyword()) {
                boolean matched = false;
                for (SynonymGroupVO.Word word : group.getWords()) {
                    if (word.getWord() != null
                            && word.getWord().toLowerCase(Locale.ROOT)
                            .contains(req.getKeyword().toLowerCase(Locale.ROOT))) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    continue;
                }
            }
            groups.add(group);
        }
        groups.sort((a, b) -> Integer.compare(b.getGroupNo(), a.getGroupNo()));
        int pageNum = req.getPageNum();
        int pageSize = req.getPageSize();
        int from = Math.min((pageNum - 1) * pageSize, groups.size());
        int to = Math.min(from + pageSize, groups.size());
        PageResult<SynonymGroupVO> result = new PageResult<>();
        result.setList(new ArrayList<>(groups.subList(from, to)));
        result.setTotal(groups.size());
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        result.setPages(pageSize <= 0 ? 0 : (groups.size() + pageSize - 1) / pageSize);
        result.setHasNext(to < groups.size());
        result.setTruncated(req.isTruncated());
        return result;
    }

    public synchronized SynonymGroupVO createSynonymGroup(SynonymGroupSaveReq req) {
        if (synonymMapper.selectList(null).stream().map(SynonymWord::getGroupNo)
                .distinct().count() >= properties.getMaxSynonymGroups()) {
            throw new BizException(ResultCode.DICT_SYNONYM_INVALID,
                    "同义词组数量已达上限（" + properties.getMaxSynonymGroups() + "）");
        }
        int groupNo = nextGroupNo();
        saveGroup(groupNo, req, true);
        snapshotService.invalidate();
        changeLogService.success("SYNONYM", "CREATE", (long) groupNo, null, req, "新增同义词组");
        return groupOf(groupNo);
    }

    @Transactional(rollbackFor = Exception.class)
    public synchronized SynonymGroupVO updateSynonymGroup(Integer groupNo, SynonymGroupSaveReq req) {
        List<SynonymWord> existing = rowsOfGroup(groupNo);
        if (existing.isEmpty()) {
            throw new BizException(ResultCode.DICT_ENTRY_NOT_FOUND, "同义词组不存在：" + groupNo);
        }
        SynonymGroupVO before = toGroup(existing);
        saveGroup(groupNo, req, false);
        snapshotService.invalidate();
        changeLogService.success("SYNONYM", "UPDATE", (long) groupNo, before, req, "修改同义词组");
        return groupOf(groupNo);
    }

    public synchronized void deleteSynonymGroup(Integer groupNo) {
        List<SynonymWord> existing = rowsOfGroup(groupNo);
        if (existing.isEmpty()) {
            throw new BizException(ResultCode.DICT_ENTRY_NOT_FOUND, "同义词组不存在：" + groupNo);
        }
        synonymMapper.delete(new LambdaQueryWrapper<SynonymWord>()
                .eq(SynonymWord::getGroupNo, groupNo));
        snapshotService.invalidate();
        changeLogService.success("SYNONYM", "DELETE", (long) groupNo, toGroup(existing), null, "删除同义词组");
    }

    public String exportSynonyms() {
        List<SynonymWord> rows = synonymMapper.selectList(new LambdaQueryWrapper<SynonymWord>()
                .orderByAsc(SynonymWord::getGroupNo)
                .orderByAsc(SynonymWord::getSortOrder));
        StringBuilder sb = new StringBuilder("# 等价组：A,B,C；单向组：A => B,C\n");
        for (SynonymGroupVO group : toGroups(rows)) {
            List<String> other = new ArrayList<>();
            for (SynonymGroupVO.Word word : group.getWords()) {
                if (!word.getWord().equals(group.getPrimaryWord())) {
                    other.add(word.getWord());
                }
            }
            if ("ONE_WAY".equalsIgnoreCase(group.getSynonymType())) {
                sb.append(group.getPrimaryWord()).append(" => ")
                        .append(String.join(",", other)).append('\n');
            } else {
                List<String> all = new ArrayList<>();
                all.add(group.getPrimaryWord());
                all.addAll(other);
                sb.append(String.join(",", all)).append('\n');
            }
        }
        return sb.toString();
    }

    public DictImportResultVO importSynonyms(DictImportReq req) {
        DictImportResultVO result = new DictImportResultVO();
        List<DictTextSupport.Line> lines = DictTextSupport.lines(req.getContent());
        result.setTotal(lines.size());
        for (DictTextSupport.Line line : lines) {
            try {
                SynonymGroupSaveReq save = parseSynonymLine(line.getText());
                createSynonymGroup(save);
                result.setInserted(result.getInserted() + 1);
            } catch (BizException e) {
                result.setFailed(result.getFailed() + 1);
                result.getErrors().add(new DictImportResultVO.LineError(
                        line.getNumber(), line.getText(), e.getMessage()));
            }
        }
        return result;
    }

    private void saveGroup(int groupNo, SynonymGroupSaveReq req, boolean creating) {
        List<String> words = normalizeWords(req.getWords());
        if (words.size() < 2 || words.size() > properties.getMaxWordsPerGroup()) {
            throw new BizException(ResultCode.DICT_SYNONYM_INVALID,
                    "同义词组需包含 2~" + properties.getMaxWordsPerGroup() + " 个词");
        }
        for (String word : words) {
            String invalid = DictTextSupport.validateToken(word);
            if (invalid != null) {
                throw new BizException(ResultCode.DICT_SYNONYM_INVALID, invalid + "：" + word);
            }
            if (stopwordMapper.selectCount(new LambdaQueryWrapper<IkStopword>()
                    .eq(IkStopword::getWord, word)) > 0) {
                throw new BizException(ResultCode.DICT_SYNONYM_CONFLICT,
                        "同义词不能与停用词重复：" + word);
            }
            long conflict = synonymMapper.selectCount(new LambdaQueryWrapper<SynonymWord>()
                    .eq(SynonymWord::getWord, word)
                    .ne(SynonymWord::getGroupNo, groupNo));
            if (conflict > 0) {
                throw new BizException(ResultCode.DICT_SYNONYM_CONFLICT,
                        "该词已在其他同义词组中：" + word);
            }
        }

        String primary = req.getPrimaryWord() == null ? null : req.getPrimaryWord().trim();
        if (primary == null || primary.isEmpty()) {
            primary = words.get(0);
        }
        String primaryLower = primary.toLowerCase(Locale.ROOT);
        String matchedPrimary = null;
        for (String word : words) {
            if (word.toLowerCase(Locale.ROOT).equals(primaryLower)) {
                matchedPrimary = word;
                break;
            }
        }
        if (matchedPrimary == null) {
            throw new BizException(ResultCode.DICT_SYNONYM_INVALID, "主词必须属于该组：" + primary);
        }

        String type = req.getEquivalent() == null
                ? upperOrDefault(req.getSynonymType(), "EQUIVALENT")
                : (req.getEquivalent() ? "EQUIVALENT" : "ONE_WAY");
        if (!SYNONYM_TYPES.contains(type)) {
            throw new BizException(ResultCode.DICT_SYNONYM_INVALID, "同义词类型只能是 EQUIVALENT 或 ONE_WAY");
        }
        String category = upperOrDefault(req.getCategory(), "OTHER");
        if (!CATEGORIES.contains(category)) {
            category = "OTHER";
        }
        int enabled = enabledOf(req.getEnabled());

        if (!creating) {
            synonymMapper.delete(new LambdaQueryWrapper<SynonymWord>()
                    .eq(SynonymWord::getGroupNo, groupNo));
        }
        int index = 0;
        for (String word : words) {
            SynonymWord entity = new SynonymWord();
            entity.setGroupNo(groupNo);
            entity.setWord(word);
            entity.setIsPrimary(word.equals(matchedPrimary) ? 1 : 0);
            entity.setSynonymType(type);
            entity.setCategory(category);
            entity.setEnabled(enabled);
            entity.setSortOrder(index++);
            synonymMapper.insert(entity);
        }
    }

    private int nextGroupNo() {
        List<SynonymWord> rows = synonymMapper.selectList(new LambdaQueryWrapper<SynonymWord>()
                .orderByDesc(SynonymWord::getGroupNo).last("LIMIT 1"));
        return rows.isEmpty() ? 1 : rows.get(0).getGroupNo() + 1;
    }

    private List<SynonymWord> rowsOfGroup(Integer groupNo) {
        return synonymMapper.selectList(new LambdaQueryWrapper<SynonymWord>()
                .eq(SynonymWord::getGroupNo, groupNo)
                .orderByAsc(SynonymWord::getSortOrder)
                .orderByAsc(SynonymWord::getId));
    }

    private SynonymGroupVO groupOf(Integer groupNo) {
        return toGroup(rowsOfGroup(groupNo));
    }

    private List<SynonymGroupVO> toGroups(List<SynonymWord> rows) {
        Map<Integer, List<SynonymWord>> groups = new LinkedHashMap<>();
        for (SynonymWord row : rows) {
            groups.computeIfAbsent(row.getGroupNo(), k -> new ArrayList<>()).add(row);
        }
        List<SynonymGroupVO> result = new ArrayList<>(groups.size());
        for (List<SynonymWord> group : groups.values()) {
            result.add(toGroup(group));
        }
        return result;
    }

    private SynonymGroupVO toGroup(List<SynonymWord> group) {
        SynonymGroupVO vo = new SynonymGroupVO();
        if (group.isEmpty()) {
            return vo;
        }
        SynonymWord primary = group.get(0);
        for (SynonymWord word : group) {
            if (word.getIsPrimary() != null && word.getIsPrimary() == 1) {
                primary = word;
                break;
            }
        }
        vo.setGroupNo(primary.getGroupNo());
        vo.setPrimaryWord(primary.getWord());
        vo.setSynonymType(primary.getSynonymType());
        vo.setCategory(primary.getCategory());
        vo.setEnabled(primary.getEnabled());
        LocalDateTime latest = null;
        for (SynonymWord word : group) {
            SynonymGroupVO.Word item = new SynonymGroupVO.Word();
            item.setId(word.getId());
            item.setWord(word.getWord());
            item.setIsPrimary(word.getIsPrimary());
            item.setEnabled(word.getEnabled());
            item.setSortOrder(word.getSortOrder());
            vo.getWords().add(item);
            if (latest == null || (word.getUpdateTime() != null && word.getUpdateTime().isAfter(latest))) {
                latest = word.getUpdateTime();
            }
        }
        vo.setUpdateTime(latest);
        return vo;
    }

    private SynonymGroupSaveReq parseSynonymLine(String line) {
        SynonymGroupSaveReq req = new SynonymGroupSaveReq();
        String primary = null;
        String wordsPart = line;
        int arrow = line.indexOf("=>");
        if (arrow >= 0) {
            primary = line.substring(0, arrow).trim();
            wordsPart = line.substring(arrow + 2).trim();
            req.setSynonymType("ONE_WAY");
        }
        List<String> words = new ArrayList<>();
        if (primary != null && !primary.isEmpty()) {
            words.add(primary);
        }
        for (String word : wordsPart.split("[,，]")) {
            if (!word.trim().isEmpty()) {
                words.add(word.trim());
            }
        }
        req.setWords(words);
        req.setPrimaryWord(primary);
        return req;
    }

    private List<String> normalizeWords(List<String> words) {
        if (words == null) {
            return Collections.emptyList();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String word : words) {
            if (word != null && !word.trim().isEmpty()) {
                unique.add(word.trim());
            }
        }
        return new ArrayList<>(unique);
    }

    // ==================== 拼音词典 ====================

    public PageResult<PinyinDict> pagePinyin(PinyinQueryReq req) {
        req.normalize();
        LambdaQueryWrapper<PinyinDict> wrapper = new LambdaQueryWrapper<PinyinDict>()
                .like(req.hasKeyword(), PinyinDict::getWord, req.getKeyword())
                .eq(req.getEnabled() != null, PinyinDict::getEnabled, req.getEnabled())
                .orderByDesc(PinyinDict::getUpdateTime)
                .orderByDesc(PinyinDict::getId);
        return PageResult.of(pinyinMapper.selectPage(req.toPage(), wrapper), req.isTruncated());
    }

    public PinyinDict createPinyin(PinyinSaveReq req) {
        String word = req.getWord() == null ? null : req.getWord().trim();
        String invalid = DictTextSupport.validateToken(word);
        if (invalid != null) {
            throw new BizException(ResultCode.DICT_PINYIN_INVALID, invalid);
        }
        String pinyin = req.getPinyin() == null ? null : req.getPinyin().trim();
        invalid = DictTextSupport.validatePinyin(pinyin);
        if (invalid != null) {
            throw new BizException(ResultCode.DICT_PINYIN_INVALID, invalid);
        }
        if (pinyinMapper.selectCount(new LambdaQueryWrapper<PinyinDict>()
                .eq(PinyinDict::getWord, word)) > 0) {
            throw new BizException(ResultCode.DICT_WORD_DUPLICATED, "拼音词条已存在：" + word);
        }
        PinyinDict entity = new PinyinDict();
        entity.setWord(word);
        entity.setPinyin(pinyin);
        entity.setEnabled(enabledOf(req.getEnabled()));
        pinyinMapper.insert(entity);
        afterPinyinChange();
        changeLogService.success("PINYIN", "CREATE", entity.getId(), null, entity, "新增拼音词典");
        return entity;
    }

    public PinyinDict updatePinyin(Long id, PinyinSaveReq req) {
        PinyinDict existing = requirePinyin(id);
        String word = req.getWord() == null ? null : req.getWord().trim();
        String invalid = DictTextSupport.validateToken(word);
        if (invalid != null) {
            throw new BizException(ResultCode.DICT_PINYIN_INVALID, invalid);
        }
        String pinyin = req.getPinyin() == null ? null : req.getPinyin().trim();
        invalid = DictTextSupport.validatePinyin(pinyin);
        if (invalid != null) {
            throw new BizException(ResultCode.DICT_PINYIN_INVALID, invalid);
        }
        if (pinyinMapper.selectCount(new LambdaQueryWrapper<PinyinDict>()
                .eq(PinyinDict::getWord, word)
                .ne(PinyinDict::getId, id)) > 0) {
            throw new BizException(ResultCode.DICT_WORD_DUPLICATED, "拼音词条已存在：" + word);
        }
        PinyinDict before = copy(existing);
        existing.setWord(word);
        existing.setPinyin(pinyin);
        existing.setEnabled(enabledOf(req.getEnabled()));
        pinyinMapper.updateById(existing);
        afterPinyinChange();
        changeLogService.success("PINYIN", "UPDATE", id, before, existing, "修改拼音词典");
        return existing;
    }

    public void deletePinyin(Long id) {
        PinyinDict existing = requirePinyin(id);
        pinyinMapper.deleteById(id);
        afterPinyinChange();
        changeLogService.success("PINYIN", "DELETE", id, existing, null, "删除拼音词典");
    }

    public String exportPinyin() {
        StringBuilder sb = new StringBuilder();
        for (PinyinDict row : pinyinMapper.selectList(new LambdaQueryWrapper<PinyinDict>()
                .orderByAsc(PinyinDict::getWord))) {
            sb.append(row.getWord()).append('\t').append(row.getPinyin()).append('\n');
        }
        return sb.toString();
    }

    public DictImportResultVO importPinyin(DictImportReq req) {
        DictImportResultVO result = new DictImportResultVO();
        List<DictTextSupport.Line> lines = DictTextSupport.lines(req.getContent());
        result.setTotal(lines.size());
        for (DictTextSupport.Line line : lines) {
            String[] parts = line.getText().split("[\\t ]+");
            if (parts.length < 2) {
                result.setFailed(result.getFailed() + 1);
                result.getErrors().add(new DictImportResultVO.LineError(
                        line.getNumber(), line.getText(), "格式应为「词 拼音」"));
                continue;
            }
            try {
                PinyinSaveReq save = new PinyinSaveReq();
                save.setWord(parts[0]);
                save.setPinyin(parts[1]);
                createPinyin(save);
                result.setInserted(result.getInserted() + 1);
            } catch (BizException e) {
                result.setFailed(result.getFailed() + 1);
                result.getErrors().add(new DictImportResultVO.LineError(
                        line.getNumber(), line.getText(), e.getMessage()));
            }
        }
        return result;
    }

    private void afterPinyinChange() {
        snapshotService.invalidate();
        snapshotService.applyPinyinOverrides();
        pinyinRefresher.refreshAffected();
    }

    // ==================== 分词测试 / 状态 / 重载 ====================

    /**
     * @param previewWords 逗号分隔的「修改后」词条；非空时强制走本地匹配（ES 不知道未保存的词）
     */
    public DictAnalyzeVO analyze(String text, String analyzer, String previewWords) {
        if (text == null || text.trim().isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID, "text 不能为空");
        }
        if (text.length() > 500) {
            throw new BizException(ResultCode.SEARCH_KEYWORD_TOO_LONG);
        }
        String analyzerName = analyzer == null || analyzer.trim().isEmpty() ? "ik_smart" : analyzer.trim();
        Set<String> extras = new LinkedHashSet<>();
        if (previewWords != null) {
            for (String word : previewWords.split("[,，]")) {
                if (!word.trim().isEmpty()) {
                    extras.add(word.trim());
                }
            }
        }
        // ik_* 分析器只存在于装了 analysis-ik 的 ES 里。没装时去问 ES 只会拿到 400，
        // 再由 catch 兜回本地——多一次无用往返 + 每次一条 WARN。这里直接走本地。
        boolean ikRequested = analyzerName != null && analyzerName.startsWith("ik_");
        if (extras.isEmpty() && esClient.isAvailable()
                && !(ikRequested && !esClient.hasIkPlugin())) {
            try {
                return esClient.analyze(text, analyzerName);
            } catch (RuntimeException e) {
                log.warn("ES 分词失败，改用本地匹配：{}", e.toString());
            }
        }
        return localAnalyzer.analyze(text, analyzerName, extras);
    }

    public DictStatusVO status() {
        DictStatusVO vo = new DictStatusVO();
        vo.setEsEnabled(esClient.isEnabled());
        vo.setEsUrl(esClient.url());
        vo.setSearchEngine(searchProperties.getEngine());
        boolean available = esClient.isAvailable();
        vo.setEsAvailable(available);
        if (available) {
            vo.setEsVersion(esClient.getVersion());
            vo.setPinyinPlugin(esClient.hasPinyinPlugin());
            vo.setStconvertPlugin(esClient.hasStconvertPlugin());
            if (!esClient.hasIkPlugin()) {
                vo.getWarnings().add("未安装 analysis-ik 插件，中文分词将退化为标准分词器");
            }
            if (!esClient.hasPinyinPlugin()) {
                vo.getWarnings().add("未安装 analysis-pinyin 插件，拼音检索由 MySQL 拼音列兜底");
            }
            if (!esClient.hasStconvertPlugin()) {
                vo.getWarnings().add("未安装 analysis-stconvert 插件，繁简互搜由应用层 opencc4j 兜底");
            }
        } else if (esClient.isEnabled()) {
            vo.getWarnings().add("Elasticsearch 不可达：" + esClient.url() + "；搜索已降级为 MySQL");
        } else {
            vo.getWarnings().add("未启用 Elasticsearch；词典能力由应用层与 MySQL 拼音列提供");
        }

        LocalDateTime lastPull = null;
        for (String type : DictSnapshotService.types()) {
            DictStatusVO.DictStatVO stat = new DictStatusVO.DictStatVO();
            stat.setType(type);
            try {
                DictSnapshotService.Snapshot snapshot = snapshotService.get(type);
                stat.setCount(snapshot.getCount());
                stat.setVersion(snapshot.getVersion());
                stat.setLastModified(snapshot.getLastModifiedHeader());
                stat.setLastPullTime(snapshotService.lastPull(type));
                if (lastPull == null || (stat.getLastPullTime() != null
                        && stat.getLastPullTime().isAfter(lastPull))) {
                    lastPull = stat.getLastPullTime();
                }
            } catch (RuntimeException e) {
                vo.getWarnings().add("读取 " + type + " 快照失败：" + e.getMessage());
                stat.setCount(0);
            }
            vo.getDicts().add(stat);
        }
        vo.setLastPullTime(lastPull);

        DictSyncLog latest = latestSyncLog();
        if (latest != null) {
            DictStatusVO.DictSyncLogVO sync = new DictStatusVO.DictSyncLogVO();
            sync.setSyncType(latest.getSyncType());
            sync.setStatus(latest.getStatus());
            sync.setItemCount(latest.getItemCount());
            sync.setCostMs(latest.getCostMs());
            sync.setMessage(latest.getMessage());
            sync.setCreateTime(latest.getCreateTime());
            vo.setLastSync(sync);
        }

        if (!esClient.isEnabled()) {
            vo.setEffectiveStatus("本地兜底");
            vo.setNotice("当前未启用 ES：主词典/停用词/同义词/繁简/多音字均在应用层生效；"
                    + "主词典新增词条对 MySQL LIKE 检索立即生效，无需重建索引");
        } else if (!available) {
            vo.setEffectiveStatus("本地兜底");
            vo.setNotice("ES 不可达，搜索已降级为 MySQL；主词典新增词条需在 ES 恢复后重建索引");
        } else if (!esClient.hasIkPlugin()) {
            // 没装 IK 时 ES 不会来拉远程词典，lastPull 永远为空。
            // 此时再显示「等待 ES 首次拉取」会让人一直等一个不会发生的事。
            vo.setEffectiveStatus("已连通（无 IK 插件）");
            vo.setNotice("ES 已连通但未安装 analysis-ik：中文按二元词（cjk_bigram）检索、"
                    + "拼音由应用侧物化后写入索引；主词典词条只影响应用层改写，改完需要重建索引");
        } else if (lastPull == null) {
            vo.setEffectiveStatus("等待 ES 首次拉取");
            vo.setNotice("ES 已连通但尚未拉取远程词典；IK 默认每 60 秒轮询一次（BR-20-009）");
        } else if (lastPull.isBefore(LocalDateTime.now().minusMinutes(5))) {
            vo.setEffectiveStatus("生效中（拉取超时）");
            vo.setNotice("ES 超过 5 分钟未拉取词典，请检查 IK 远程词典配置与网络（EX-20-001-6）");
        } else {
            vo.setEffectiveStatus("已生效");
            vo.setNotice("新词条对已索引文档不生效，需要重建索引后才能命中历史文档（BR-20-010）");
        }
        return vo;
    }

    public void reload() {
        if (!esClient.isAvailable()) {
            throw new BizException(ResultCode.DICT_ES_UNAVAILABLE,
                    "Elasticsearch 未启用或不可达，无法重载分析器");
        }
        long started = System.currentTimeMillis();
        try {
            esClient.reloadSearchAnalyzers();
            writeSyncLog("RELOAD", "SUCCESS", 0, System.currentTimeMillis() - started,
                    "已触发 _reload_search_analyzers");
            changeLogService.success("SYNONYM", "RELOAD", null, null, null, "手动重载分析器");
        } catch (RuntimeException e) {
            writeSyncLog("RELOAD", "FAIL", 0, System.currentTimeMillis() - started, e.getMessage());
            throw new BizException(ResultCode.DICT_ES_UNAVAILABLE, "重载失败：" + e.getMessage(), e);
        }
    }

    public DictSyncResultVO syncArtists() {
        return artistSyncService.sync();
    }

    public DictSyncResultVO reindex() {
        DictSyncResultVO result = reindexService.reindex();
        changeLogService.success("MAIN", "REINDEX", null, null, result, "重建 ES 索引");
        return result;
    }

    public PageResult<DictChangeLog> pageChangeLog(DictChangeLogQueryReq req) {
        req.normalize();
        LambdaQueryWrapper<DictChangeLog> wrapper = new LambdaQueryWrapper<DictChangeLog>()
                .eq(req.getDictType() != null && !req.getDictType().isEmpty(),
                        DictChangeLog::getDictType, upper(req.getDictType()))
                .eq(req.getChangeType() != null && !req.getChangeType().isEmpty(),
                        DictChangeLog::getChangeType, upper(req.getChangeType()))
                .eq(req.getOperatorId() != null, DictChangeLog::getOperatorId, req.getOperatorId())
                .orderByDesc(DictChangeLog::getCreateTime)
                .orderByDesc(DictChangeLog::getId);
        return PageResult.of(changeLogMapper.selectPage(req.toPage(), wrapper), req.isTruncated());
    }

    // ==================== 内部工具 ====================

    private IkDictWord requireWord(Long id) {
        IkDictWord word = wordMapper.selectById(id);
        if (word == null) {
            throw new BizException(ResultCode.DICT_ENTRY_NOT_FOUND);
        }
        return word;
    }

    private IkStopword requireStopword(Long id) {
        IkStopword word = stopwordMapper.selectById(id);
        if (word == null) {
            throw new BizException(ResultCode.DICT_ENTRY_NOT_FOUND);
        }
        return word;
    }

    private PinyinDict requirePinyin(Long id) {
        PinyinDict row = pinyinMapper.selectById(id);
        if (row == null) {
            throw new BizException(ResultCode.DICT_ENTRY_NOT_FOUND);
        }
        return row;
    }

    private void requireManual(String source) {
        if (!SOURCE_MANUAL.equals(source)) {
            throw new BizException(ResultCode.DICT_AUTO_WORD_READONLY,
                    "自动同步词条不可手工修改或删除；如需停用请切换启用状态");
        }
    }

    private String validateMainWord(String raw) {
        String word = raw == null ? null : raw.trim();
        String invalid = DictTextSupport.validateWord(word,
                properties.getWordMinLength(), properties.getWordMaxLength());
        if (invalid != null) {
            throw new BizException(ResultCode.DICT_WORD_INVALID, invalid);
        }
        return word;
    }

    private String validateStopword(String raw) {
        String word = raw == null ? null : raw.trim();
        String invalid = stopwordInvalidReason(word);
        if (invalid != null) {
            throw new BizException(ResultCode.DICT_WORD_INVALID, invalid);
        }
        return word;
    }

    private String stopwordInvalidReason(String word) {
        String invalid = DictTextSupport.validateToken(word);
        if (invalid != null) {
            return invalid;
        }
        if (word.matches("\\d{4}")) {
            int year = Integer.parseInt(word);
            if (year >= 1980 && year <= 2029) {
                return "1980~2029 的年份/年代不能作为停用词（BR-20-021）";
            }
        }
        if (synonymMapper.selectCount(new LambdaQueryWrapper<SynonymWord>()
                .eq(SynonymWord::getWord, word)) > 0) {
            return "该词已存在于同义词组，不能同时作为停用词";
        }
        return null;
    }

    private static String[] splitWordLine(String line) {
        if (line.contains("\t")) {
            return line.split("\\t+");
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(.+?)\\s+(\\d{1,7})(?:\\s+([A-Za-z]{1,8}))?$")
                .matcher(line);
        if (matcher.matches()) {
            return matcher.group(3) == null
                    ? new String[]{matcher.group(1), matcher.group(2)}
                    : new String[]{matcher.group(1), matcher.group(2), matcher.group(3)};
        }
        return new String[]{line};
    }

    private int intOrDefault(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private int frequencyOf(Integer frequency) {
        if (frequency == null) {
            return 100;
        }
        if (frequency < 1) {
            throw new BizException(ResultCode.DICT_WORD_INVALID, "词频必须为正整数");
        }
        return frequency;
    }

    private String posOf(String pos) {
        if (pos == null || pos.trim().isEmpty()) {
            return "n";
        }
        String value = pos.trim();
        if (value.length() > 8) {
            throw new BizException(ResultCode.DICT_WORD_INVALID, "词性不能超过 8 个字符");
        }
        return value;
    }

    private int enabledOf(Integer enabled) {
        return enabled != null && enabled == 0 ? 0 : 1;
    }

    private String dictTypeOf(String dictType) {
        String value = upperOrDefault(dictType, "MAIN");
        if (!DICT_TYPES.contains(value)) {
            throw new BizException(ResultCode.DICT_WORD_INVALID,
                    "词库类型只能是 MAIN / NET_SLANG / REGION");
        }
        return value;
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String upperOrDefault(String value, String defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String summary(DictImportResultVO result) {
        return "total=" + result.getTotal() + ", inserted=" + result.getInserted()
                + ", updated=" + result.getUpdated() + ", skipped=" + result.getSkipped()
                + ", failed=" + result.getFailed();
    }

    private DictSyncLog latestSyncLog() {
        List<DictSyncLog> rows = syncLogMapper.selectList(new LambdaQueryWrapper<DictSyncLog>()
                .orderByDesc(DictSyncLog::getCreateTime)
                .orderByDesc(DictSyncLog::getId)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void writeSyncLog(String syncType, String status, int itemCount, long costMs, String message) {
        try {
            DictSyncLog entry = new DictSyncLog();
            entry.setSyncType(syncType);
            entry.setStatus(status);
            entry.setItemCount(itemCount);
            entry.setCostMs(costMs);
            entry.setMessage(DictTextSupport.brief(message, 480));
            syncLogMapper.insert(entry);
        } catch (RuntimeException e) {
            log.warn("写词典同步日志失败 type={} cause={}", syncType, e.toString());
        }
    }

    private static IkDictWord copy(IkDictWord source) {
        IkDictWord target = new IkDictWord();
        target.setId(source.getId());
        target.setWord(source.getWord());
        target.setFrequency(source.getFrequency());
        target.setPos(source.getPos());
        target.setSource(source.getSource());
        target.setRefId(source.getRefId());
        target.setEnabled(source.getEnabled());
        target.setDictType(source.getDictType());
        target.setCreateTime(source.getCreateTime());
        target.setUpdateTime(source.getUpdateTime());
        return target;
    }

    private static IkStopword copy(IkStopword source) {
        IkStopword target = new IkStopword();
        target.setId(source.getId());
        target.setWord(source.getWord());
        target.setEnabled(source.getEnabled());
        target.setCreateTime(source.getCreateTime());
        target.setUpdateTime(source.getUpdateTime());
        return target;
    }

    private static PinyinDict copy(PinyinDict source) {
        PinyinDict target = new PinyinDict();
        target.setId(source.getId());
        target.setWord(source.getWord());
        target.setPinyin(source.getPinyin());
        target.setEnabled(source.getEnabled());
        target.setCreateTime(source.getCreateTime());
        target.setUpdateTime(source.getUpdateTime());
        return target;
    }
}
