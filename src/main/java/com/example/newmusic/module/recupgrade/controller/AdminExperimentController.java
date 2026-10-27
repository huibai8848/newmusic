package com.example.newmusic.module.recupgrade.controller;

import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.query.PageQuery;
import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.common.result.R;
import com.example.newmusic.module.admin.annotation.AdminOpLog;
import com.example.newmusic.module.recupgrade.entity.RecExperiment;
import com.example.newmusic.module.recupgrade.experiment.ExperimentService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 后台-A/B 实验（FR-24-008 / FR-24-009）。
 *
 * <p>审批权限单独拆出 {@code admin:exp:approve}：能建实验的人不应同时能批自己的实验。
 */
@Api(tags = "后台接口-推荐实验")
@RestController
@RequestMapping("/api/v1/admin/rec/experiments")
public class AdminExperimentController {

    private final ExperimentService experimentService;

    public AdminExperimentController(ExperimentService experimentService) {
        this.experimentService = experimentService;
    }

    @ApiOperation("实验列表")
    @GetMapping
    @PreAuthorize("hasAuthority('admin:exp:view')")
    public R<PageResult<RecExperiment>> list(@RequestParam(required = false) Integer status,
                                             PageQuery query) {
        return R.ok(experimentService.list(status, query));
    }

    @ApiOperation("实验详情（含分组、白名单、结论）")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('admin:exp:view')")
    public R<RecExperiment> detail(@PathVariable Long id) {
        return R.ok(experimentService.get(id));
    }

    @ApiOperation(value = "创建实验",
            notes = "同层流量合计 ≤100%，同层运行中实验 ≤5；组比例合计必须 100%。")
    @PostMapping
    @PreAuthorize("hasAuthority('admin:exp:edit')")
    @AdminOpLog(type = "EXP_CREATE", target = "REC_EXPERIMENT", description = "创建推荐实验")
    public R<RecExperiment> create(@RequestBody RecExperiment experiment) {
        experiment.setCreatorId(UserContext.getUserId());
        return R.ok(experimentService.create(experiment));
    }

    @ApiOperation(value = "审批并启动", notes = "审批后立即按新流量分流。")
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('admin:exp:approve')")
    @AdminOpLog(type = "EXP_APPROVE", target = "REC_EXPERIMENT", description = "审批推荐实验", sensitive = true)
    public R<RecExperiment> approve(@PathVariable Long id) {
        return R.ok(experimentService.approve(id, UserContext.getUserId()));
    }

    @ApiOperation(value = "一键停止", notes = "清空全部分组缓存，下一次请求立即回默认组（EX-24-008-5）。")
    @PostMapping("/{id}/stop")
    @PreAuthorize("hasAuthority('admin:exp:edit')")
    @AdminOpLog(type = "EXP_STOP", target = "REC_EXPERIMENT", description = "停止推荐实验", sensitive = true)
    public R<RecExperiment> stop(@PathVariable Long id) {
        return R.ok(experimentService.stop(id));
    }

    @ApiOperation("归档并保存结论")
    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('admin:exp:edit')")
    @AdminOpLog(type = "EXP_ARCHIVE", target = "REC_EXPERIMENT", description = "归档推荐实验")
    public R<RecExperiment> archive(@PathVariable Long id,
                                    @RequestBody(required = false) Map<String, String> body) {
        String conclusion = body == null ? null : body.get("conclusion");
        return R.ok(experimentService.archive(id, conclusion));
    }

    @ApiOperation(value = "更新白名单", notes = "JSON：{\"userId\":\"group\"}；白名单以数据库为准。")
    @PutMapping("/{id}/whitelist")
    @PreAuthorize("hasAuthority('admin:exp:edit')")
    @AdminOpLog(type = "EXP_WHITELIST", target = "REC_EXPERIMENT", description = "更新实验白名单")
    public R<RecExperiment> whitelist(@PathVariable Long id,
                                      @RequestBody(required = false) Map<String, String> body) {
        String json = body == null ? null : body.get("whitelistJson");
        return R.ok(experimentService.updateWhitelist(id, json));
    }

    @ApiOperation(value = "实验指标",
            notes = "按实验组聚合 CTR/播放/完播/有效播放/赞踩；支持用户类型、设备、小时下钻。")
    @GetMapping("/{experimentKey}/metrics")
    @PreAuthorize("hasAuthority('admin:exp:view')")
    public R<Map<String, Object>> metrics(
            @PathVariable String experimentKey,
            @ApiParam("统计天数，默认 7，最大 90") @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String userType,
            @RequestParam(required = false) String deviceType,
            @ApiParam("小时 0~23，-1 表示不过滤") @RequestParam(defaultValue = "-1") int hour) {
        return R.ok(experimentService.metrics(experimentKey, days, userType, deviceType, hour));
    }

    @ApiOperation(value = "显著性检验",
            notes = "比例指标 z 检验，avgValidPlay 用连续型近似；样本不足返回 INSUFFICIENT_SAMPLE，"
                    + "多组比较使用 Bonferroni 校正。")
    @GetMapping("/{experimentKey}/significance")
    @PreAuthorize("hasAuthority('admin:exp:view')")
    public R<Map<String, Object>> significance(
            @PathVariable String experimentKey,
            @RequestParam(required = false) String metric,
            @RequestParam(defaultValue = "7") int days) {
        return R.ok(experimentService.significance(experimentKey, metric, days));
    }

    @ApiOperation(value = "AA 检验", notes = "对照组用户按 hash 拆半，p≥0.05 判定通过。")
    @GetMapping("/{experimentKey}/aa")
    @PreAuthorize("hasAuthority('admin:exp:view')")
    public R<Map<String, Object>> aa(@PathVariable String experimentKey,
                                     @RequestParam(defaultValue = "7") int days) {
        return R.ok(experimentService.aaTest(experimentKey, days));
    }
}
