package com.example.newmusic.module.admin.service;

import com.example.newmusic.common.result.PageResult;
import com.example.newmusic.module.admin.vo.SongImportResultVO;
import com.example.newmusic.module.music.dto.SongQueryReq;
import com.example.newmusic.module.music.dto.SongSaveReq;
import com.example.newmusic.module.music.entity.Song;
import com.example.newmusic.module.music.service.SongService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 后台内容管理：JSON 批量导入与 CSV 导出。
 *
 * <p>导入逐行独立事务：某一行失败不会回滚已成功的行，最终返回失败行号与原因
 * （PRD EX-18-007-4）。导出同样走 {@link SongService#page} 的白名单查询，
 * 不另写 SQL，避免后台与前台看到两套口径。
 */
@Slf4j
@Service
public class AdminContentService {

    private static final int MAX_EXPORT_ROWS = 10000;
    private static final String CSV_HEADER =
            "ID,歌曲名,歌手,专辑,时长(秒),语种,流派,状态,审核状态,版权状态,播放量,上架时间";

    private final SongService songService;
    private final Validator validator;

    public AdminContentService(SongService songService, Validator validator) {
        this.songService = songService;
        this.validator = validator;
    }

    public SongImportResultVO importSongs(List<SongSaveReq> rows) {
        SongImportResultVO result = new SongImportResultVO();
        for (int i = 0; i < rows.size(); i++) {
            SongSaveReq row = rows.get(i);
            int rowNo = i + 1;
            if (row == null) {
                result.addError(rowNo, "空行");
                continue;
            }
            Set<ConstraintViolation<SongSaveReq>> violations = validator.validate(row);
            if (!violations.isEmpty()) {
                result.addError(rowNo, violations.stream()
                        .map(ConstraintViolation::getMessage)
                        .sorted()
                        .collect(Collectors.joining("；")));
                continue;
            }
            if (!StringUtils.hasText(row.getAudioStandard())) {
                result.addError(rowNo, "必须填写标准音质音频（BR-18-055）");
                continue;
            }
            try {
                songService.create(row);
                result.setSuccess(result.getSuccess() + 1);
            } catch (Exception e) {
                // 每行单独事务，异常只影响本行
                result.addError(rowNo, e.getMessage() == null ? "导入失败" : e.getMessage());
            }
        }
        log.info("歌曲批量导入完成 total={} success={} failed={}",
                rows.size(), result.getSuccess(), result.getFailed());
        return result;
    }

    /** 导出 CSV（UTF-8 BOM，Excel 直接打开不乱码）。最多 10000 行。 */
    public void exportSongs(SongQueryReq query, OutputStream outputStream) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append('﻿').append(CSV_HEADER).append('\n');
        int pageNum = 1;
        int exported = 0;
        while (exported < MAX_EXPORT_ROWS) {
            query.setPageNum(pageNum);
            query.setPageSize(100);
            PageResult<Song> page = songService.page(query);
            List<Song> records = page.getList();
            if (records == null || records.isEmpty()) {
                break;
            }
            for (Song song : records) {
                sb.append(csv(String.valueOf(song.getId()))).append(',')
                        .append(csv(song.getName())).append(',')
                        .append(csv(song.getArtistName())).append(',')
                        .append(csv(song.getAlbumName())).append(',')
                        .append(csv(song.getDuration() == null ? null : String.valueOf(song.getDuration()))).append(',')
                        .append(csv(song.getLanguage())).append(',')
                        .append(csv(song.getGenre())).append(',')
                        .append(csv(statusText(song.getStatus()))).append(',')
                        .append(csv(auditText(song.getAuditStatus()))).append(',')
                        .append(csv(copyrightText(song.getCopyrightStatus()))).append(',')
                        .append(csv(song.getPlayCount() == null ? "0" : String.valueOf(song.getPlayCount()))).append(',')
                        .append(csv(song.getPublishTime() == null ? "" : song.getPublishTime().toString()))
                        .append('\n');
                exported++;
                if (exported >= MAX_EXPORT_ROWS) {
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

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String statusText(Integer status) {
        return status != null && status == 1 ? "上架" : "下架";
    }

    private String auditText(Integer audit) {
        if (audit == null) {
            return "待审";
        }
        if (audit == 1) {
            return "通过";
        }
        return audit == 2 ? "拒绝" : "待审";
    }

    private String copyrightText(Integer copyright) {
        if (copyright == null) {
            return "未授权";
        }
        switch (copyright) {
            case 1:
                return "已授权";
            case 2:
                return "已过期";
            case 3:
                return "受限";
            default:
                return "未授权";
        }
    }
}
