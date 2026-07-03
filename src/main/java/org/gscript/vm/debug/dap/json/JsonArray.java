package org.gscript.vm.debug.dap.json;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * JSON 数组节点（替代 Gson 的 JsonArray）。
 *
 * <p>Java 1.4 兼容：raw type ArrayList，iterator() 返回原生 Iterator。
 */
public class JsonArray extends JsonValue {

    private final ArrayList elements = new ArrayList();

    public void add(JsonValue value) {
        elements.add(value);
    }

    public void add(String value) {
        elements.add(new JsonPrimitive(value));
    }

    public void add(int value) {
        elements.add(new JsonPrimitive(value));
    }

    public void add(boolean value) {
        elements.add(new JsonPrimitive(value));
    }

    public int size() {
        return elements.size();
    }

    public boolean isEmpty() {
        return elements.size() == 0;
    }

    public JsonValue get(int index) {
        return (JsonValue) elements.get(index);
    }

    public JsonObject getAsJsonObject(int index) {
        return (JsonObject) elements.get(index);
    }

    public Iterator iterator() {
        return elements.iterator();
    }

    public JsonArray getAsJsonArray() {
        return this;
    }

    public boolean isJsonArray() {
        return true;
    }
}
