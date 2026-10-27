package com.example.newmusic.module.contentsafety.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
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
import com.example.newmusic.module.contentsafety.service.SensitiveWordService;
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

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 敏感词库、白名单与审核规则（FR-27-002）。
 *
 * <p>词库属于高敏感数据：所有响应加 {@code Cache-Control: no-store}，
 * L3 词条在非超管视图里只返回掩码；导出 L3 需超管 + 显式 confirm。
 */
@Api(tags = "后台接口-敏感词库")
@RestController
@RequestMapping("/api/v1/admin")
public class SensitiveWordAdminController {

    private final SensitiveWordService wordService;

    public SensitiveWordAdminController(SensitiveWordService wordService) {
        this.wordService = wordService;
    }

    // ------------------------------------------------------------------ words

    @ApiOperation("敏感词列表")
    @GetMapping("/sensitive-words")
    @PreAuthorize("hasAuthority('admin:audit:word:view')")
    public R<PageResult<SensitiveWord>> page(WordQueryReq query, HttpServletResponse response) {
        noStore(response);
        return R.ok(wordService.page(query));
    }

    @ApiOperation("新增敏感词")
    @PostMapping("/sensitive-words")
    @PreAuthorize("hasAuthority('admin:audit:word:edit')")
    @AdminOpLog(type = "AUDIT_WORD_CREATE", target = "SENSITIVE_WORD", description = "新增敏感词")
    public R<SensitiveWord> create(@RequestBody @Valid SensitiveWordSaveReq req) {
        return R.ok(wordService.create(req));
    }

    @ApiOperation("编辑敏感词")
    @PutMapping("/sensitive-words/{id}")
    @PreAuthorize("hasAuthority('admin:audit:word:edit')")
    @AdminOpLog(type = "AUDIT_WORD_UPDATE", target = "SENSITIVE_WORD", description = "编辑敏感词")
    public R<SensitiveWord> update(@PathVariable Long id,
                                   @RequestBody @Valid SensitiveWordSaveReq req) {
        return R.ok(wordService.update(id, req));
    }

    @ApiOperation("删除敏感词")
    @DeleteMapping("/sensitive-words/{id}")
    @PreAuthorize("hasAuthority('admin:audit:word:edit')")
    @AdminOpLog(type = "AUDIT_WORD_DELETE", target = "SENSITIVE_WORD", description = "删除敏感词")
    public R<Void> delete(@PathVariable Long id) {
        wordService.delete(id);
        return R.ok();
    }

    @ApiOperation("导入预览")
    @PostMapping("/sensitive-words/import/preview")
    @PreAuthorize("hasAuthority('admin:audit:word:edit')")
    public R<Map<String, Object>> previewImport(@RequestBody @Valid WordImportReq req) {
        return R.ok(wordService.previewImport(req));
    }

    @ApiOperation(value = "批量导入", notes = "单次最多 10000 条；L3 仅超管")
    @PostMapping("/sensitive-words/import")
    @PreAuthorize("hasAuthority('admin:audit:word:edit')")
    @AdminOpLog(type = "AUDIT_WORD_IMPORT", target = "SENSITIVE_WORD", description = "批量导入敏感词")
    public R<Map<String, Object>> importWords(@RequestBody @Valid WordImportReq req) {
        return R.ok(wordService.importWords(req));
    }

    @ApiOperation(value = "导出词库", notes = "L3 需超管并在 confirm 参数输入 EXPORT")
    @GetMapping("/sensitive-words/export")
    @PreAuthorize("hasAuthority('admin:audit:word:export')")
    @AdminOpLog(type = "AUDIT_WORD_EXPORT", target = "SENSITIVE_WORD", description = "导出词库")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) Integer level,
                                         @RequestParam(required = false) String confirm,
                                         HttpServletResponse response) {
        noStore(response);
        // Service 已在首行写入 UTF-8 BOM，这里不能再加一次，否则表头字段变成 BOM+BOM+word。
        String csv = wordService.export(level, confirm);
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"sensitive-words-" + UserContext.getUserId() + ".csv\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    @ApiOperation("词库测试")
    @PostMapping("/sensitive-words/test")
    @PreAuthorize("hasAuthority('admin:audit:word:view')")
    public R<Map<String, Object>> test(@RequestBody @Valid WordTestReq req, HttpServletResponse response) {
        noStore(response);
        return R.ok(wordService.test(req));
    }

    @ApiOperation("一键热加载")
    @PostMapping("/sensitive-words/reload")
    @PreAuthorize("hasAuthority('admin:audit:word:edit')")
    @AdminOpLog(type = "AUDIT_WORD_RELOAD", target = "SENSITIVE_WORD", description = "热加载词库")
    public R<Map<String, Object>> reload() {
        return R.ok(wordService.reload());
    }

    @ApiOperation("词库版本列表")
    @GetMapping("/sensitive-words/versions")
    @PreAuthorize("hasAuthority('admin:audit:word:view')")
    public R<List<SensitiveWordVersion>> versions(HttpServletResponse response) {
        noStore(response);
        return R.ok(wordService.versions());
    }

    @ApiOperation("回滚到指定版本")
    @PostMapping("/sensitive-words/versions/{version}/rollback")
    @PreAuthorize("hasAuthority('admin:audit:word:edit')")
    @AdminOpLog(type = "AUDIT_WORD_ROLLBACK", target = "SENSITIVE_WORD", description = "词库回滚")
    public R<Map<String, Object>> rollback(@PathVariable Integer version) {
        return R.ok(wordService.rollback(version, UserContext.requireUserId()));
    }

    @ApiOperation("命中统计")
    @GetMapping("/sensitive-words/stats")
    @PreAuthorize("hasAuthority('admin:audit:word:view')")
    public R<Map<String, Object>> stats() {
        return R.ok(wordService.stats());
    }

    // ------------------------------------------------------------------ white words

    @ApiOperation("白名单列表")
    @GetMapping("/white-words")
    @PreAuthorize("hasAuthority('admin:audit:word:view')")
    public R<List<WhiteWord>> whites() {
        return R.ok(wordService.whites());
    }

    @ApiOperation("新增白名单")
    @PostMapping("/white-words")
    @PreAuthorize("hasAuthority('admin:audit:word:edit')")
    @AdminOpLog(type = "AUDIT_WHITE_CREATE", target = "WHITE_WORD", description = "新增白名单")
    public R<WhiteWord> createWhite(@RequestBody @Valid WhiteWordSaveReq req) {
        return R.ok(wordService.createWhite(req));
    }

    @ApiOperation("删除白名单")
    @DeleteMapping("/white-words/{id}")
    @PreAuthorize("hasAuthority('admin:audit:word:edit')")
    @AdminOpLog(type = "AUDIT_WHITE_DELETE", target = "WHITE_WORD", description = "删除白名单")
    public R<Void> deleteWhite(@PathVariable Long id) {
        wordService.deleteWhite(id);
        return R.ok();
    }

    // ------------------------------------------------------------------ rules

    @ApiOperation("审核规则列表")
    @GetMapping("/audit-rules")
    @PreAuthorize("hasAuthority('admin:audit:word:view')")
    public R<List<AuditRule>> rules() {
        return R.ok(wordService.rules());
    }

    @ApiOperation("新增审核规则")
    @PostMapping("/audit-rules")
    @PreAuthorize("hasAuthority('admin:audit:rule:edit')")
    @AdminOpLog(type = "AUDIT_RULE_CREATE", target = "AUDIT_RULE", description = "新增审核规则")
    public R<AuditRule> createRule(@RequestBody @Valid AuditRuleSaveReq req) {
        return R.ok(wordService.createRule(req));
    }

    @ApiOperation("编辑审核规则")
    @PutMapping("/audit-rules/{id}")
    @PreAuthorize("hasAuthority('admin:audit:rule:edit')")
    @AdminOpLog(type = "AUDIT_RULE_UPDATE", target = "AUDIT_RULE", description = "编辑审核规则")
    public R<AuditRule> updateRule(@PathVariable Long id,
                                   @RequestBody @Valid AuditRuleSaveReq req) {
        return R.ok(wordService.updateRule(id, req));
    }

    @ApiOperation("删除审核规则")
    @DeleteMapping("/audit-rules/{id}")
    @PreAuthorize("hasAuthority('admin:audit:rule:edit')")
    @AdminOpLog(type = "AUDIT_RULE_DELETE", target = "AUDIT_RULE", description = "删除审核规则")
    public R<Void> deleteRule(@PathVariable Long id) {
        wordService.deleteRule(id);
        return R.ok();
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
    }
}
