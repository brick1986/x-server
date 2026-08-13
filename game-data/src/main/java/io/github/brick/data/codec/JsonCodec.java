package io.github.brick.data.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * POJO↔JSON 序列化工具。不持有任何业务实体类（primitives spec §6）。
 * 对 POJO 形态（record 或带 setter 的普通类）透明——Jackson 自行处理。
 */
public final class JsonCodec {

    private final ObjectMapper mapper;

    public JsonCodec() {
        this(new ObjectMapper());
    }

    public JsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(Object pojo) {
        try {
            return mapper.writeValueAsString(pojo);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 编码失败: " + pojo, e);
        }
    }

    public <T> T decode(String json, Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 解码失败: " + json, e);
        }
    }
}
