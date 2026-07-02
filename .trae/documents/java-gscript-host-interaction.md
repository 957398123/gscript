# Java 宿主与 gscript 解释器交互 API

## Summary

新增 Java ↔ gscript 双向交互能力。目前 gscript→Java 已通过 `GSNativeFunction`（如 `Console`）实现，足够使用；但 Java→gscript 方向缺失：Java 无法便捷地执行一段 gscript 代码、求值表达式、或读写解释器中的变量。

本次新增 4 个 `GSInterpreter` 方法 + 2 个 `GSValue` 方法，使 Java 宿主能：
- 执行 gscript 源码 / 求值表达式并获取结果
- 获取 / 设置全局变量（自动转换 Java↔GSValue 类型）
- 递归转换 GSValue↔Java 原生对象（Map/List/Integer/Float/String/Boolean/null）

## 当前状态分析（已核实）

### 已存在的能力
- `GSInterpreter.global` 是 `public GSEnv`（GSInterpreter.java:19），顶级作用域
- `GSInterpreter.addVariableToGlobal(String, GSValue)`（GSInterpreter.java:671）是唯一变量注入 API，**无 getVariable**
- `eval(byte[][], Object[], int[], String)`（GSInterpreter.java:618）创建顶级匿名函数（env=global）并执行；**返回 void**
- `eval(String[])`（GSInterpreter.java:664）保留的文本入口，内部用 `BytecodeEncoder` 编码后委托 `eval(byte[][],...)`
- `OP_RETURN`（GSInterpreter.java:495-509）：正常返回时 `frame.destroy(); return;`，**返回值留在 `interpreter.stack` 上**（line 506 注释："返回值留在共享栈上由调用者读取"）
- `eval(byte[][],...)` **内部 catch GSException**（line 628-630）并打印 "Uncaught Error: ..."，异常被吞掉
- `GSEnv.getVariableValue(String)`（GSEnv.java:37）只查当前 env 的 HashMap，**不遍历 parent**；但 global 是顶级（parent=null），所有全局变量都在 global.values 中

### 关键确认：top-level var 落在 global
- `visit(ProgramNode)`（ByteCodeGenerator.java:144-153）**不 emit `pushenv`**——直接生成语句字节码
- 顶级匿名函数 env=global（GSInterpreter.java:619），故 `var x=10;` 的 `OP_DECLARE`/`OP_STORE` 直接操作 `global.values`
- 因此 `global.getVariableValue("x")` 能正确取到值 ✓

### GSValue 类型体系
- type: 1=bool, 2=int, 3=float, 4=object, 5=str, 6=function, 7=array, 8=null, 9=native, 10=nan
- **继承关系**：`GSObject extends GSValue`；`GSString`/`GSNull`/`GSNaN`/`GSArray`/`GSFunction` **都 extends GSObject**
- 字段可见性：
  - `GSInt.value` / `GSFloat.value` / `GSBool.value`：**public**（可直接访问）
  - `GSString.value`：**private**（必须用 `toStringValue()`）
  - `GSBool` / `GSNull` / `GSNaN` 构造器 **private**（用 `GSBool.getGSBool()` / `GSNull.NULL` / `GSNaN.NAN`）
- `GSObject.getMembers()` 返回 `Map<String,GSValue>`；`getProperty(String)` / `setProperty(String, GSValue)`
- `GSArray` 无独立 API，元素存 `members` HashMap 键为索引字符串 "0","1",...

### 编译链
`Lexer.tokenize(String) → List<GSToken>` → `Parser.parseProgram() → Node` → `ByteCodeGenerator` visitor → `getByteCode() → ArrayList<String>` → `BytecodeEncoder.encode(List<String>) → EncodedBytecode{instructions: byte[][], constantPool: Object[]}`

## Proposed Changes

### 文件 1: `src/main/java/org/gscript/vm/GSInterpreter.java`

**新增 import**（当前已有 BytecodeEncoder/EncodedBytecode/Arrays/ArrayList/ArrayDeque）：
```java
import org.gscript.compile.Lexer;
import org.gscript.compile.Parser;
import org.gscript.compile.gen.ByteCodeGenerator;
import org.gscript.compile.node.Node;
import org.gscript.compile.token.GSToken;
import java.util.List;
```

**新增 5 个方法**（放在类末尾，`addVariableToGlobal` 之后）：

#### 1.1 私有编译辅助 `compile(String)`

```java
/**
 * 编译 gscript 源码为二进制字节码（共享 global env 执行用）。
 *
 * @param code gscript 源码
 * @return 编码后的二进制字节码 + 常量池
 */
private EncodedBytecode compile(String code) {
    Lexer lexer = new Lexer();
    List<GSToken> tokens = lexer.tokenize(code);
    Parser parser = new Parser(tokens);
    Node program = parser.parseProgram();
    ByteCodeGenerator gen = new ByteCodeGenerator();
    program.accept(gen);
    String[] src = gen.getByteCode().toArray(new String[0]);
    return new BytecodeEncoder().encode(Arrays.asList(src));
}
```

**为什么私有**：内部复用，避免暴露编译细节。`evalScript` 和 `evalExpression` 共用，消除重复。

#### 1.2 `evalScript(String code)`

```java
/**
 * 在当前解释器全局上下文中执行一段 gscript 源码（语句序列）。
 *
 * <p>共享 {@link #global} 环境：执行的 var 声明、function 定义会落入 global，
 * 后续 {@link #getVariable} / {@link #evalExpression} 可访问。
 * 脚本未捕获异常会被捕获并打印（与 {@link #eval(byte[][], Object[], int[], String)} 行为一致）。
 *
 * @param code gscript 源码
 */
public void evalScript(String code) {
    EncodedBytecode encoded = compile(code);
    eval(encoded.instructions, encoded.constantPool, null, null);
}
```

**为什么委托 `eval(byte[][],...)`**：复用现成的异常处理 + 调试器调用栈管理，不重复逻辑。

#### 1.3 `evalExpression(String expr)`

```java
/**
 * 求值一个 gscript 表达式并返回结果。
 *
 * <p>实现：将表达式包装为 {@code return (expr);} 执行，OP_RETURN 会把结果留在
 * {@link #stack} 上，执行后弹出返回。表达式出错（语法/运行时）时返回 {@link GSNull#NULL}。
 *
 * <p>注意：不能复用 {@link #evalScript}（它内部 eval 会吞掉 GSException），
 * 故直接调用 {@link #eval(GSFrame, ArrayList)} 以检测异常。
 *
 * @param expr gscript 表达式（如 "a + b"、"add(1, 2)"、"{x: 1, y: 2}"）
 * @return 求值结果，出错返回 GSNull.NULL
 */
public GSValue evalExpression(String expr) {
    stack.clear();  // 清空栈上残留值，确保返回值是本次表达式的结果
    EncodedBytecode encoded = compile("return (" + expr + ");");
    GSFunction anonymous = new GSFunction("null", encoded.instructions, encoded.constantPool, global);
    GSFrame frame = new GSFrame(anonymous);
    try {
        eval(frame, null);
    } catch (GSException e) {
        System.out.println(String.format("Uncaught Error: %s at <anonymous>:%d", e.origin.toStringValue(), e.getIp()));
        stack.clear();
        return GSNull.NULL;
    } catch (DebugAbortException e) {
        return GSNull.NULL;
    }
    if (stack.isEmpty()) return GSNull.NULL;
    return stack.pop();
}
```

**关键设计点**：
- `stack.clear()` 先清空残留——top-level expression statement 会往栈上留值，不清空则 `pop()` 可能取到旧值
- 直接调 `eval(frame, null)`（非 `eval(byte[][],...)`）以让 GSException 传播——这样才能区分成功/失败
- 顶层 `return` 合法（EBNF: `Program = { Statement }`，`ReturnStatement` 是 `Statement`），Parser 接受
- catch GSException 后 `stack.clear()` 清理异常路径残留的中间值

#### 1.4 `getVariable(String name)`

```java
/**
 * 从全局作用域获取变量。
 *
 * @param name 变量名
 * @return 变量值，未定义返回 {@link GSNull#NULL}
 */
public GSValue getVariable(String name) {
    GSValue value = global.getVariableValue(name);
    return value != null ? value : GSNull.NULL;
}
```

#### 1.5 `setVariable(String name, Object value)`

```java
/**
 * 设置全局变量（自动包装 Java 对象为 GSValue）。
 *
 * @param name  变量名
 * @param value Java 对象（Integer/Float/Double/String/Boolean/Map/List/null/GSValue）
 */
public void setVariable(String name, Object value) {
    addVariableToGlobal(name, GSValue.fromJavaObject(value));
}
```

---

### 文件 2: `src/main/java/org/gscript/vm/value/GSValue.java`

**新增 import**：
```java
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
```
（GSObject/GSArray/GSInt/GSFloat/GSString/GSBool/GSNull/GSNaN 同包，无需 import）

**新增 2 个方法**：

#### 2.1 `toJavaObject()` 实例方法

```java
/**
 * 将 GSValue 转换为 Java 原生对象（递归转换对象/数组）。
 *
 * @return Java 对象：Boolean/Integer/Float/String/null/Map/List；function/native 原样返回
 */
public Object toJavaObject() {
    switch (this.type) {
        case 1:  // GSBool
            return ((GSBool) this).value;
        case 2:  // GSInt
            return ((GSInt) this).value;
        case 3:  // GSFloat
            return ((GSFloat) this).value;
        case 5:  // GSString（value 私有，用 toStringValue）
            return this.toStringValue();
        case 8:  // GSNull
            return null;
        case 10: // GSNaN
            return Float.NaN;
        case 4:  // GSObject
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, GSValue> e : ((GSObject) this).getMembers().entrySet()) {
                map.put(e.getKey(), e.getValue().toJavaObject());
            }
            return map;
        case 7:  // GSArray（extends GSObject，元素键为 "0","1",...）
            List<Object> list = new ArrayList<>();
            GSObject arr = (GSObject) this;
            for (int i = 0; arr.getMembers().containsKey(String.valueOf(i)); i++) {
                list.add(arr.getProperty(String.valueOf(i)).toJavaObject());
            }
            return list;
        default:  // GSFunction(6) / GSNativeFunction(9) 原样返回
            return this;
    }
}
```

#### 2.2 `fromJavaObject(Object)` 静态方法

```java
/**
 * 将 Java 对象转换为 GSValue（静态工厂）。
 *
 * @param obj Java 对象（Integer/Float/Double/String/Boolean/Map/List/null/GSValue）
 * @return 对应的 GSValue
 * @throws IllegalArgumentException 不支持的类型
 */
public static GSValue fromJavaObject(Object obj) {
    if (obj == null) return GSNull.NULL;
    if (obj instanceof GSValue) return (GSValue) obj;
    if (obj instanceof Integer) return new GSInt((Integer) obj);
    if (obj instanceof Float) return new GSFloat((Float) obj);
    if (obj instanceof Double) return new GSFloat(((Double) obj).floatValue());
    if (obj instanceof String) return new GSString((String) obj);
    if (obj instanceof Boolean) return GSBool.getGSBool((Boolean) obj);
    if (obj instanceof Map) {
        GSObject gso = new GSObject();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) obj).entrySet()) {
            gso.setProperty(e.getKey().toString(), fromJavaObject(e.getValue()));
        }
        return gso;
    }
    if (obj instanceof List) {
        GSArray gsa = new GSArray();
        int i = 0;
        for (Object item : (List<?>) obj) {
            gsa.setProperty(String.valueOf(i++), fromJavaObject(item));
        }
        return gsa;
    }
    throw new IllegalArgumentException("Cannot convert Java type to GSValue: " + obj.getClass());
}
```

---

### 文件 3: `src/main/java/org/gscript/TestScript.java`

**新增 `hosttest` 模式**（main 方法分支 + hostTest 方法），作为 Python 测试的 Java 入口。

main 中新增分支（在 `dumpgclass` 分支后）：
```java
} else if ("hosttest".equals(mode)) {
    TestScript.hostTest();
}
```

新增方法：
```java
/**
 * 宿主交互 API 演示与测试入口。
 * 逐项打印结果到 stdout（格式 "key=value"），供 tests/test_host_interaction.py 校验。
 */
public static void hostTest() {
    GSInterpreter interpreter = new GSInterpreter();
    interpreter.addVariableToGlobal("console", new Console());

    // 1. evalScript + getVariable
    interpreter.evalScript("var x = 10; var y = 20; function add(a, b) { return a + b; }");
    System.out.println("x=" + interpreter.getVariable("x").toIntValue());
    System.out.println("y=" + interpreter.getVariable("y").toIntValue());

    // 2. evalExpression（调用上面定义的 add）
    GSValue sum = interpreter.evalExpression("add(x, y)");
    System.out.println("sum=" + sum.toIntValue());

    // 3. setVariable 注入 Java 值 + gscript 读取
    interpreter.setVariable("z", 100);
    interpreter.setVariable("greeting", "hello");
    interpreter.evalScript("console.log(z); console.log(greeting);");

    // 4. toJavaObject：对象 + 嵌套数组
    GSValue obj = interpreter.evalExpression("{name: \"Alice\", age: 30, scores: [90, 85, 95]}");
    @SuppressWarnings("unchecked")
    Map<String, Object> javaObj = (Map<String, Object>) obj.toJavaObject();
    System.out.println("obj.name=" + javaObj.get("name"));
    System.out.println("obj.age=" + javaObj.get("age"));
    System.out.println("obj.scores=" + javaObj.get("scores"));

    // 5. fromJavaObject：注入 Map/List + gscript 访问
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("timeout", 5000);
    config.put("retries", 3);
    List<Integer> tags = Arrays.asList(1, 2, 3);
    interpreter.setVariable("config", config);
    interpreter.setVariable("tags", tags);
    GSValue timeout = interpreter.evalExpression("config.timeout");
    GSValue retries = interpreter.evalExpression("config.retries");
    GSValue tagCount = interpreter.evalExpression("tags.length");  // 若 length 不支持则用 tags[0]
    System.out.println("config.timeout=" + timeout.toIntValue());
    System.out.println("config.retries=" + retries.toIntValue());

    // 6. 表达式出错 → GSNull.NULL
    GSValue err = interpreter.evalExpression("null.foo");
    System.out.println("err_is_null=" + (err == GSNull.NULL));
}
```

**注意**：`tags.length` 若 gscript 不支持 `.length` 属性，运行时会返回 GSNull（getProperty 返回 NULL），toIntValue 返回 0。测试对此宽松断言。若需稳定，改为 `tags[0]`。实现时优先验证 `.length` 是否可用，不可用则改用索引访问。

---

### 文件 4: `tests/test_host_interaction.py`（新增）

Python 端运行 `java -cp target/classes org.gscript.TestScript hosttest`，校验 stdout 中各 `key=value` 行。

预期输出（关键断言）：
```
x=10
y=20
sum=30
config.timeout=5000
config.retries=3
err_is_null=True
```
console 输出（stdout）：
```
100
hello
```
`obj.scores` 应为 `[90, 85, 95]`（Java ArrayList 的 toString）。

## Assumptions & Decisions

1. **不修改** GSFunction / GSEnv / GSObject / GSArray / Lexer / Parser / ByteCodeGenerator / DebugController / DapServer —— 复用现有 eval 链与作用域机制。
2. **`evalExpression` 不复用 `evalScript`**：因为 `evalScript`→`eval(byte[][],...)` 会吞掉 GSException，无法区分成功/失败。改为直接调 `eval(frame, null)` + 自己 catch。
3. **`stack.clear()` 在 evalExpression 开头**：top-level expression statement 会往栈留值，不清空则可能 pop 到旧值。evalExpression 是宿主 API，调用时解释器空闲，清空栈安全。
4. **`getVariable` 用 `global.getVariableValue`**：已确认 top-level var 直接落 global（ProgramNode 不 emit pushenv）。
5. **`toJavaObject` 对 function(6)/native(9) 原样返回 `this`**：GSFunction 无法有意义地转为 Java 对象，保留引用便于宿主持有。
6. **`fromJavaObject` 支持 Double→Float**：gscript 浮点统一用 float（GSFloat），Double 需收窄。
7. **`hosttest` 模式加到 TestScript**：与现有 run/dump/compile/rungclass/dumpgclass 模式一致，Python 测试复用既有运行框架。
8. **顶层 `return` 合法**：EBNF `Program={Statement}`，`ReturnStatement` 属于 `Statement`，Parser 接受顶层 return。

## Verification Steps

1. `mvn -q clean compile` —— 编译通过
2. `python tests/test_host_interaction.py` —— 全部断言通过：
   - evalScript 执行语句 + getVariable 读取 x/y
   - evalExpression 调用 add(x,y) 返回 30
   - setVariable 注入 z/greeting + gscript console.log 输出
   - toJavaObject 递归转换对象 {name, age, scores:[...]}
   - fromJavaObject 包装 Map/List + gscript 读取 config.timeout/retries
   - 表达式出错（null.foo）返回 GSNull.NULL
3. 回归测试（确保新增方法不影响现有 eval 流程）：
   - `python tests/test_gclass.py`（56 项）
   - `python tests/test_dap_e2e.py`（17 项）
   - `python tests/test_multi_file.py`（19 项）
   - `python tests/test_while_breakpoint.py`（9 项）
   - `python tests/test_step_catch.py`（7 项）
4. 更新 `README.md`：在合适位置新增 "宿主交互 API" 小节，说明 6 个方法的用法与示例。
5. 更新记忆文件 `project_memory.md` + `topics.md`。
