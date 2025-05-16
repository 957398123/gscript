package gscript;

import gscript.token.GSToken;
import gscript.token.GSTokenType;
import gscript.vm.Env;
import gscript.vm.stdlib.PrintFunction;
import gscript.vm.value.*;

import java.util.*;

public class Interpreter {

    /**
     * 变量域名
     */
    private LinkedList<Env> list;

    private Env gEnv;

    /**
     * 运行时堆栈
     */
    public SwapStack runStack;

    public Interpreter() {
        list = new LinkedList<>();
        gEnv = new Env("global");
        runStack = new SwapStack();
        // 注册标准函数
        registerStdlib();
    }

    public static class SwapStack<T extends GSValue> {
        private final Deque<T> stack = new ArrayDeque<>();

        public void push(T item) {
            stack.push(item);
        }

        /**
         * 复制栈顶元素
         */
        public void copy() {
            // TODO,这里基本类型要new
            stack.push(stack.peek());
        }

        public T pop() {
            return stack.pop();
        }

        public void swapTopTwo() {
            if (stack.size() < 2) {
                throw new IllegalStateException("Stack has fewer than 2 elements");
            }
            T top = stack.pop();
            T second = stack.pop();
            stack.push(top);
            stack.push(second);
        }
    }

    /**
     * 从域中获取变量，未取到返回null
     *
     * @param name
     * @return
     */
    public GSValue getVariable(String name) {
        // this只在当前function域名查找
        Iterator<Env> descendingIterator = list.descendingIterator();
        if ("this".equals(name)) {
            // 从底部往头部查找
            while (descendingIterator.hasNext()) {
                Env env = descendingIterator.next();
                if (env.values.containsKey(name)) {
                    return env.values.get(name);
                } else if ("function".equals(env.name)) {  // 如果当前查找到function域没有，返回null
                    break;
                }
            }
        } else {
            // 从底部往头部查找
            while (descendingIterator.hasNext()) {
                Env env = descendingIterator.next();
                if (env.values.containsKey(name)) {
                    return env.values.get(name);
                }
            }
            // 查找全局域是否存在
            if (gEnv.values.containsKey(name)) {
                return gEnv.values.get(name);
            }
        }
        return GSNull.NULL;
    }

    /**
     * 往域中设置值（会提升类型到全局）
     *
     * @param name
     * @param value
     */
    public void setVariable(String name, GSValue value) {
        if (list.size() == 0) {
            gEnv.values.put(name, value);
        } else {
            // 从底部往头部查找
            Iterator<Env> descendingIterator = list.descendingIterator();
            while (descendingIterator.hasNext()) {
                Env env = descendingIterator.next();
                if (env.values.containsKey(name)) {
                    env.values.put(name, value);
                    return;
                }
            }
            // 没找到添加到全局域
            gEnv.values.put(name, value);
        }
    }

    /**
     * 往最近的域设置值
     *
     * @param name
     * @param value
     */
    public void setNearEnvVariable(String name, GSValue value) {
        if (list.size() == 0) {
            gEnv.values.put(name, value);
        } else {
            Env env = list.getLast();
            env.values.put(name, value);
        }
    }

    /**
     * 声明变量
     *
     * @param name
     */
    public void declareVariable(String name) {
        Env env;
        if (list.size() == 0) {
            env = gEnv;
        } else {
            env = list.peekLast();
        }
        if (!env.values.containsKey(name)) {
            // 如果未设置值
            env.values.put(name, GSNull.NULL);
        }
    }

    /**
     * 添加变量到全局域
     *
     * @param name
     * @param value
     */
    public void setGlobalVar(String name, GSObject value) {
        gEnv.values.put(name, value);
    }

    /**
     * 增加域
     *
     * @param env
     */
    public void addEnv(Env env) {
        list.add(env);
    }

    /**
     * 减少域
     */
    public void removeLastEnv() {
        list.removeLast();
    }

    /**
     * 恢复变量域到最近的循环域
     */
    public void restNearLoopEnv() {
        Iterator<Env> descendingIterator = list.descendingIterator();
        while (descendingIterator.hasNext()) {
            Env env = descendingIterator.next();
            if (!"loop".equals(env.name)) {
                descendingIterator.remove(); // 删除当前元素
            } else {
                break;
            }
        }
    }

    /**
     * 恢复变量域到最近的函数域
     */
    public void restNearFunctionEnv() {
        Iterator<Env> descendingIterator = list.descendingIterator();
        while (descendingIterator.hasNext()) {
            Env env = descendingIterator.next();
            if (!"function".equals(env.name)) {
                descendingIterator.remove(); // 删除当前元素
            } else {
                break;
            }
        }
    }

    /**
     * 获取int值
     *
     * @param obj
     * @return
     */
    private int getIntValue(GSValue obj) {
        int value = 0;
        if (obj.type == 1) {
            if (((GSBool) obj).value) {
                value = 1;
            } else {
                value = 0;
            }
        } else if (obj.type == 2) {
            value = ((GSInt) obj).value;
        } else if (obj.type == 3) {
            value = (int) ((GSFloat) obj).value;
        }
        return value;
    }

    public float getFloatValue(GSValue obj) {
        float value = 0;
        if (obj.type == 3) {
            value = ((GSFloat) obj).value;
        } else {
            value = getIntValue(obj);
        }
        return value;
    }

    public boolean getBoolean(GSValue obj) {
        return ((GSBool) obj).value;
    }

    /**
     * 注册本地函数
     *
     * @param name
     * @return
     */
    private void registerNative(String name, GSNativeFunction fun) {
        gEnv.values.put(name, fun);
    }

    /**
     * 注册标准库
     */
    private void registerStdlib() {
        registerNative("println", new PrintFunction());
    }

    public void callFunction(GSFunction fun, List<GSValue> args) {
        int ip = 0;
        String[] codes = fun.codes;
        while (ip < codes.length) {
            String[] bytes = codes[ip++].split(" ");
            switch (bytes[0]) {
                case "const": {  // 加载变量到栈顶
                    String value = bytes[2];
                    GSValue obj = null;
                    switch (bytes[1]) {
                        case "a": {  // 从变量域中加载变量值
                            obj = getVariable(value);
                            break;
                        }
                        case "i": {  // 加载整数到栈顶
                            obj = new GSInt(Integer.parseInt(value));
                            break;
                        }
                        case "f": {  // 加载浮点数到栈顶
                            obj = new GSFloat(Float.parseFloat(value));
                            break;
                        }
                        case "s": {  // 加载字符串到栈顶
                            String str = String.join(" ", Arrays.copyOfRange(bytes, 2, bytes.length));
                            obj = new GSStr(str);
                            break;
                        }
                        case "b": {  // 加载布尔型到栈顶
                            obj = new GSBool(Boolean.parseBoolean(value));
                            break;
                        }
                    }
                    runStack.push(obj);
                    break;
                }
                case "store": {  // 存储变量（会提升到全局）
                    String name = bytes[1];
                    setVariable(name, runStack.pop());
                    break;
                }
                case "arith_op": {  // 算数运算
                    GSValue v2 = runStack.pop();
                    switch (bytes[1]) {
                        case "plus": {
                            GSValue v1 = runStack.pop();
                            if (v1.type > 3 || v2.type > 3) {
                                String value = v1.toValueString() + v2.toValueString();
                                runStack.push(new GSStr(value));
                            } else {
                                if (v1.type < 3 && v2.type < 3) {
                                    runStack.push(new GSInt(getIntValue(v1) + getIntValue(v2)));
                                } else {  // 提升为float
                                    runStack.push(new GSFloat(getFloatValue(v1) + getFloatValue(v2)));
                                }
                            }
                            break;
                        }
                        case "minus": {
                            GSValue v1 = runStack.pop();
                            if (v1.type < 3 && v2.type < 3) {
                                runStack.push(new GSInt(getIntValue(v1) - getIntValue(v2)));
                            } else {  // 提升为float
                                runStack.push(new GSFloat(getFloatValue(v1) - getFloatValue(v2)));
                            }
                            break;
                        }
                        case "mul": {
                            GSValue v1 = runStack.pop();
                            if (v1.type < 3 && v2.type < 3) {
                                runStack.push(new GSInt(getIntValue(v1) * getIntValue(v2)));
                            } else {  // 提升为float
                                runStack.push(new GSFloat(getFloatValue(v1) * getFloatValue(v2)));
                            }
                            break;
                        }
                        case "div": {
                            GSValue v1 = runStack.pop();
                            if (v1.type < 3 && v2.type < 3) {
                                runStack.push(new GSInt(getIntValue(v1) / getIntValue(v2)));
                            } else {  // 提升为float
                                runStack.push(new GSFloat(getFloatValue(v1) / getFloatValue(v2)));
                            }
                            break;
                        }
                        case "modulo": {
                            GSValue v1 = runStack.pop();
                            if (v1.type < 3 && v2.type < 3) {
                                runStack.push(new GSInt(getIntValue(v1) % getIntValue(v2)));
                            } else {  // 提升为float
                                runStack.push(new GSFloat(getFloatValue(v1) % getFloatValue(v2)));
                            }
                            break;
                        }
                        case "neg": {
                            if (v2.type == 3) {
                                runStack.push(new GSFloat(-getFloatValue(v2)));
                            } else {
                                runStack.push(new GSInt(-getIntValue(v2)));
                            }
                            break;
                        }
                        case "ls": {
                            GSValue v1 = runStack.pop();
                            runStack.push(new GSInt(getIntValue(v1) << getIntValue(v2)));
                            break;
                        }
                        case "rs": {
                            GSValue v1 = runStack.pop();
                            runStack.push(new GSInt(getIntValue(v1) >> getIntValue(v2)));
                            break;
                        }
                    }
                    break;
                }
                case "rela_op": {  // 逻辑操作
                    GSValue v2 = runStack.pop();
                    switch (bytes[1]) {
                        case "b_and": {
                            GSValue v1 = runStack.pop();
                            runStack.push(new GSInt(getIntValue(v1) & getIntValue(v2)));
                            break;
                        }
                        case "b_or": {
                            GSValue v1 = runStack.pop();
                            runStack.push(new GSInt(getIntValue(v1) | getIntValue(v2)));
                            break;
                        }
                        case "b_xor": {
                            GSValue v1 = runStack.pop();
                            runStack.push(new GSInt(getIntValue(v1) ^ getIntValue(v2)));
                            break;
                        }
                        case "b_not": {
                            runStack.push(new GSInt(~getIntValue(v2)));
                            break;
                        }
                        case "l_and": {
                            GSValue v1 = runStack.pop();
                            runStack.push(new GSBool(getBoolean(v1) && getBoolean(v2)));
                            break;
                        }
                        case "l_or": {
                            GSValue v1 = runStack.pop();
                            runStack.push(new GSBool(getBoolean(v1) || getBoolean(v2)));
                            break;
                        }
                        case "l_not": {
                            runStack.push(new GSBool(!getBoolean(v2)));
                            break;
                        }
                    }
                    break;
                }
                case "incr": {
                    GSValue v1 = runStack.pop();
                    runStack.push(new GSInt(getIntValue(v1) + 1));
                    break;
                }
                case "decr": {
                    GSValue v1 = runStack.pop();
                    runStack.push(new GSInt(getIntValue(v1) - 1));
                    break;
                }
                case "getfield": {
                    GSObject obj = (GSObject) runStack.pop();
                    String name = bytes[1];
                    runStack.push(obj.getProperty(name));
                    break;
                }
                case "putfield": {
                    String name = bytes[1];
                    GSValue value = runStack.pop();
                    GSObject obj = (GSObject) runStack.pop();
                    obj.setProperty(name, value);
                    break;
                }
                case "putfieldExpr": {
                    GSValue expr = runStack.pop();
                    GSValue value = runStack.pop();
                    GSObject ref = (GSObject) runStack.pop();
                    ref.setProperty(expr.toValueString(), value);
                    break;
                }
                case "invoke": {  // 函数调用
                    int num = Integer.parseInt(bytes[1]);
                    ArrayList<GSValue> arg = null;
                    if (num > 0) {
                        arg = new ArrayList<>();
                        do {
                            arg.add(0, runStack.pop());
                            --num;
                        } while (num > 0);
                    }
                    GSValue funRef = runStack.pop();
                    // 调用函数时要创建函数域
                    if (funRef.type == 6) {
                        GSFunction funCall = (GSFunction) funRef;
                        // 调用函数
                        // 创建函数域
                        addEnv(new Env("function"));
                        callFunction(funCall, arg);
                        // 销毁函数域
                        restNearFunctionEnv();
                    } else if (funRef.type == 9) {
                        // 本地函数不需要创建域，因为不会操作操作数栈
                        GSNativeFunction funCall = (GSNativeFunction) funRef;
                        // 调用本地函数需要手动设置返回值
                        runStack.push(funCall.call(arg));
                    }
                    break;
                }
                case "invokeMember": {  // 函数调用
                    int num = Integer.parseInt(bytes[1]);
                    ArrayList<GSValue> arg = null;
                    if (num > 0) {
                        arg = new ArrayList<>();
                        do {
                            arg.add(0, runStack.pop());
                            --num;
                        } while (num > 0);
                    }
                    GSValue funRef = runStack.pop();
                    GSObject objRef = (GSObject) runStack.pop();
                    // 调用函数时要创建函数域
                    if (funRef.type == 6) {  //
                        objRef.callFunction(this, (GSFunction) funRef, arg);
                    } else if (funRef.type == 9) {
                        // 本地函数不需要创建域，因为不会操作操作数栈
                        GSNativeFunction funCall = (GSNativeFunction) funRef;
                        // 调用本地函数需要手动设置返回值
                        runStack.push(funCall.call(arg));
                    }
                    break;
                }
                case "copy": {
                    runStack.copy();
                    break;
                }
                case "swap": {
                    runStack.swapTopTwo();
                    break;
                }
                case "pop": {
                    runStack.pop();
                    break;
                }
                case "aaload": {
                    int index = ((GSInt) runStack.pop()).value;
                    GSArray array = (GSArray) runStack.pop();
                    runStack.push(array.getElement(index));
                    break;
                }
                case "aastore": {
                    GSValue value = runStack.pop();
                    int index = ((GSInt) runStack.pop()).value;
                    GSArray ref = (GSArray) runStack.pop();
                    ref.setElement(index, value);
                    break;
                }
                case "avstore": {
                    GSValue value = runStack.pop();
                    int index = ((GSInt) runStack.pop()).value;
                    GSArray ref = (GSArray) runStack.pop();
                    ref.setElement(index, value);
                    runStack.push(value);
                    break;
                }
                case "fundef": {
                    int len = Integer.parseInt(bytes[1]);
                    String funcName = "anonymous";
                    if (bytes.length == 3) {
                        funcName = bytes[2];
                    }
                    String[] src = Arrays.copyOfRange(codes, ip, ip + len);
                    setNearEnvVariable(funcName, new GSFunction(funcName, src));
                    ip += len;
                    break;
                }
                case "fundefload": {  // 定义函数并添加到栈顶
                    int len = Integer.parseInt(bytes[1]);
                    String funcName = "anonymous";
                    if (bytes.length == 3) {
                        funcName = bytes[2];
                    }
                    String[] src = Arrays.copyOfRange(codes, ip, ip + len);
                    runStack.push(new GSFunction(funcName, src));
                    ip += len;
                    break;
                }
                case "fstore": {  // 设置实参
                    String name = bytes[1];
                    int index = Integer.parseInt(bytes[2]);
                    if (index < args.size()) {
                        setNearEnvVariable(name, args.get(index));
                    } else {
                        setNearEnvVariable(name, GSNull.NULL);
                    }
                    break;
                }
                case "declare": {
                    String name = bytes[1];
                    declareVariable(name);
                    break;
                }
                case "pushenv": {
                    String name = bytes[1];
                    addEnv(new Env(name));
                    break;
                }
                case "popenv": {
                    String name = bytes[1];
                    if ("loop".equals(name)) {
                        restNearLoopEnv();
                    } else {
                        removeLastEnv();
                    }
                    break;
                }
                case "comp": {
                    GSValue v2 = runStack.pop();
                    GSValue v1 = runStack.pop();
                    boolean res = false;
                    switch (bytes[1]) {
                        case "eq": {
                            if (v1.type <= 2 && v2.type <= 2) {
                                res = getIntValue(v1) == getIntValue(v2);
                            } else if (v1.type == 3 && v2.type == 3) {
                                res = getFloatValue(v1) == getFloatValue(v2);
                            } else if (v1.type == 5 && v2.type == 5) {
                                ((GSStr) v2).toValueString().equals(((GSStr) v2).toValueString());
                            }
                            runStack.push(new GSBool(res));
                            break;
                        }
                        case "neq": {
                            if (v1.type <= 2 && v2.type <= 2) {
                                res = getIntValue(v1) != getIntValue(v2);
                            } else if (v1.type == 3 && v2.type == 3) {
                                res = getFloatValue(v1) != getFloatValue(v2);
                            } else if (v1.type == 5 && v2.type == 5) {
                                res = !((GSStr) v1).toValueString().equals(((GSStr) v2).toValueString());
                            }
                            runStack.push(new GSBool(res));
                            break;
                        }
                        case "gt": {
                            if (v1.type <= 2 && v2.type <= 2) {
                                res = getIntValue(v1) > getIntValue(v2);
                            } else if (v1.type == 3 && v2.type == 3) {
                                res = getFloatValue(v1) > getFloatValue(v2);
                            }
                            runStack.push(new GSBool(res));
                            break;
                        }
                        case "ge": {
                            if (v1.type <= 2 && v2.type <= 2) {
                                res = getIntValue(v1) >= getIntValue(v2);
                            } else if (v1.type == 3 && v2.type == 3) {
                                res = getFloatValue(v1) >= getFloatValue(v2);
                            }
                            runStack.push(new GSBool(res));
                            break;
                        }
                        case "lt": {
                            if (v1.type <= 2 && v2.type <= 2) {
                                res = getIntValue(v1) < getIntValue(v2);
                            } else if (v1.type == 3 && v2.type == 3) {
                                res = getFloatValue(v1) < getFloatValue(v2);
                            }
                            runStack.push(new GSBool(res));
                            break;
                        }
                        case "le": {
                            if (v1.type <= 2 && v2.type <= 2) {
                                res = getIntValue(v1) <= getIntValue(v2);
                            } else if (v1.type == 3 && v2.type == 3) {
                                res = getFloatValue(v1) <= getFloatValue(v2);
                            }
                            runStack.push(new GSBool(res));
                            break;
                        }
                    }
                    break;
                }
                case "jump": {
                    int line = Integer.parseInt(bytes[1]);
                    ip = ip + line - 1;
                    break;
                }
                case "false_jump": {
                    int line = Integer.parseInt(bytes[1]);
                    boolean value = ((GSBool) runStack.pop()).value;
                    if (!value) {
                        // 这里要减去自增
                        ip = ip + line - 1;
                    }
                    break;
                }
                case "loop_jump": {
                    restNearLoopEnv();
                    int line = Integer.parseInt(bytes[1]);
                    ip = ip + line - 1;
                    break;
                }
                case "lda_null": {
                    runStack.push(GSNull.NULL);
                    break;
                }
                case "new": {
                    String name = bytes[1];
                    GSValue value = GSNull.NULL;
                    switch (name) {
                        case "Object": {
                            value = new GSObject();
                            break;
                        }
                        case "Array": {
                            value = new GSArray();
                            break;
                        }
                    }
                    runStack.push(value);
                }
            }
        }
    }

    /**
     * 执行字节码
     * 将字节码封装为匿名函数执行
     *
     * @param byteCodes
     */
    public void eval(List<String> byteCodes) {
        String[] codes = byteCodes.toArray(new String[0]);
        GSFunction fun = new GSFunction("anonymous", codes);
        callFunction(fun, null);
    }
}
