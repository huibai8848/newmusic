package com.example.newmusic.module.admin.controller;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.R;
import com.example.newmusic.common.result.ResultCode;
import com.example.newmusic.module.admin.vo.UploadVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * 后台素材上传（音频 / 封面）。
 *
 * <p><b>环境偏差</b>：本机没有 ffmpeg，因此只做「上传 + 校验 + 落盘」，
 * **不做转码**。PRD BR-18-057 的异步转码需要在有 ffmpeg 的环境中补一个转码 worker；
 * 在此之前，歌曲不会因为「转码中」而阻塞上架——上传完成即得到可播放的原始文件。
 *
 * <p>安全：文件名由服务端生成（UUID），不信任客户端文件名；扩展名白名单 +
 * 文件头校验 + 体积上限。上传目录固定在 {@code newmusic.media.dir/uploads} 下，
 * 路径穿越没有入口。
 */
@Slf4j
@Api(tags = "后台接口-素材上传")
@RestController
@RequestMapping("/api/v1/admin/files")
public class AdminFileController {

    private static final long MAX_AUDIO = 50L * 1024 * 1024;
    private static final long MAX_IMAGE = 5L * 1024 * 1024;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final Path mediaRoot;

    public AdminFileController(@Value("${newmusic.media.dir:./media}") String mediaDir) {
        this.mediaRoot = Paths.get(mediaDir).toAbsolutePath().normalize();
    }

    @ApiOperation(value = "上传音频", notes = "mp3/wav/flac，≤50MB；不做转码，返回原始文件 URL")
    @PostMapping("/audio")
    @PreAuthorize("hasAnyAuthority('admin:song:create','admin:song:edit')")
    public R<UploadVO> uploadAudio(@RequestParam("file") MultipartFile file) {
        String ext = extensionOf(file.getOriginalFilename());
        if (!Arrays.asList("mp3", "wav", "flac").contains(ext)) {
            throw new BizException(ResultCode.AUDIO_FORMAT_UNSUPPORTED);
        }
        if (file.getSize() <= 0 || file.getSize() > MAX_AUDIO) {
            throw new BizException(ResultCode.AUDIO_TOO_LARGE);
        }
        try (InputStream in = file.getInputStream()) {
            if (!looksLikeAudio(in, ext)) {
                throw new BizException(ResultCode.AUDIO_FORMAT_UNSUPPORTED, "文件内容与扩展名不符");
            }
        } catch (IOException e) {
            throw new BizException(ResultCode.SYSTEM_BUSY, "文件读取失败");
        }
        return R.ok(save(file, ext));
    }

    @ApiOperation(value = "上传图片", notes = "jpg/png/webp，≤5MB；按文件头识别类型并解码校验")
    @PostMapping("/image")
    @PreAuthorize("hasAnyAuthority('admin:song:create','admin:song:edit','admin:artist:manage','admin:album:manage')")
    public R<UploadVO> uploadImage(@RequestParam("file") MultipartFile file) {
        if (file.getSize() <= 0 || file.getSize() > MAX_IMAGE) {
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
        // JDK 8 的 ImageIO 没有 WebP 解码器，webp 靠文件头校验；jpg/png 再实际解码一次。
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
        return R.ok(save(file, ext));
    }

    private UploadVO save(MultipartFile file, String ext) {
        String day = LocalDate.now().format(DAY);
        Path dir = mediaRoot.resolve("uploads").resolve(day).normalize();
        if (!dir.startsWith(mediaRoot)) {
            throw new BizException(ResultCode.PARAM_INVALID);
        }
        String name = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = dir.resolve(name);
        try {
            Files.createDirectories(dir);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("素材落盘失败 dir={} name={}", dir, name, e);
            throw new BizException(ResultCode.SYSTEM_BUSY, "文件保存失败");
        }
        UploadVO vo = new UploadVO();
        vo.setUrl("/media/uploads/" + day + "/" + name);
        vo.setSize(file.getSize());
        vo.setOriginalName(file.getOriginalFilename());
        return vo;
    }

    private String extensionOf(String filename) {
        if (!StringUtils.hasText(filename) || filename.lastIndexOf('.') < 0) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 按文件头识别图片类型，返回落盘扩展名（jpg/png/webp），无法识别返回 {@code null}。
     * 不信任客户端扩展名：表单上传的 filename 可能没有后缀。
     */
    private String sniffImageExt(InputStream in) throws IOException {
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

    /** 只做最小文件头校验，挡掉「改扩展名上传任意文件」；真正的解码仍由播放器负责。 */
    private boolean looksLikeAudio(InputStream in, String ext) throws IOException {
        byte[] head = new byte[12];
        int read = in.read(head);
        if (read < 4) {
            return false;
        }
        if ("mp3".equals(ext)) {
            return (head[0] == 'I' && head[1] == 'D' && head[2] == '3')
                    || ((head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0);
        }
        if ("wav".equals(ext)) {
            return head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F';
        }
        return head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C';
    }
}
