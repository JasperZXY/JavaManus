package io.github.jasperzxy.javamanus.volcengine;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonUtils() {
    }

    public static String toJson(Object obj) {
        if (obj == null) {
            return "null";
        }
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }
}
