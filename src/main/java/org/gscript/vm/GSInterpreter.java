package org.gscript.vm;

import org.gscript.vm.value.*;

import java.util.ArrayDeque;
import java.util.ArrayList;

public class GSInterpreter {

    /**
     * 顶级域
     */
    public GSEnv global = new GSEnv("global", null);

    /**
     * 当前运行栈
     */
    public ArrayDeque<GSValue> stack = new ArrayDeque<>();

    public GSInterpreter() {
    }

    /**
     * 执行js函数
     *
     * @param frame 当前函数帧
     * @param args  函数入参
     */
    private void eval(GSFrame frame, ArrayList<GSValue> args) {
        while (!frame.isEvalComplete()) {
            // 获取当前字节码
            String[] codes = frame.getCode();
            // 自增程序计数器
            frame.incrIP();
            // 命令类型
            String command = codes[0];
            try {
                switch (command) {
                    case "const": {
                        GSValue temp = null;
                        String type = codes[1];
                        String value = codes[2];
                        switch (type) {
                            case "a": {  // 从域中加载变量到栈顶
                                temp = frame.function.getVariableFromScope(value);
                                break;
                            }
                            case "i": {  // 加载整数到栈顶
                                temp = new GSInt(value);
                                break;
                            }
                            case "f": {  // 加载浮点数到栈顶
                                temp = new GSFloat(value);
                                break;
                            }
                            case "s": {  // 加载字符串到栈顶
                                temp = new GSString(value);
                                break;
                            }
                            case "b": {  // 加载布尔型到栈顶
                                temp = GSBool.getGSBool(value);
                                break;
                            }
                            default: {  // 抛出异常
                                throw new Error("VMError: the virtual machine does not support this bytecode");
                            }
                        }
                        stack.push(temp);
                        break;
                    }
                    case "arith_op": {
                        String type = codes[1];
                        GSValue v2 = stack.pop();
                        if (type.equals("neg")) {
                            stack.push(GSValue.neg(v2));
                        } else {
                            GSValue v1 = stack.pop();
                            switch (type) {
                                case "plus": {
                                    stack.push(GSValue.plus(v1, v2));
                                    break;
                                }
                                case "minus": {
                                    stack.push(GSValue.minus(v1, v2));
                                    break;
                                }
                                case "mul": {
                                    stack.push(GSValue.mul(v1, v2));
                                    break;
                                }
                                case "div": {
                                    stack.push(GSValue.div(v1, v2));
                                    break;
                                }
                                case "ls": {
                                    stack.push(GSValue.ls(v1, v2));
                                    break;
                                }
                                case "rs": {
                                    stack.push(GSValue.rs(v1, v2));
                                    break;
                                }
                                default: {  // 抛出异常
                                    throw new Error("VMError: the virtual machine does not support this bytecode");
                                }
                            }
                        }
                        break;
                    }
                    case "getfield": {
                        String name = stack.pop().toStringValue();
                        GSValue objRef = stack.pop();
                        if (objRef.type <= 3) {
                            stack.push(GSNull.NULL);
                        } else if (objRef.type == 8) {
                            // TODO 抛出异常
                            throw new GSException(frame.function.name, frame.getIP() - 1, new GSString("TypeError: Cannot read properties of null"));
                        } else {
                            GSObject object = (GSObject) objRef;
                            stack.push(object.getProperty(name));
                        }
                        break;
                    }
                    case "putfield": {
                        GSValue objValue = stack.pop();
                        String name = stack.pop().toStringValue();
                        GSValue objRef = stack.pop();
                        if (objRef.type > 3) {
                            if (objRef.type != 8) {
                                GSObject object = (GSObject) objRef;
                                object.setProperty(name, objValue);
                            } else {
                                throw new GSException(frame.function.name, frame.getIP() - 1, new GSString("TypeError: Cannot set properties of null."));
                            }
                        }
                        stack.push(objValue);
                        break;
                    }
                    case "copy": {
                        stack.push(stack.peek());
                        break;
                    }
                    case "copy2": {
                        GSValue value2 = stack.pop();
                        GSValue value1 = stack.pop();
                        stack.push(value1);
                        stack.push(value2);
                        stack.push(value1);
                        stack.push(value2);
                        break;
                    }
                    case "comp": {
                        String type = codes[1];
                        GSValue v2 = stack.pop();
                        GSValue v1 = stack.pop();
                        switch (type) {
                            case "eq": {  // 等于
                                stack.push(GSBool.getGSBool(GSValue.eq(v1, v2)));
                                break;
                            }
                            case "neq": {  // 不等于
                                stack.push(GSBool.getGSBool(!GSValue.eq(v1, v2)));
                                break;
                            }
                            case "seq": {  // 严格等于
                                stack.push(GSBool.getGSBool(GSValue.seq(v1, v2)));
                                break;
                            }
                            case "sneq": {  // 严格不等于
                                stack.push(GSBool.getGSBool(!GSValue.seq(v1, v2)));
                                break;
                            }
                            case "gt": {  // 大于
                                stack.push(GSBool.getGSBool(GSValue.gt(v1, v2)));
                                break;
                            }
                            case "ge": {  // 大于等于
                                stack.push(GSBool.getGSBool(GSValue.ge(v1, v2)));
                                break;
                            }
                            case "lt": {  // 小于
                                stack.push(GSBool.getGSBool(GSValue.lt(v1, v2)));
                                break;
                            }
                            case "le": {  // 小于等于
                                stack.push(GSBool.getGSBool(GSValue.le(v1, v2)));
                                break;
                            }
                            default: {
                                throw new Error("VMError: the virtual machine does not support this bytecode");
                            }
                        }
                        break;
                    }
                    case "declare": {
                        String name = codes[1];
                        frame.function.declareVariableToScope(name);
                        break;
                    }
                    case "decr": {
                        GSValue v1 = stack.pop();
                        stack.push(v1.decr());
                        break;
                    }
                    case "false_jump": {
                        GSValue value = stack.pop();
                        int offset = Integer.parseInt(codes[1]);
                        if (!value.toBoolean()) {
                            int ip = frame.getIP() + offset - 1;
                            frame.setIp(ip);
                        }
                        break;
                    }
                    case "fstore": {
                        String name = codes[1];
                        int offset = Integer.parseInt(codes[2]);
                        GSValue value;
                        // 传来的参数可能为空，也就是调用的时候少传
                        if (offset < args.size()) {
                            value = args.get(offset);
                        } else {
                            value = GSNull.NULL;
                        }
                        // 关联变量值到当前域
                        frame.function.assignmentVariableToScope(name, value);
                        break;
                    }
                    case "fundef": {
                        String name = codes[1];
                        int len = Integer.parseInt(codes[2]);
                        int ip = frame.getIP();
                        GSEnv env = frame.function.getEnv();
                        String[][] src = new String[len][];
                        System.arraycopy(frame.function.src, ip, src, 0, len);
                        GSFunction function = new GSFunction(name, src, env);
                        stack.push(function);
                        // 加载定义函数后要移动程序计数器
                        frame.setIp(ip + len);
                        break;
                    }
                    case "incr": {
                        GSValue v1 = stack.pop();
                        stack.push(v1.incr());
                        break;
                    }
                    case "invoke": {
                        // 取变量值
                        int argCount = Integer.parseInt(codes[1]);
                        ArrayList<GSValue> callArgs = new ArrayList<>();
                        // 预占位this
                        callArgs.add(null);
                        for (int i = 0; i < argCount; i++) {
                            callArgs.add(stack.pop());
                        }
                        GSValue methodRef = stack.pop();
                        GSValue objectRef = stack.pop();
                        // 设置this传参数
                        callArgs.set(0, objectRef);
                        if (methodRef.type == 6) {  // 普通函数（普通函数执行后会往栈顶放值）
                            GSFunction method = (GSFunction) methodRef;
                            GSFrame newFrame = new GSFrame(method);
                            eval(newFrame, callArgs);
                        } else if (methodRef.type == 9) {  // 本地函数（本地函数需要手动放值）
                            GSNativeFunction nativeFunction = (GSNativeFunction) methodRef;
                            GSValue r = nativeFunction.eval(callArgs);
                            stack.push(r);
                        } else {  // 函数引用为空
                            throw new GSException(frame.function.name, frame.getIP() - 1, new GSString("TypeError: function not exit."));
                        }
                        break;
                    }
                    case "loop_jump": {
                        frame.function.freeToSpecScope("loop");
                        int offset = Integer.parseInt(codes[1]);
                        int ip = frame.getIP() + offset - 1;
                        frame.setIp(ip);
                        break;
                    }
                    case "block_jump": {
                        frame.function.freeToSpecScope("block");
                        int offset = Integer.parseInt(codes[1]);
                        int ip = frame.getIP() + offset - 1;
                        frame.setIp(ip);
                        break;
                    }
                    case "jump": {
                        int offset = Integer.parseInt(codes[1]);
                        int ip = frame.getIP() + offset - 1;
                        frame.setIp(ip);
                        break;
                    }
                    case "lda_null": {
                        stack.push(GSNull.NULL);
                        break;
                    }
                    case "lda_nan": {
                        stack.push(GSNaN.NAN);
                        break;
                    }
                    case "new": {
                        String type = codes[1];
                        if ("Object".equals(type)) {
                            stack.push(new GSObject());
                        } else if ("Array".equals(type)) {
                            stack.push(new GSArray());
                        } else {
                            // TODO 抛出异常
                        }
                        break;
                    }
                    case "constructor": {
                        // 默认对象
                        GSObject object = new GSObject();
                        // 取变量值
                        int argCount = Integer.parseInt(codes[1]);
                        ArrayList<GSValue> callArgs = new ArrayList<>();
                        // 预占位this
                        callArgs.add(null);
                        for (int i = 0; i < argCount; i++) {
                            callArgs.add(stack.pop());
                        }
                        GSValue methodRef = stack.pop();
                        // 出栈未使用的默认this
                        stack.pop();
                        // 设置this传参数
                        callArgs.set(0, object);
                        if (methodRef.type == 6) {  // 普通函数（普通函数执行后会往栈顶放值）
                            GSFunction method = (GSFunction) methodRef;
                            GSFrame newFrame = new GSFrame(method);
                            eval(newFrame, callArgs);
                            // 这里需要取栈顶的数据，看看函数执行完成以后是不是一个对象，如果是，返回函数返回的对象
                            GSValue r = stack.pop();
                            if (r.type >= 4 && r.type != 8) {
                                object = (GSObject) r;
                            }
                            // 否则返回默认对象
                            stack.push(object);
                        } else if (methodRef.type == 9) {  // 本地函数（本地函数需要手动放值）
                            GSNativeFunction nativeFunction = (GSNativeFunction) methodRef;
                            GSValue r = nativeFunction.eval(callArgs);
                            // 看构造函数是不是返回了对象
                            if (r.type >= 4) {
                                object = (GSObject) r;
                            }
                            // 否则返回默认对象
                            stack.push(object);
                        } else {
                            throw new GSException(frame.function.name, frame.getIP() - 1, new GSString("TypeError: constructor function not exit."));
                        }
                        break;
                    }
                    case "pop": {
                        stack.pop();
                        break;
                    }
                    case "popenv": {
                        String name = codes[1];
                        frame.function.freeToSpecScope(name);
                        break;
                    }
                    case "pushenv": {
                        // 域类型
                        String type = codes[1];
                        // 函数名称
                        String name = frame.function.name;
                        // 增加域
                        GSEnv env = frame.function.addEnv(type);
                        // 创建函数域的时候，如果不是匿名函数，把函数本身加入到域里面
                        if ("function".equals(type)) {
                            // 隐式入参this
                            env.addVariableValue("this", args.get(0));
                            // 入参函数名指向函数本身
                            if (!"null".equals(name)) {
                                env.addVariableValue(name, frame.function);
                            }
                        }
                        frame.function.setEnv(env);
                        break;
                    }
                    case "rela_op": {
                        String type = codes[1];
                        GSValue v2 = stack.pop();
                        switch (type) {
                            case "b_and": {
                                GSValue v1 = stack.pop();
                                stack.push(GSValue.b_and(v1, v2));
                                break;
                            }
                            case "b_or": {
                                GSValue v1 = stack.pop();
                                stack.push(GSValue.b_or(v1, v2));
                                break;
                            }
                            case "b_xor": {
                                GSValue v1 = stack.pop();
                                stack.push(GSValue.b_xor(v1, v2));
                                break;
                            }
                            case "b_not": {
                                stack.push(GSValue.b_not(v2));
                                break;
                            }
                            case "l_not": {
                                stack.push(GSValue.l_not(v2));
                                break;
                            }
                            default: {
                                throw new Error("VMError: the virtual machine does not support this bytecode");
                            }
                        }
                        break;
                    }
                    case "return": {
                        frame.destroy();
                        return;
                    }
                    case "store": {
                        String name = codes[1];
                        GSValue value = stack.pop();
                        frame.function.setVariableToScope(name, value);
                        break;
                    }
                    case "swap": {
                        GSValue v1 = stack.pop();
                        GSValue v2 = stack.pop();
                        stack.push(v2);
                        stack.push(v1);
                        break;
                    }
                    case "throw": {
                        GSValue origin = stack.pop();
                        throw new GSException(frame.function.name, frame.getIP() - 1, origin);
                    }
                    case "try_start": {
                        // 往当前frame的异常监视表里面增加监视
                        int tryStart = Integer.parseInt(codes[1]);
                        int tryEnd = Integer.parseInt(codes[2]);
                        int catchStart = Integer.parseInt(codes[3]);
                        int finallyStart = Integer.parseInt(codes[4]);
                        frame.addGSExceptionMonitor(tryStart, tryEnd, catchStart, finallyStart);
                        break;
                    }
                    case "try_end": {
                        // 销毁当前监视表的当前异常监视
                        frame.tryEndCheck();
                        break;
                    }
                    case "finally_check": {
                        // 检测是否向上抛出异常
                        frame.finallyCheck();
                        break;
                    }
                    default: {  // 这里要报错，不支持的字节码
                        break;
                    }
                }
            } catch (GSException e) {  // 如果是包装好的异常
                // 进行异常处理，异常可能抛到上一个frame
                GSValue value = frame.handleException(e.getIp(), e);
                // 这里判断null的原因是如果是try或者catch转finally的话，是不需要往栈顶放异常对象
                if (value != null) {
                    // 设置当前异常对象到栈顶
                    stack.push(value);
                }
            } catch (Exception e) {  // 如果是运行时异常（虚拟机异常）
                e.printStackTrace();
                // 包装异常对象并再处理异常对象
                GSValue origin = new GSString(e.getMessage());
                int ip = frame.getIP() - 1;
                GSException exception = new GSException(frame.function.name, ip, origin);
                // 进行异常处理，异常可能抛到上一个frame
                GSValue value = frame.handleException(ip, exception);
                if (value != null) {
                    // 设置当前异常对象到栈顶
                    stack.push(value);
                }
            }
        }
    }

    /**
     * 执行字节码
     *
     * @param src 字节码
     */
    public void eval(String[] src) {
        String[][] codes = new String[src.length][];
        for (int i = 0; i < src.length; i++) {
            String code = src[i];
            codes[i] = code.split(" ");
        }
        GSFunction anonymous = new GSFunction("null", codes, global);
        GSFrame frame = new GSFrame(anonymous);
        try {
            eval(frame, null);
        } catch (GSException e) {
            System.out.println(String.format("Uncaught Error: %s at <anonymous>:%d", e.origin.toStringValue(), e.getIp()));
        }
    }

    /**
     * 增加变量到全局域
     */
    public void addVariableToGlobal(String name, GSValue value) {
        global.addVariableValue(name, value);
    }
}
