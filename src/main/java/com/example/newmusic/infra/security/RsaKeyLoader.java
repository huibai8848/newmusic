package com.example.newmusic.infra.security;

import com.example.newmusic.common.exception.BizException;
import com.example.newmusic.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 从 PEM 文件加载 RSA 公私钥（PKCS#8 / X.509，openssl 默认输出格式）。
 */
@Slf4j
@Component
public class RsaKeyLoader {

    private final ResourceLoader resourceLoader;

    public RsaKeyLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public PrivateKey loadPrivateKey(String location) {
        try {
            byte[] der = readDer(location, "PRIVATE KEY");
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            log.error("加载 JWT 私钥失败: {}", location, e);
            throw new BizException(ResultCode.SYSTEM_BUSY, "JWT 私钥加载失败");
        }
    }

    public PublicKey loadPublicKey(String location) {
        try {
            byte[] der = readDer(location, "PUBLIC KEY");
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            log.error("加载 JWT 公钥失败: {}", location, e);
            throw new BizException(ResultCode.SYSTEM_BUSY, "JWT 公钥加载失败");
        }
    }

    private byte[] readDer(String location, String type) throws Exception {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("密钥文件不存在: " + location);
        }
        byte[] bytes;
        try (java.io.InputStream in = resource.getInputStream()) {
            bytes = org.springframework.util.StreamUtils.copyToByteArray(in);
        }
        String pem = new String(bytes, StandardCharsets.UTF_8);
        String base64 = pem
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        if (!StringUtils.hasText(base64)) {
            throw new IllegalStateException("密钥文件内容为空: " + location);
        }
        return Base64.getDecoder().decode(base64);
    }
}
