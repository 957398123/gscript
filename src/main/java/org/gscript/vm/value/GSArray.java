package org.gscript.vm.value;

public class GSArray extends GSObject {

    public GSArray() {
        this.type = 7;
    }

    /**
     * 数组转字符串：元素按索引顺序用 "," 连接（JS 语义）。
     * null 元素输出为空字符串，NaN 输出为 "NaN"。
     */
    public String toStringValue() {
        if (members.isEmpty()) {
            return "";
        }
        StringBuffer sb = new StringBuffer();
        // 按索引顺序遍历，直到遇到空洞
        int index = 0;
        while (true) {
            String key = Integer.toString(index);
            GSValue value = (GSValue) members.get(key);
            if (value == null) {
                // 空洞：检查是否有更高索引的元素
                break;
            }
            if (index > 0) {
                sb.append(",");
            }
            if (value.type != 8) {  // 非 null 输出值，null 输出空串
                sb.append(value.toStringValue());
            }
            ++index;
        }
        return sb.toString();
    }

    /**
     * 获取数组属性：length 返回元素个数，其余委托父类。
     */
    public GSValue getProperty(String name) {
        if ("length".equals(name)) {
            // 计算最大连续索引 + 1
            int len = 0;
            while (members.get(Integer.toString(len)) != null) {
                ++len;
            }
            return new GSInt(len);
        }
        return super.getProperty(name);
    }
}
