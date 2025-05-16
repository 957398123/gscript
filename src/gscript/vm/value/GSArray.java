package gscript.vm.value;

public class GSArray extends GSObject {

    private int length;

    public GSArray() {
        length = 0;
        type = 7;
        setProperty("length", new GSInt(0));
    }

    public GSValue getElement(int index) {
        if (index < 0 || index >= length) {
            return GSNull.NULL;
        }
        return properties.get(String.valueOf(index));
    }

    public void setElement(int index, GSValue value) {
        // 如果越界
        if (index < 0) {  // 报错
            // 这里模仿js返回null，不处理
        } else if (index >= length) {
            // 更新数组长度
            length = index + 1;
            setProperty("length", new GSInt(length));
        }
        // 保存属性值
        setProperty(String.valueOf(index), value);
    }
}
