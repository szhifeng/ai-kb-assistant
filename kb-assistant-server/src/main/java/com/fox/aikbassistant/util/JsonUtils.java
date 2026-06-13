package com.fox.aikbassistant.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

public final class JsonUtils {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .build();

    private JsonUtils() {
    }

    public static ObjectMapper objectMapper() {
        return OBJECT_MAPPER;
    }

    public static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("JSON 序列化失败", ex);
        }
    }

    public static <T> T fromJson(String json, TypeReference<T> typeReference) {
        try {
            return OBJECT_MAPPER.readValue(json, typeReference);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("JSON 反序列化失败", ex);
        }
    }
}
