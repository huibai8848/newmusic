package com.example.newmusic.common.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 全局 JSON 配置（系分决策 D-05 / 《05-通用设计规范》第 8 节）。
 *
 * <p>长整型序列化策略：只有当数值超出 JS 安全整数范围（2^53-1）时才序列化为字符串。
 * 雪花 ID（19 位）→ 字符串（防精度丢失，AC-10-104）；
 * 计数、分页 total 等小数值 → 保持数字，前端可直接运算。
 */
@Configuration
public class JacksonConfig {

    /** JS Number.MAX_SAFE_INTEGER */
    private static final long JS_MAX_SAFE_INTEGER = 9007199254740991L;

    public static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> {
            JavaTimeModule module = new JavaTimeModule();
            module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME_FMT));
            module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DATE_TIME_FMT));
            module.addSerializer(LocalDate.class, new LocalDateSerializer(DATE_FMT));
            module.addDeserializer(LocalDate.class, new LocalDateDeserializer(DATE_FMT));

            builder.modules(module);
            builder.serializerByType(Long.class, new SafeLongSerializer());
            builder.serializerByType(Long.TYPE, new SafeLongSerializer());
        };
    }

    /** 超出 JS 安全整数范围的 Long 才转字符串。 */
    public static class SafeLongSerializer extends JsonSerializer<Long> {
        @Override
        public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            if (value == null) {
                gen.writeNull();
            } else if (value > JS_MAX_SAFE_INTEGER || value < -JS_MAX_SAFE_INTEGER) {
                gen.writeString(String.valueOf(value));
            } else {
                gen.writeNumber(value);
            }
        }
    }
}
