package com.example.newmusic.module.dict.controller;

import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
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
import com.example.newmusic.module.dict.entity.IkDictWord;
import com.example.newmusic.module.dict.entity.IkStopword;
import com.example.newmusic.module.dict.entity.PinyinDict;
import com.example.newmusic.module.dict.service.DictAdminService;
import com.example.newmusic.module.dict.vo.DictAnalyzeVO;
import com.example.newmusic.module.dict.vo.DictImportResultVO;
import com.example.newmusic.module.dict.vo.DictStatusVO;
import com.example.newmusic.module.dict.vo.DictSyncResultVO;
import com.example.newmusic.module.dict.vo.SynonymGroupVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.nio.charset.StandardCharsets;

/**
 * 后台词典管理（模块 11 FR-20-008）。
 *
 * <p>权限拆成三档而不是一把 {@code admin:dict:manage}：
 * 只读运营（查看分词、导出）给 view；改词给 edit；重载/重建索引会直接影响线上搜索，
 * 单独给 reindex。这样「让实习生帮忙加几个词」不需要把重建索引的权限也交出去。
 */
@Api(tags = "后台接口-搜索词典")
@RestController
@RequestMapping("/api/v1/admin/dict")
public class AdminDictController {

    private final DictAdminService dictAdminService;

    public AdminDictController(DictAdminService dictAdminService) {
        this.dictAdminService = dictAdminService;
    }

    // ==================== 状态 / 分词测试 ====================

    @ApiOperation("词典生效状态")
    @GetMapping("/status")
    @PreAuthorize("hasAuthority('admin:dict:view')")
    public R<DictStatusVO> status() {
        return R.ok(dictAdminService.status());
    }

    @ApiOperation(value = "分词测试", notes = "previewWords = 逗号分隔的待添加词条，用于保存前预览")
    @GetMapping("/analyze")
    @PreAuthorize("hasAuthority('admin:dict:view')")
    public R<DictAnalyzeVO> analyze(@RequestParam String text,
                                    @RequestParam(required = false) String analyzer,
                                    @RequestParam(required = false) String previewWords) {
        return R.ok(dictAdminService.analyze(text, analyzer, previewWords));
    }

    @ApiOperation("变更历史")
    @GetMapping("/change-log")
    @PreAuthorize("hasAuthority('admin:dict:view')")
    public R<PageResult<DictChangeLog>> changeLog(DictChangeLogQueryReq query) {
        return R.ok(dictAdminService.pageChangeLog(query));
    }

    // ==================== 主词典 ====================

    @ApiOperation("主词典列表")
    @GetMapping("/words")
    @PreAuthorize("hasAuthority('admin:dict:view')")
    public R<PageResult<IkDictWord>> words(DictWordQueryReq query) {
        return R.ok(dictAdminService.pageWords(query));
    }

    @ApiOperation("新增主词典词条")
    @PostMapping("/words")
    @PreAuthorize("hasAuthority('admin:dict:edit')")
    @AdminOpLog(type = "CREATE", target = "DICT", description = "新增主词典词条")
    public R<IkDictWord> createWord(@RequestBody @Valid DictWordSaveReq req) {
        return R.ok(dictAdminService.createWord(req));
    }

    @ApiOperation("修改主词典词条")
    @PutMapping("/words/{id}")
    @PreAuthorize("hasAuthority('admin:dict:edit')")
    @AdminOpLog(type = "UPDATE", target = "DICT", description = "修改主词典词条")
    public R<IkDictWord> updateWord(@PathVariable Long id, @RequestBody @Valid DictWordSaveReq req) {
        return R.ok(dictAdminService.updateWord(id, req));
    }

    @ApiOperation(value = "删除主词典词条", notes = "歌手自动同步的词条不可手工删除（71004）")
    @DeleteMapping("/words/{id}")
    @PreAuthorize("hasAuthority('admin:dict:edit')")
    @AdminOpLog(type = "DELETE", target = "DICT", description = "删除主词典词条")
    public R<Long> deleteWord(@PathVariable Long id) {
        dictAdminService.deleteWord(id);
        return R.ok(id);
    }

    @ApiOperation("批量导入主词典")
    @PostMapping("/words/import")
    @PreAuthorize("hasAuthority('admin:dict:edit')")
    @AdminOpLog(type = "IMPORT", target = "DICT", description = "批量导入主词典")
    public R<DictImportResultVO> importWords(@RequestBody @Valid DictImportReq req) {
        return R.ok(dictAdminService.importWords(req));
    }

    @ApiOperation("导出主词典")
    @GetMapping("/words/export")
    @PreAuthorize("hasAuthority('admin:dict:view')")
    @AdminOpLog(type = "EXPORT", target = "DICT", description = "导出主词典")
    public ResponseEntity<byte[]> exportWords(@RequestParam(required = false) String source,
                                              @RequestParam(required = false) String dictType,
                                              @RequestParam(required = false) Integer enabled) {
        return text(dictAdminService.exportWords(source, dictType, enabled), "dict-words.txt");
    }

    // ==================== 停用词 ====================

    @ApiOperation("停用词列表")
    @GetMapping("/stopwords")
    @PreAuthorize("hasAuthority('admin:dict:view')")
    public R<PageResult<IkStopword>> stopwords(StopwordQueryReq query) {
        return R.ok(dictAdminService.pageStopwords(query));
    }

    @ApiOperation("新增停用词")
    @PostMapping("/stopwords")
    @PreAuthorize("hasAuthority('admin:dict:edit')")
    @AdminOpLog(type = "CREATE", target = "DICT", description = "新增停用词")
    public R<IkStopword> createStopword(@RequestBody @Valid StopwordSaveReq req) {
        return R.ok(dictAdminService.createStopword(req));
    }

    @ApiOperation("修改停用词")
    @PutMapping("/stopwords/{id}")
    @PreAuthorize("hasAuthority('admin:dict:edit')")
    @AdminOpLog(type = "UPDATE", target = "DICT", description = "修改停用词")
    public R<IkStopword> updateStopword(@PathVariable Long id, @RequestBody @Valid StopwordSaveReq req) {
        return R.ok(dictAdminService.updateStopword(id, req));
    }

    @ApiOperation("删除停用词")
    @DeleteMapping("/stopwords/{id}")
    @PreAuthorize("hasAuthority('admin:dict:edit')")
    @AdminOpLog(type = "DELETE", target = "DICT", description = "删除停用词")
    public R<Long> deleteStopword(@PathVariable Long id) {
        dictAdminService.deleteStopword(id);
        return R.ok(id);
    }

    @ApiOperation("批量导入停用词")
    @PostMapping("/stopwords/import")
    @PreAuthorize("hasAuthority('admin:dict:edit')")
    @AdminOpLog(type = "IMPORT", target = "DICT", description = "批量导入停用词")
    public R<DictImportResultVO> importStopwords(@RequestBody @Valid DictImportReq req) {
        return R.ok(dictAdminService.importStopwords(req));
    }

    @ApiOperation("导出停用词")
    @GetMapping("/stopwords/export")
    @PreAuthorize("hasAuthority('admin:dict:view')")
    @AdminOpLog(type = "EXPORT", target = "DICT", description = "导出停用词")
    public ResponseEntity<byte[]> exportStopwords() {
        return text(dictAdminService.exportStopwords(), "dict-stopwords.txt");
    }

    // ==================== 同义词 ====================

    @ApiOperation("同义词组列表")
    @GetMapping("/synonyms")
    @PreAuthorize("hasAuthority('admin:dict:view')")
    public R<PageResult<SynonymGroupVO>> synonyms(SynonymQueryReq query) {
        return R.ok(dictAdminService.pageSynonymGroups(query));
    }

    @ApiOperation("新增同义词组")
    @PostMapping("/synonyms")
    @PreAuthorize("hasAuthority('admin:dict:edit')")
    @AdminOpLog(type = "CREATE", target = "DICT", description = "新增同义词组")
    public R<SynonymGroupVO> createSynonym(@RequestBody @Valid SynonymGroupSaveReq req) {
        return R.ok(dictAdminService.createSynonymGroup(req));
    }

    @ApiOperation("修改同义词组")
    @PutMapping("/synonyms/{groupNo}")
    @PreAuthorize("hasAuthority('admin:dict:edit')")
    @AdminOpLog(type = "UPDATE", target = "DICT", description = "修改同义词组")
    public R<SynonymGroupVO> updateSynonym(@PathVariable Integer groupNo,
                                           @RequestBody @Valid SynonymGroupSaveReq req) {
        return R.ok(dictAdminService.updateSynonymGroup(groupNo, req));
    }

    @ApiOperation("删除同义词组")
    @DeleteMapping("/synonyms/{groupNo}")
    @PreAuthorize("hasAuthority('admin:dict:edit')")
    @AdminOpLog(type = "DELETE", target = "DICT", description = "删除同义词组")
    public R<Integer> deleteSynonym(@PathVariable Integer groupNo) {
        dictAdminService.deleteSynonymGroup(groupNo);
        return R.ok(groupNo);
    }

    @ApiOperation("批量导入同义词组")
    @PostMapping("/synonyms/import")
    @PreAuthorize("hasAuthority('admin:dict:edit')")
    @AdminOpLog(type = "IMPORT", target = "DICT", description = "批量导入同义词组")
    public R<DictImportResultVO> importSynonyms(@RequestBody @Valid DictImportReq req) {
        return R.ok(dictAdminService.importSynonyms(req));
    }

    @ApiOperation("导出同义词组")
    @GetMapping("/synonyms/export")
    @PreAuthorize("hasAuthority('admin:dict:view')")
    @AdminOpLog(type = "EXPORT", target = "DICT", description = "导出同义词组")
    public ResponseEntity<byte[]> exportSynonyms() {
        return text(dictAdminService.exportSynonyms(), "dict-synonyms.txt");
    }

    // ==================== 拼音词典 ====================

    @ApiOperation("拼音词典列表")
    @GetMapping("/pinyin")
    @PreAuthorize("hasAuthority('admin:dict:view')")
    public R<PageResult<PinyinDict>> pinyin(PinyinQueryReq query) {
        return R.ok(dictAdminService.pagePinyin(query));
    }

    @ApiOperation("新增拼音词条")
    @PostMapping("/pinyin")
    @PreAuthorize("hasAuthority('admin:dict:edit')")
    @AdminOpLog(type = "CREATE", target = "DICT", description = "新增拼音词条")
    public R<PinyinDict> createPinyin(@RequestBody @Valid PinyinSaveReq req) {
        return R.ok(dictAdminService.createPinyin(req));
    }

    @ApiOperation("修改拼音词条")
    @PutMapping("/pinyin/{id}")
    @PreAuthorize("hasAuthority('admin:dict:edit')")
    @AdminOpLog(type = "UPDATE", target = "DICT", description = "修改拼音词条")
    public R<PinyinDict> updatePinyin(@PathVariable Long id, @RequestBody @Valid PinyinSaveReq req) {
        return R.ok(dictAdminService.updatePinyin(id, req));
    }

    @ApiOperation("删除拼音词条")
    @DeleteMapping("/pinyin/{id}")
    @PreAuthorize("hasAuthority('admin:dict:edit')")
    @AdminOpLog(type = "DELETE", target = "DICT", description = "删除拼音词条")
    public R<Long> deletePinyin(@PathVariable Long id) {
        dictAdminService.deletePinyin(id);
        return R.ok(id);
    }

    @ApiOperation("批量导入拼音词典")
    @PostMapping("/pinyin/import")
    @PreAuthorize("hasAuthority('admin:dict:edit')")
    @AdminOpLog(type = "IMPORT", target = "DICT", description = "批量导入拼音词典")
    public R<DictImportResultVO> importPinyin(@RequestBody @Valid DictImportReq req) {
        return R.ok(dictAdminService.importPinyin(req));
    }

    @ApiOperation("导出拼音词典")
    @GetMapping("/pinyin/export")
    @PreAuthorize("hasAuthority('admin:dict:view')")
    @AdminOpLog(type = "EXPORT", target = "DICT", description = "导出拼音词典")
    public ResponseEntity<byte[]> exportPinyin() {
        return text(dictAdminService.exportPinyin(), "dict-pinyin.txt");
    }

    // ==================== 重载 / 同步 / 重建索引 ====================

    @ApiOperation(value = "重载分析器", notes = "ES 不可用时返回 71008；同义词/停用词无需重建索引")
    @PostMapping("/reload")
    @PreAuthorize("hasAuthority('admin:dict:reindex')")
    @AdminOpLog(type = "CONFIG", target = "DICT", description = "重载词典分析器")
    public R<Void> reload() {
        dictAdminService.reload();
        return R.ok();
    }

    @ApiOperation("立即同步歌手名到主词典")
    @PostMapping("/sync-artists")
    @PreAuthorize("hasAuthority('admin:dict:edit')")
    @AdminOpLog(type = "CONFIG", target = "DICT", description = "同步歌手词条")
    public R<DictSyncResultVO> syncArtists() {
        return R.ok(dictAdminService.syncArtists());
    }

    @ApiOperation(value = "重建 ES 索引", notes = "新索引全量写入后原子切换别名；ES 不可用返回 71008")
    @PostMapping("/reindex")
    @PreAuthorize("hasAuthority('admin:dict:reindex')")
    @AdminOpLog(type = "CONFIG", target = "DICT", description = "重建 ES 索引", sensitive = true)
    public R<DictSyncResultVO> reindex() {
        return R.ok(dictAdminService.reindex());
    }

    private static ResponseEntity<byte[]> text(String content, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/plain;charset=UTF-8"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        return ResponseEntity.ok().headers(headers)
                .body(content.getBytes(StandardCharsets.UTF_8));
    }
}
