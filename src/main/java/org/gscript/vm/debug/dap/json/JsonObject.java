package org.gscript.vm.debug.dap.json;

import java.util.LinkedHashMap;
import java.util.Iterator;

/**
 * JSON 对象节点（替代 Gson 的 JsonObject）。
 *
 * <p>内部用 LinkedHashMap 保持插入顺序（日志可读）。Java 1.4 兼容：raw type，
 * 取值处由调用方 cast。
 *
 * <p>API 对齐 Gson JsonObject：addProperty / add / get / has / getAsJsonObject /
 * getAsJsonArray / getAsInt / getAsString / getAsBoolean / isJsonObject。
 */
public class JsonObject extends JsonValue {

    private final LinkedHashMap members = new LinkedHashMap();

    public void addProperty(String key, String value) {
        members.put(key, new JsonPrimitive(value));
    }

    public void addProperty(String key, int value) {
        members.put(key, new JsonPrimitive(value));
    }

    public void addProperty(String key, long value) {
        members.put(key, new JsonPrimitive(value));
    }

    public void addProperty(String key, boolean value) {
        members.put(key, new JsonPrimitive(value));
    }

    public void add(String key, JsonValue value) {
        members.put(key, value);
    }

    public JsonValue get(String key) {
        return (JsonValue) members.get(key);
    }

    public boolean has(String key) {
        return members.containsKey(key);
    }

    public JsonObject getAsJsonObject(String key) {
        return (JsonObject) members.get(key);
    }

    public JsonArray getAsJsonArray(String key) {
        return (JsonArray) members.get(key);
    }

    public int getAsInt(String key) {
        JsonValue v = (JsonValue) members.get(key);
        if (v == null) {
            throw new UnsupportedOperationException("Key not found: " + key);
        }
        return v.getAsInt();
    }

    public String getAsString(String key) {
        JsonValue v = (JsonValue) members.get(key);
        if (v == null) {
            throw new UnsupportedOperationException("Key not found: " + key);
        }
        return v.getAsString();
    }

    public boolean getAsBoolean(String key) {
        JsonValue v = (JsonValue) members.get(key);
        if (v == null) {
            throw new UnsupportedOperationException("Key not found: " + key);
        }
        return v.getAsBoolean();
    }

    public JsonObject getAsJsonObject() {
        return this;
    }

    public boolean isJsonObject() {
        return true;
    }

    /** 返回所有 key 的迭代器（供序列化使用）。 */
    Iterator keys() {
        return members.keySet().iterator();
    }
}
