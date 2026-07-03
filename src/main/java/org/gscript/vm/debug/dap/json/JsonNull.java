package org.gscript.vm.debug.dap.json;

/**
 * JSON null 单例（替代 Gson 的 JsonNull）。
 */
public class JsonNull extends JsonValue {

    public static final JsonNull INSTANCE = new JsonNull();

    private JsonNull() {
    }

    public boolean isJsonNull() {
        return true;
    }

    public String getAsString() {
        return "null";
    }
}
