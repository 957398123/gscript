package org.gscript.vm.value;

public class GSString extends GSObject {

    private final String value;

    public GSString(String value) {
        this.type = 5;
        this.value = value;
    }

    @Override
    public String toStringValue() {
        return value;
    }

    @Override
    public boolean toBoolean() {
        // 非空字符串为 true（JS 语义）
        return !value.isEmpty();
    }
}
