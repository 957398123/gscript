package org.gscript.vm.debug.dap.json;

/**
 * JSON 标量值：字符串/数字/布尔（替代 Gson 的 JsonPrimitive）。
 *
 * <p>Java 1.4 兼容：无自动装箱，构造器显式包装为 Integer/Long/Double/Boolean。
 * 数字统一以 Number 引用持有，getAsInt 通过 Number.intValue() 转换。
 */
public class JsonPrimitive extends JsonValue {

    private final Object value;

    public JsonPrimitive(String v) {
        this.value = v;
    }

    public JsonPrimitive(int v) {
        this.value = new Integer(v);
    }

    public JsonPrimitive(long v) {
        this.value = new Long(v);
    }

    public JsonPrimitive(double v) {
        this.value = new Double(v);
    }

    public JsonPrimitive(boolean v) {
        this.value = new Boolean(v);
    }

    public String getAsString() {
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue() ? "true" : "false";
        }
        return value.toString();
    }

    public int getAsInt() {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        throw new UnsupportedOperationException("Not a number: " + value);
    }

    public boolean getAsBoolean() {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        throw new UnsupportedOperationException("Not a boolean: " + value);
    }

    Object rawValue() {
        return value;
    }

    boolean isString() {
        return value instanceof String;
    }

    boolean isNumber() {
        return value instanceof Number;
    }

    boolean isBoolean() {
        return value instanceof Boolean;
    }
}
