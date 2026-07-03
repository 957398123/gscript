package org.gscript.vm.debug.dap.json;

import java.util.Iterator;

/**
 * JSON 序列化器（替代 Gson 的 gson.toJson）。
 *
 * <p>紧凑输出（无缩进），对齐 Gson 默认行为。Java 1.4 兼容：用 StringBuffer。
 */
public class JsonWriter {

    /**
     * 将 JsonValue 序列化为 JSON 字符串。
     */
    public static String toJson(JsonValue value) {
        StringBuffer sb = new StringBuffer();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuffer sb, JsonValue value) {
        if (value == null || value.isJsonNull()) {
            sb.append("null");
        } else if (value.isJsonObject()) {
            writeObject(sb, (JsonObject) value);
        } else if (value.isJsonArray()) {
            writeArray(sb, (JsonArray) value);
        } else {
            writePrimitive(sb, (JsonPrimitive) value);
        }
    }

    private static void writeObject(StringBuffer sb, JsonObject obj) {
        sb.append('{');
        Iterator keys = obj.keys();
        boolean first = true;
        while (keys.hasNext()) {
            String key = (String) keys.next();
            JsonValue val = obj.get(key);
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, key);
            sb.append(':');
            writeValue(sb, val);
        }
        sb.append('}');
    }

    private static void writeArray(StringBuffer sb, JsonArray arr) {
        sb.append('[');
        for (int i = 0; i < arr.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            writeValue(sb, arr.get(i));
        }
        sb.append(']');
    }

    private static void writePrimitive(StringBuffer sb, JsonPrimitive p) {
        if (p.isBoolean()) {
            sb.append(p.getAsBoolean() ? "true" : "false");
        } else if (p.isNumber()) {
            Object raw = p.rawValue();
            if (raw instanceof Long) {
                sb.append(((Long) raw).longValue());
            } else if (raw instanceof Integer) {
                sb.append(((Integer) raw).intValue());
            } else if (raw instanceof Double) {
                double d = ((Double) raw).doubleValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    // 整数值的 double 用 Long 输出避免 Gson 风格的 "10.0"
                    sb.append((long) d);
                } else {
                    sb.append(d);
                }
            } else {
                sb.append(raw.toString());
            }
        } else {
            // 字符串
            writeString(sb, p.getAsString());
        }
    }

    private static void writeString(StringBuffer sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                sb.append("\\\"");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c == '\b') {
                sb.append("\\b");
            } else if (c == '\f') {
                sb.append("\\f");
            } else if (c < 0x20) {
                // 控制字符用 backslash-u-XXXX
                String hex = Integer.toHexString(c);
                while (hex.length() < 4) {
                    hex = "0" + hex;
                }
                sb.append('\\').append('u').append(hex);
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
    }
}
