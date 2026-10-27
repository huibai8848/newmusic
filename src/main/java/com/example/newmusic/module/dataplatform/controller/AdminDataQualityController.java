package com.example.newmusic.module.dataplatform.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.dataplatform.entity.DataLineage;
import com.example.newmusic.module.dataplatform.mapper.DataLineageMapper;
import com.example.newmusic.module.dataplatform.service.DataQualityService;
import com.example.newmusic.module.dataplatform.service.ReconciliationService;
import com.example.newmusic.module.dataplatform.service.WarehouseEtlService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 数据质量、对账、血缘与数仓加工（FR-26-010 / FR-26-011）。 */
@Api(tags = "后台接口-数据治理")
@RestController
@RequestMapping("/api/v1/admin")
public class AdminDataQualityController {

    private final DataQualityService qualityService;
    private final ReconciliationService reconciliationService;
    private final WarehouseEtlService etlService;
    private final DataLineageMapper lineageMapper;

    public AdminDataQualityController(DataQualityService qualityService,
                                      ReconciliationService reconciliationService,
                                      WarehouseEtlService etlService,
                                      DataLineageMapper lineageMapper) {
        this.qualityService = qualityService;
        this.reconciliationService = reconciliationService;
        this.etlService = etlService;
        this.lineageMapper = lineageMapper;
    }

    // ------------------------------------------------------------------ 数据质量

    @ApiOperation("数据质量报告")
    @GetMapping("/data-quality")
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<Map<String, Object>> quality(@RequestParam(required = false) String date) {
        return R.ok(qualityService.report(date));
    }

    @ApiOperation("执行数据质量校验（幂等）")
    @PostMapping("/data-quality/run")
    @PreAuthorize("hasAuthority('admin:data:edit')")
    @AdminOpLog(type = "QUALITY_RUN", target = "DATA_QUALITY", description = "执行数据质量校验")
    public R<Map<String, Object>> runQuality(@RequestParam(required = false) String date) {
        return R.ok(qualityService.run(date));
    }

    // ------------------------------------------------------------------ 对账

    @ApiOperation("对账结果")
    @GetMapping("/reconciliation")
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<List<Map<String, Object>>> reconciliation(@RequestParam(required = false) String date) {
        return R.ok(reconciliationService.list(date));
    }

    @ApiOperation("执行对账（幂等）")
    @PostMapping("/reconciliation/run")
    @PreAuthorize("hasAuthority('admin:data:edit')")
    @AdminOpLog(type = "RECONCILIATION_RUN", target = "RECONCILIATION", description = "执行数据对账")
    public R<Map<String, Object>> runReconciliation(@RequestParam(required = false) String date) {
        return R.ok(reconciliationService.run(date));
    }

    // ------------------------------------------------------------------ 血缘

    @ApiOperation("数据血缘（表级 / 字段级）")
    @GetMapping("/lineage")
    @PreAuthorize("hasAuthority('admin:data:view')")
    public R<List<DataLineage>> lineage(@RequestParam(required = false) String table) {
        String filter = table == null || table.trim().isEmpty() ? null : table.trim();
        return R.ok(lineageMapper.selectList(Wrappers.<DataLineage>lambdaQuery()
                .eq(filter != null, DataLineage::getTableName, filter)
                .orderByAsc(DataLineage::getTableName, DataLineage::getColumnName)));
    }

    // ------------------------------------------------------------------ 数仓

    @ApiOperation("加工指定日期数仓（ODS→DWD→DWS→ADS，幂等）")
    @PostMapping("/data-platform/etl")
    @PreAuthorize("hasAuthority('admin:data:edit')")
    @AdminOpLog(type = "ETL_RUN", target = "DATA_WAREHOUSE", description = "加工数仓")
    public R<Map<String, Object>> etl(@RequestParam(required = false) String date) {
        return R.ok(etlService.run(date));
    }

    @ApiOperation("回溯重算区间（最多 90 天）")
    @PostMapping("/data-platform/etl/backfill")
    @PreAuthorize("hasAuthority('admin:data:edit')")
    @AdminOpLog(type = "ETL_BACKFILL", target = "DATA_WAREHOUSE", description = "回溯重算数仓")
    public R<Map<String, Object>> backfill(@RequestParam String start,
                                           @RequestParam(required = false) String end) {
        return R.ok(etlService.runRange(start, end));
    }
}
