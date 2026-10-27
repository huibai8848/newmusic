package com.example.newmusic.module.socialupgrade.service;

import com.example.newmusic.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户图片上传单测（模块 16 / 25）。
 *
 * <p>关键回归：浏览器 `FormData` 上传的 filename 可能没有扩展名（前端曾固定传 `file`）。
 * 类型必须以文件头为准，否则正常图片会被当成「仅支持 jpg/png/webp」拒掉。
 */
@DisplayName("MediaStorageService 图片类型识别")
class MediaStorageServiceTest {

    /** 1x1 透明 PNG，ImageIO 可实际解码 */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=");
    /** RIFF....WEBP 头；JDK 8 没有 WebP 解码器，只校验文件头 */
    private static final byte[] WEBP = {
            'R', 'I', 'F', 'F', 0x1A, 0, 0, 0, 'W', 'E', 'B', 'P',
            'V', 'P', '8', ' ', 0x0A, 0, 0, 0
    };
    private static final byte[] TEXT = "not an image".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private MediaStorageService service;

    @BeforeEach
    void setUp() {
        service = new MediaStorageService(tempDir.toString());
    }

    @Test
    @DisplayName("无扩展名 filename + PNG 内容 → 按内容保存为 .png")
    void acceptsPngWithoutFilenameExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "file", "application/octet-stream", PNG);

        String url = service.saveImage(file, "avatar");

        assertTrue(url.endsWith(".png"), url);
        assertTrue(Files.exists(tempDir.resolve(url.substring("/media/".length()))));
    }

    @Test
    @DisplayName("WebP 只凭文件头接受，扩展名由服务端决定")
    void acceptsWebpByHeader() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "blob", "image/webp", WEBP);

        String url = service.saveImage(file, "cover");

        assertTrue(url.endsWith(".webp"), url);
    }

    @Test
    @DisplayName("内容不是图片 → 10001，不能靠伪造扩展名绕过")
    void rejectsNonImageContent() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.png", "image/png", TEXT);

        BizException e = assertThrows(BizException.class,
                () -> service.saveImage(file, "post"));

        assertEquals(10001, e.getCode());
        assertTrue(e.getMessage().contains("jpg/png/webp"), e.getMessage());
    }

    @Test
    @DisplayName("超过 5MB → 10001")
    void rejectsOversizedImage() {
        byte[] oversized = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(PNG, 0, oversized, 0, PNG.length);
        MockMultipartFile file = new MockMultipartFile(
                "file", "big.png", "image/png", oversized);

        BizException e = assertThrows(BizException.class,
                () -> service.saveImage(file, "post"));

        assertEquals(10001, e.getCode());
        assertTrue(e.getMessage().contains("5MB"), e.getMessage());
    }
}
