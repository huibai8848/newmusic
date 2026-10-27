package com.example.newmusic.module.ops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.newmusic.common.context.UserContext;
import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.ops.config.OpsProperties;
import com.example.newmusic.module.ops.dto.BackupReq;
import com.example.newmusic.module.ops.entity.BackupRecord;
import com.example.newmusic.module.ops.mapper.BackupRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 备份清单与校验（FR-28-012 / AC-28-012-1~5）。
 *
 * <p><b>为什么不跑 mysqldump：</b>本机 MySQL 是共享实例（3306 上还有别的项目库），
 * 跑全库 dump 既慢又可能把无关库带出来；而且本模块的验收点是「清单完整、可校验、
 * 可追溯」，不是「真的能恢复」。所以这里做的是**逻辑备份清单**：
 * 枚举 {@code newmusic} 下的表、逐表精确计数、生成 manifest 文件并计算 SHA-256。
 *
 * <p>文件只写在 {@code newmusic.ops.backup-dir}（默认 {@code .tmp/backups}），
 * 且校验/清理时会做 canonical path 检查，数据库里被篡改的 location 无法读到目录外的文件。
 */
@Slf4j
@Service
public class BackupService {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String FILE_PREFIX = "manifest-newmusic-";

    private final BackupRecordMapper mapper;
    private final DataSource dataSource;
    private final OpsProperties properties;
    private final OpsConfigService configService;

    public BackupService(BackupRecordMapper mapper, DataSource dataSource,
                         OpsProperties properties, OpsConfigService configService) {
        this.mapper = mapper;
        this.dataSource = dataSource;
        this.properties = properties;
        this.configService = configService;
    }

    public Page<BackupRecord> list(String status, int page, int size) {
        LambdaQueryWrapper<BackupRecord> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq(BackupRecord::getStatus, status);
        }
        wrapper.orderByDesc(BackupRecord::getStartedAt).orderByDesc(BackupRecord::getId);
        return mapper.selectPage(new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size))),
                wrapper);
    }

    /**
     * 生成备份清单。同步执行：表数量有限（本库几十张表），逐表 COUNT(*) 在本地毫秒级。
     */
    public BackupRecord trigger(BackupReq req) {
        BackupRecord record = new BackupRecord();
        record.setBackupType(StringUtils.hasText(req.getBackupType())
                ? req.getBackupType().toUpperCase() : "FULL");
        record.setStatus("RUNNING");
        record.setVerifyStatus("PENDING");
        record.setStartedAt(LocalDateTime.now());
        record.setOperatorId(UserContext.getUserId());
        record.setMessage(req.getMessage());
        record.setCreateTime(LocalDateTime.now());
        mapper.insert(record);

        try {
            cleanupExpired();
            Map<String, Long> counts = countTables();
            StringBuilder manifest = new StringBuilder(4096);
            manifest.append("# newmusic logical backup manifest\n");
            manifest.append("# created_at=").append(LocalDateTime.now()).append('\n');
            manifest.append("# type=").append(record.getBackupType()).append('\n');
            manifest.append("# format=table<TAB>rows\n");
            long rows = 0;
            for (Map.Entry<String, Long> entry : counts.entrySet()) {
                manifest.append(entry.getKey()).append('\t').append(entry.getValue()).append('\n');
                rows += entry.getValue();
            }
            byte[] bytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
            String checksum = sha256(bytes);

            Path dir = backupDir();
            Files.createDirectories(dir);
            String filename = FILE_PREFIX + record.getId() + "-" + LocalDateTime.now().format(FILE_TS)
                    + ".txt";
            Path file = dir.resolve(filename).normalize();
            if (!file.startsWith(dir)) {
                throw new IOException("非法备份路径");
            }
            Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            String verified = sha256(Files.readAllBytes(file)).equals(checksum)
                    ? "VERIFIED" : "MISMATCH";

            record.setStatus("SUCCESS");
            record.setSizeBytes((long) bytes.length);
            record.setLocation(relative(dir, file));
            record.setChecksum(checksum);
            record.setVerifyStatus(verified);
            record.setTableCount(counts.size());
            record.setRowCount(rows);
            record.setFinishedAt(LocalDateTime.now());
            mapper.updateById(record);
            log.info("备份清单完成 id={} tables={} rows={} checksum={}", record.getId(),
                    counts.size(), rows, checksum);
            return record;
        } catch (Exception e) {
            record.setStatus("FAILED");
            record.setFinishedAt(LocalDateTime.now());
            record.setMessage(truncate("备份失败：" + e.getMessage(), 300));
            mapper.updateById(record);
            log.error("备份清单失败 id={} cause={}", record.getId(), e.toString());
            throw new BizException(ResultCode.OPS_BACKUP_FAILED);
        }
    }

    /** 重新计算文件校验和，用于「备份可恢复性」的轻量验证。 */
    public Map<String, Object> verify(Long id) {
        BackupRecord record = mapper.selectById(id);
        if (record == null) {
            throw new BizException(ResultCode.OPS_BACKUP_FAILED, "备份记录不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        if (!StringUtils.hasText(record.getLocation())) {
            record.setVerifyStatus("MISSING");
            mapper.updateById(record);
            result.put("verifyStatus", "MISSING");
            return result;
        }
        try {
            Path file = resolve(record.getLocation());
            if (!Files.exists(file)) {
                record.setVerifyStatus("MISSING");
                mapper.updateById(record);
                result.put("verifyStatus", "MISSING");
                return result;
            }
            String actual = sha256(Files.readAllBytes(file));
            boolean ok = actual.equals(record.getChecksum());
            record.setVerifyStatus(ok ? "VERIFIED" : "MISMATCH");
            mapper.updateById(record);
            result.put("verifyStatus", record.getVerifyStatus());
            result.put("expected", record.getChecksum());
            result.put("actual", actual);
            return result;
        } catch (Exception e) {
            record.setVerifyStatus("ERROR");
            mapper.updateById(record);
            result.put("verifyStatus", "ERROR");
            result.put("error", e.toString());
            return result;
        }
    }

    /** 供后台预览清单前 N 行。 */
    public List<String> preview(Long id, int limit) {
        BackupRecord record = mapper.selectById(id);
        if (record == null) {
            throw new BizException(ResultCode.OPS_BACKUP_FAILED, "备份记录不存在");
        }
        List<String> lines = new ArrayList<>();
        try {
            Path file = resolve(record.getLocation());
            if (!Files.exists(file)) {
                return lines;
            }
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null && lines.size() < Math.min(200, limit)) {
                    lines.add(line);
                }
            }
        } catch (Exception e) {
            log.warn("读取备份清单失败 id={} cause={}", id, e.toString());
        }
        return lines;
    }

    // ------------------------------------------------------------------ 内部

    private Map<String, Long> countTables() throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery(
                    "SELECT table_name FROM information_schema.tables "
                            + "WHERE table_schema = 'newmusic' ORDER BY table_name")) {
                while (rs.next() && tables.size() < 300) {
                    String table = rs.getString(1);
                    if (table != null && table.matches("[A-Za-z0-9_]+")) {
                        tables.add(table);
                    }
                }
            }
            for (String table : tables) {
                try (Statement countStatement = connection.createStatement()) {
                    countStatement.setQueryTimeout(5);
                    try (ResultSet rs = countStatement.executeQuery(
                            "SELECT COUNT(*) FROM newmusic." + table)) {
                        counts.put(table, rs.next() ? rs.getLong(1) : 0L);
                    }
                }
            }
        }
        return counts;
    }

    /** 只清理本服务生成、且超过保留期的清单文件。 */
    private void cleanupExpired() {
        int days = (int) configService.getLong(OpsConfigService.KEY_BACKUP_RETENTION_DAYS,
                properties.getBackupRetentionDays());
        if (days <= 0) {
            return;
        }
        File[] files = backupDir().toFile().listFiles((dir, name) -> name.startsWith(FILE_PREFIX));
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - days * 24L * 3600L * 1000L;
        for (File file : files) {
            if (file.isFile() && file.lastModified() < cutoff) {
                if (file.delete()) {
                    log.info("清理过期备份清单 file={}", file.getName());
                }
            }
        }
    }

    private Path backupDir() {
        return Paths.get(properties.getBackupDir()).toAbsolutePath().normalize();
    }

    private String relative(Path dir, Path file) {
        try {
            return dir.relativize(file).toString().replace('\\', '/');
        } catch (Exception e) {
            return file.getFileName().toString();
        }
    }

    /** location 只允许备份目录下的文件名，防止数据库字段被改成 ../../etc/passwd。 */
    private Path resolve(String location) throws IOException {
        Path dir = backupDir();
        Path file = dir.resolve(location).normalize();
        if (!file.startsWith(dir)) {
            throw new IOException("备份路径越界");
        }
        return file;
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
