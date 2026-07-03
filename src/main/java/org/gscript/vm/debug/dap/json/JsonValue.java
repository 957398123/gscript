package org.gscript.vm.debug.dap.json;

/**
 * JSON 值的抽象基类（自研 JSON 库，替代 Gson 的 JsonElement）。
 *
 * <p>Java 1.4 兼容：无泛型、无注解、无枚举。各子类按需覆盖 getAsXxx 方法，
 * 不支持的类型默认抛 UnsupportedOperationException。
 */
public abstract class JsonValue {

    /** 返回字符串表示。仅 JsonPrimitive(字符串) 有效。 */
    public String getAsString() {
        throw new UnsupportedOperationException("Not a string");
    }

    /** 返回 int 值。仅 JsonPrimitive(数字) 有效。 */
    public int getAsInt() {
        throw new UnsupportedOperationException("Not a number");
    }

    /** 返回 boolean 值。仅 JsonPrimitive(布尔) 有效。 */
    public boolean getAsBoolean() {
        throw new UnsupportedOperationException("Not a boolean");
    }

    /** 转为 JsonObject。仅 JsonObject 自身有效。 */
    public JsonObject getAsJsonObject() {
        throw new UnsupportedOperationException("Not a JsonObject");
    }

    /** 转为 JsonArray。仅 JsonArray 自身有效。 */
    public JsonArray getAsJsonArray() {
        throw new UnsupportedOperationException("Not a JsonArray");
    }

    public boolean isJsonObject() {
        return false;
    }

    public boolean isJsonArray() {
        return false;
    }

    public boolean isJsonNull() {
        return false;
    }
}
