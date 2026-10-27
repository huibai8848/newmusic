package com.example.newmusic.module.socialupgrade.service;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 用户内容图片落盘（头像/封面/动态图）。
 *
 * <p>与后台素材上传使用同一个 {@code newmusic.media.dir}，但**不信任客户端文件名**：
 * 类型以文件头为准，扩展名由服务端决定。浏览器用 `FormData` 上传 `Blob` 时文件名常常
 * 没有扩展名（Axios 的 `fileForm` 曾经固定传 `file`），只看文件名会把正常图片拒掉。
 * 当前环境没有图片压缩工具，接口仍按 PRD 限制原图 ≤5MB；「压缩到 500KB」留给后续
 * 接入对象存储/图片服务时在上传层完成。
 */
@Slf4j
@Service
public class MediaStorageService {

    private static final long MAX_IMAGE = 5L * 1024 * 1024;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final Path mediaRoot;

    public MediaStorageService(@Value("${newmusic.media.dir:./media}") String mediaDir) {
        this.mediaRoot = Paths.get(mediaDir).toAbsolutePath().normalize();
    }

    public String saveImage(MultipartFile file, String category) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID, "请选择图片");
        }
        if (file.getSize() > MAX_IMAGE) {
            throw new BizException(ResultCode.PARAM_INVALID, "图片不能超过 5MB");
        }
        String ext;
        try (InputStream in = file.getInputStream()) {
            ext = sniffImageExt(in);
        } catch (IOException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "图片读取失败");
        }
        if (ext == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "仅支持 jpg/png/webp 图片");
        }
        // JDK 8 自带的 ImageIO 没有 WebP 解码器，webp 只能靠上面的文件头校验；
        // jpg/png 仍实际解码一次，挡掉「改了扩展名的任意文件」。
        if (!"webp".equals(ext)) {
            try (InputStream in = file.getInputStream()) {
                BufferedImage image = ImageIO.read(in);
                if (image == null) {
                    throw new BizException(ResultCode.PARAM_INVALID, "图片内容无法解析");
                }
            } catch (IOException e) {
                throw new BizException(ResultCode.PARAM_INVALID, "图片读取失败");
            }
        }

        String safeCategory = StringUtils.hasText(category)
                ? category.replaceAll("[^a-z0-9_-]", "") : "misc";
        String day = LocalDate.now().format(DAY);
        Path dir = mediaRoot.resolve("uploads").resolve(safeCategory).resolve(day).normalize();
        if (!dir.startsWith(mediaRoot)) {
            throw new BizException(ResultCode.PARAM_INVALID);
        }
        String name = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        try {
            Files.createDirectories(dir);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("用户图片落盘失败 category={} day={}", safeCategory, day, e);
            throw new BizException(ResultCode.SYSTEM_BUSY, "图片保存失败");
        }
        return "/media/uploads/" + safeCategory + "/" + day + "/" + name;
    }

    /**
     * 按文件头识别图片类型，返回落盘扩展名（jpg/png/webp），无法识别返回 {@code null}。
     *
     * <p>不读客户端扩展名：`FormData` 里的 filename 可能没有后缀，或者与内容不符。
     */
    private static String sniffImageExt(InputStream in) throws IOException {
        byte[] head = new byte[12];
        int read = in.read(head);
        if (read < 4) {
            return null;
        }
        if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if ((head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
            return "png";
        }
        if (read >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return "webp";
        }
        return null;
    }
}
