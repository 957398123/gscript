package org.gscript.vm.debug.dap.json;

/**
 * JSON 解析器（替代 Gson 的 JsonParser）。
 *
 * <p>递归下降解析器，支持完整 JSON 语法：对象/数组/字符串（含转义）/数字（含
 * 负数/浮点/指数）/true/false/null。Java 1.4 兼容。
 *
 * <p>用法：{@code JsonObject obj = JsonParser.parseString(json).getAsJsonObject();}
 */
public class JsonParser {

    private final String json;
    private int pos;

    private JsonParser(String json) {
        this.json = json;
        this.pos = 0;
    }

    /**
     * 解析 JSON 字符串为 JsonValue（对齐 Gson 的 JsonParser.parseString）。
     */
    public static JsonValue parseString(String json) {
        JsonParser p = new JsonParser(json);
        p.skipWhitespace();
        JsonValue v = p.parseValue();
        p.skipWhitespace();
        return v;
    }

    private JsonValue parseValue() {
        skipWhitespace();
        if (pos >= json.length()) {
            throw new RuntimeException("Unexpected end of JSON at " + pos);
        }
        char c = json.charAt(pos);
        if (c == '{') {
            return parseObject();
        }
        if (c == '[') {
            return parseArray();
        }
        if (c == '"') {
            return new JsonPrimitive(parseStringValue());
        }
        if (c == 't' || c == 'f') {
            return parseBoolean();
        }
        if (c == 'n') {
            return parseNull();
        }
        if (c == '-' || (c >= '0' && c <= '9')) {
            return parseNumber();
        }
        throw new RuntimeException("Unexpected character '" + c + "' at position " + pos);
    }

    private JsonObject parseObject() {
        JsonObject obj = new JsonObject();
        pos++; // skip '{'
        skipWhitespace();
        if (pos < json.length() && json.charAt(pos) == '}') {
            pos++;
            return obj;
        }
        while (true) {
            skipWhitespace();
            if (pos >= json.length() || json.charAt(pos) != '"') {
                throw new RuntimeException("Expected string key at position " + pos);
            }
            String key = parseStringValue();
            skipWhitespace();
            if (pos >= json.length() || json.charAt(pos) != ':') {
                throw new RuntimeException("Expected ':' at position " + pos);
            }
            pos++; // skip ':'
            JsonValue value = parseValue();
            obj.add(key, value);
            skipWhitespace();
            if (pos >= json.length()) {
                throw new RuntimeException("Unexpected end of object at " + pos);
            }
            char c = json.charAt(pos);
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                break;
            }
            throw new RuntimeException("Expected ',' or '}' at position " + pos + ", got '" + c + "'");
        }
        return obj;
    }

    private JsonArray parseArray() {
        JsonArray arr = new JsonArray();
        pos++; // skip '['
        skipWhitespace();
        if (pos < json.length() && json.charAt(pos) == ']') {
            pos++;
            return arr;
        }
        while (true) {
            JsonValue value = parseValue();
            arr.add(value);
            skipWhitespace();
            if (pos >= json.length()) {
                throw new RuntimeException("Unexpected end of array at " + pos);
            }
            char c = json.charAt(pos);
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                break;
            }
            throw new RuntimeException("Expected ',' or ']' at position " + pos + ", got '" + c + "'");
        }
        return arr;
    }

    private String parseStringValue() {
        // Assumes current char is '"'
        pos++; // skip opening '"'
        StringBuffer sb = new StringBuffer();
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (c == '"') {
                pos++;
                return sb.toString();
            }
            if (c == '\\') {
                pos++;
                if (pos >= json.length()) {
                    throw new RuntimeException("Unexpected end in string escape at " + pos);
                }
                char esc = json.charAt(pos);
                if (esc == '"') {
                    sb.append('"');
                } else if (esc == '\\') {
                    sb.append('\\');
                } else if (esc == '/') {
                    sb.append('/');
                } else if (esc == 'b') {
                    sb.append('\b');
                } else if (esc == 'f') {
                    sb.append('\f');
                } else if (esc == 'n') {
                    sb.append('\n');
                } else if (esc == 'r') {
                    sb.append('\r');
                } else if (esc == 't') {
                    sb.append('\t');
                } else if (esc == 'u') {
                    // backslash-u-XXXX (Unicode escape)
                    if (pos + 4 >= json.length()) {
                        throw new RuntimeException("Invalid unicode escape at " + pos);
                    }
                    String hex = json.substring(pos + 1, pos + 5);
                    sb.append((char) Integer.parseInt(hex, 16));
                    pos += 4;
                } else {
                    throw new RuntimeException("Invalid escape '\\" + esc + "' at position " + pos);
                }
                pos++;
            } else {
                sb.append(c);
                pos++;
            }
        }
        throw new RuntimeException("Unterminated string starting at " + (pos - 1));
    }

    private JsonPrimitive parseBoolean() {
        if (json.startsWith("true", pos)) {
            pos += 4;
            return new JsonPrimitive(true);
        }
        if (json.startsWith("false", pos)) {
            pos += 5;
            return new JsonPrimitive(false);
        }
        throw new RuntimeException("Invalid boolean at position " + pos);
    }

    private JsonNull parseNull() {
        if (json.startsWith("null", pos)) {
            pos += 4;
            return JsonNull.INSTANCE;
        }
        throw new RuntimeException("Invalid null at position " + pos);
    }

    private JsonPrimitive parseNumber() {
        int start = pos;
        if (pos < json.length() && json.charAt(pos) == '-') {
            pos++;
        }
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                pos++;
            } else {
                break;
            }
        }
        String numStr = json.substring(start, pos);
        // 含 '.' 或 'e'/'E' 视为浮点，否则整数
        boolean isFloat = false;
        for (int i = 0; i < numStr.length(); i++) {
            char c = numStr.charAt(i);
            if (c == '.' || c == 'e' || c == 'E') {
                isFloat = true;
                break;
            }
        }
        if (isFloat) {
            return new JsonPrimitive(Double.parseDouble(numStr));
        }
        return new JsonPrimitive(Long.parseLong(numStr));
    }

    private void skipWhitespace() {
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }
}
