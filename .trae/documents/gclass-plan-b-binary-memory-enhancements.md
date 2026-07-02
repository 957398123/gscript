# Plan B: 二进制内存表示 + gclass 格式增强

## Summary

将 gscript 解释器的内存表示从 `String[][]`（文本指令数组）升级为 `byte[][]`（二进制指令数组）+ `Object[]` 常量池，解释器主循环从 `switch(String command)` 改为 `switch(byte opcode)`。同时增强 gclass 文件格式：新增属性段（Attributes Section）机制、RLE 压缩源码映射。

**核心洞察**：gclass 文件格式**已经是二进制的**（GSClassWriter 已将指令编码为 opcode byte + operand bytes）。当前 GSClassReader 读取二进制后又转回 `String[][]` 供解释器使用。Plan B 只是**消除 String[][] 中间层**——让 Reader 读出的 `byte[][]` 直接用于执行。

**调试器零影响**：经探查验证，DebugController 和 DapServer.handleStackTrace **从不访问 `function.src`**，仅使用 `sourceLines`、`baseOffset`、`sourcePath`、`name`。IP→行号映射公式 `sourceLines[baseOffset + ip - 1]` 与字节码格式无关。

***

## Current State Analysis（经 Phase 1 探查验证）

### 已完成（Plan A）

gclass 序列化/反序列化层已完整实现：
- `GSClassConstants` — 37 个操作码 + 子操作码 + 双向映射表
- `GSClassWriter` — 文本字节码 → .gclass 二进制（含 CP 去重、CRC32）
- `GSClassReader` — .gclass → `String[][]` + `int[]` + `String`（当前转回 String[][]）
- `GSClassData` — 数据容器（`String[][] src`）
- `GSInterpreter.eval(String[][], int[], String)` — 2D 重载
- `TestScript` — compile/rungclass/dumpgclass CLI
- `DapServer` — .gclass 缓存加载
- 19 项测试全部通过

### 当前数据流（Plan A）

```
.script → Lexer → Parser → ByteCodeGenerator → ArrayList<String> (1D 文本)
                                                      ↓
                                           [GSClassWriter 序列化]
                                                      ↓
                                                   .gclass 文件
                                                      ↓
                                           [GSClassReader 反序列化]
                                                      ↓
                                           String[][] (2D 文本) ← 中间层！
                                                      ↓
                                           GSFunction.src (String[][])
                                                      ↓
                                           switch(String command) ← String 比较
```

### 目标数据流（Plan B）

```
.script → Lexer → Parser → ByteCodeGenerator → ArrayList<String> (1D 文本)
                                                      ↓
                                           [BytecodeEncoder 编码]
                                                      ↓
                                           byte[][] + Object[] cp ← 二进制内存
                                                      ↓
                                           GSFunction.src (byte[][])
                                           GSFunction.constantPool (Object[])
                                                      ↓
                                           switch(byte opcode) ← byte 比较，无字符串解析

.gclass 文件 ← GSClassWriter 直接写 byte[][] + cp（无需文本中间层）
.gclass 文件 → GSClassReader 直接读 byte[][] + cp（无需转 String[][]）
```

### 所有引用 `String[][]` / `function.src` 的位置（完整清单）

| 文件 | 行 | 用法 | Plan B 改动 |
|---|---|---|---|
| `GSFunction.java` | 20 | `public String src[][];` | → `public byte[][] src;` + 新增 `public Object[] constantPool;` |
| `GSFunction.java` | 55 | 构造器 `GSFunction(String, String[][], GSEnv)` | → `GSFunction(String, byte[][], Object[], GSEnv)` |
| `GSFrame.java` | 54 | `return this.function.src[ip];` 返回 `String[]` | → 返回 `byte[]` |
| `GSFrame.java` | 88 | `ip >= this.function.src.length` | 无需改（数组长度访问与类型无关） |
| `GSInterpreter.java` | 78 | `String[] codes = frame.getCode();` | → `byte[] codes = frame.getCode();` |
| `GSInterpreter.java` | 88-558 | `switch(command)` 32 个 case | → `switch(opcode)` 37 个 case（含 5 个 const_*） |
| `GSInterpreter.java` | 287-289 | fundef 切片 `String[][]` | → 切片 `byte[][]`，共享 cp 引用 |
| `GSInterpreter.java` | 607-633 | `eval(String[], int[], String)` | → 内部调用 BytecodeEncoder 后调 `eval(byte[][], Object[], int[], String)` |
| `GSInterpreter.java` | 650-663 | `eval(String[][], int[], String)` | → `eval(byte[][], Object[], int[], String)` |
| `GSClassData.java` | 19,27 | `String[][] src` | → `byte[][] src` + `Object[] constantPool` |
| `GSClassReader.java` | 125-128 | 读指令转为 `String[][]` | → 读原始字节到 `byte[][]`（用指令长度表） |
| `GSClassWriter.java` | 65,122,162,217 | 文本→String[][]→写二进制 | → 用 BytecodeEncoder 直接得 byte[][] + cp |
| `TestScript.java` | 161 | `String[] code = data.src[i]` (dump) | → 用 BytecodeDecoder 还原为文本显示 |
| `TestScript.java` | 86,89 | `eval(src.toArray(new String[0]))` | → 编码后调新 eval |
| `TestScript.java` | 145 | `eval(data.src, data.sourceLines, data.sourcePath)` | → 传 byte[][] + cp |
| `DapServer.java` | 92 | `List<String[][]> compiledBytecodes` | → `List<byte[][]>` + `List<Object[]> compiledCPs` |
| `DapServer.java` | 465,728,746,763,801 | splitBytecode / eval 调用 | → 用 BytecodeEncoder 替代 splitBytecode |

### 调试器不受影响的部分（探查确认）

- `DebugController` — 仅用 `sourceLines`、`baseOffset`、`sourcePath`，**从不访问 `src`**
- `DapServer.handleStackTrace` — 用 `name`、`sourcePath`、`currentLine(frame)`，不碰 `src`
- `DapServer.handleScopes` — 用 `frame.function.env`（运行时作用域），不碰 `src`
- IP→行号映射 `sourceLines[baseOffset + ip - 1]` — `ip` 是指令索引，与指令编码格式无关

***

## 设计

### 1. 指令二进制编码（每条指令 = 一个 byte[]）

与 gclass 文件中的编码完全一致（GSClassWriter 已实现），只是保持在内存中不转 String：

| 指令 | byte[] 布局 | 长度 |
|---|---|---|
| nop/lda_null/lda_nan/copy/copy2/swap/pop/getfield/putfield/return/throw/try_end/finally_check/incr/decr | [opcode] | 1 |
| arith_op/comp/rela_op/pushenv/popenv/new | [opcode, sub] | 2 |
| const_a/const_i/const_f/const_s/const_b/declare/store/jump/false_jump/loop_jump/block_jump/invoke/constructor | [opcode, u2 operand] | 3 |
| fundef/fstore | [opcode, u2, u2] | 5 |
| try_start | [opcode, s2×4] | 9 |

### 2. 指令长度表

新增 `GSClassConstants.instructionLength(byte opcode)` 静态方法，返回每条指令的总字节数（含 opcode）。用于：
- `GSClassReader`：读取时按长度复制原始字节到 `byte[]`
- `BytecodeDecoder`：dump 时按长度解析

### 3. BytecodeEncoder（新类）

**职责**：将 `ArrayList<String>` 1D 文本字节码 + `ArrayList<Integer>` 源码行号 编码为 `byte[][]` + `Object[]` 常量池。

**提取自** `GSClassWriter` 的 `parseBytecode` + `preCollectConstants` + `writeInstruction` 逻辑，但输出到内存而非流。

```java
public class BytecodeEncoder {
    private List<Object> cpList = new ArrayList<>();      // 1-based，索引 0 不用
    private Map<String, Integer> cpIndex = new LinkedHashMap<>();

    public EncodedBytecode encode(List<String> bytecode) {
        byte[][] instructions = new byte[bytecode.size()][];
        for (int i = 0; i < bytecode.size(); i++) {
            String text = bytecode.get(i);
            String[] parts = parseInstruction(text);  // const 特殊解析
            instructions[i] = encodeInstruction(parts);
        }
        Object[] cp = cpList.toArray(new Object[0]);
        return new EncodedBytecode(instructions, cp);
    }
    // addUtf8/addInt/addFloat/addBool — 与 GSClassWriter 相同的去重逻辑
}
```

**`EncodedBytecode`** — 简单容器：`byte[][] instructions` + `Object[] constantPool`

### 4. BytecodeDecoder（新类，调试/dump 用）

**职责**：将 `byte[]` 指令还原为文本形式，供 dump 显示。

```java
public class BytecodeDecoder {
    public static String decode(byte[] code, Object[] cp) {
        byte opcode = code[0];
        // 按 opcode 查表还原文本（与 GSClassReader.readInstruction 逻辑相同，
        // 但输出 String 而非 String[]）
    }
}
```

### 5. GSFunction 变更

```java
public class GSFunction extends GSObject {
    public String name;
    public GSEnv env;
    public byte[][] src;                    // ← 从 String[][] 改为 byte[][]
    public Object[] constantPool;           // ← 新增：常量池引用（同文件所有函数共享）
    public int baseOffset = 0;
    public int[] sourceLines = null;
    public String sourcePath = null;

    public GSFunction(String name, byte[][] src, Object[] constantPool, GSEnv env) {
        this.type = 6;
        this.name = name;
        this.src = src;
        this.constantPool = constantPool;
        this.env = env;
    }
    // ... 其余方法不变
}
```

### 6. GSFrame.getCode() 变更

```java
public byte[] getCode() {          // ← 返回 byte[] 而非 String[]
    return this.function.src[ip];
}
```

### 7. GSInterpreter.eval 主循环重写

从 `switch(String command)` 改为 `switch(byte opcode)`。所有操作数读取从 `codes[1]`（String）+ `Integer.parseInt` 改为直接字节读取：

```java
byte[] codes = frame.getCode();
frame.incrIP();
byte opcode = codes[0];
if (debugController != null) {
    debugController.suspendCheck(frame, callStack.size());
}
try {
    switch (opcode) {
        case GSClassConstants.OP_CONST_I: {
            int cpIdx = ((codes[1] & 0xFF) << 8) | (codes[2] & 0xFF);
            int value = (Integer) frame.function.constantPool[cpIdx];
            stack.push(new GSInt(value));
            break;
        }
        case GSClassConstants.OP_CONST_S: {
            int cpIdx = ((codes[1] & 0xFF) << 8) | (codes[2] & 0xFF);
            String value = (String) frame.function.constantPool[cpIdx];
            stack.push(new GSString(value));
            break;
        }
        case GSClassConstants.OP_JUMP: {
            short offset = (short) ((codes[1] << 8) | (codes[2] & 0xFF));
            int ip = frame.getIP() + offset - 1;
            frame.setIp(ip);
            break;
        }
        case GSClassConstants.OP_FUNDEF: {
            int nameCpIdx = ((codes[1] & 0xFF) << 8) | (codes[2] & 0xFF);
            int len = ((codes[3] & 0xFF) << 8) | (codes[4] & 0xFF);
            String name = (String) frame.function.constantPool[nameCpIdx];
            int ip = frame.getIP();
            GSEnv env = frame.function.getEnv();
            byte[][] src = new byte[len][];
            System.arraycopy(frame.function.src, ip, src, 0, len);
            GSFunction function = new GSFunction(name, src, frame.function.constantPool, env);
            function.sourceLines = frame.function.sourceLines;
            function.sourcePath = frame.function.sourcePath;
            function.baseOffset = frame.function.baseOffset + ip;
            stack.push(function);
            frame.setIp(ip + len);
            break;
        }
        // ... 其余 32 个 case，模式相同
    }
} catch (GSException e) { ... }
```

**操作数读取辅助**（可选，提高可读性）：
```java
private static int readU2(byte[] code, int offset) {
    return ((code[offset] & 0xFF) << 8) | (code[offset + 1] & 0xFF);
}
private static short readS2(byte[] code, int offset) {
    return (short) ((code[offset] << 8) | (code[offset + 1] & 0xFF));
}
```

### 8. eval 重载更新

```java
// 新主入口：二进制内存表示
public void eval(byte[][] codes, Object[] cp, int[] sourceLines, String sourcePath) {
    GSFunction anonymous = new GSFunction("null", codes, cp, global);
    anonymous.sourceLines = sourceLines;
    anonymous.sourcePath = sourcePath;
    anonymous.baseOffset = 0;
    GSFrame frame = new GSFrame(anonymous);
    try { eval(frame, null); }
    catch (DebugAbortException e) { }
    catch (GSException e) { System.out.println(...); }
}

// 兼容旧入口：1D 文本 → 编码 → 调新入口
public void eval(String[] src, int[] sourceLines, String sourcePath) {
    List<String> bc = Arrays.asList(src);
    BytecodeEncoder encoder = new BytecodeEncoder();
    EncodedBytecode encoded = encoder.encode(bc);
    eval(encoded.instructions, encoded.constantPool, sourceLines, sourcePath);
}

// 最简入口（无源码映射）
public void eval(String[] src) {
    eval(src, null, null);
}
```

### 9. GSClassReader 变更

读取时保持二进制，不转 String[][]：

```java
// 读取字节码段：按指令长度表读原始字节
byte[][] src = new byte[bytecodeLength][];
for (int i = 0; i < bytecodeLength; i++) {
    byte opcode = dis.readByte();
    int len = GSClassConstants.instructionLength(opcode);
    byte[] bytes = new byte[len];
    bytes[0] = opcode;
    dis.readFully(bytes, 1, len - 1);
    src[i] = bytes;
}
// 常量池仍然读为 Object[]（解释器运行时按 CP 索引查值）
```

### 10. GSClassWriter 变更

用 BytecodeEncoder 编码，直接写 byte[][] 到流：

```java
public void write(List<String> bytecode, List<Integer> sourceLines, String sourcePath, OutputStream out) {
    BytecodeEncoder encoder = new BytecodeEncoder();
    EncodedBytecode encoded = encoder.encode(bytecode);
    byte[][] instructions = encoded.instructions;
    Object[] cp = encoded.constantPool;
    // ... 写 header / CP / bytecode（直接写 byte[]）/ source map / CRC32
}
```

### 11. gclass 格式增强

#### 11a. 属性段机制（Attributes Section）

新增 flags 位：
- bit2 (0x0004) = SourceMap 使用 RLE 压缩编码
- bit3 (0x0008) = 有 Attributes 段

**Attributes 段格式**（位于 Source Map 之后）：
```
u2 attribute_count
for each attribute:
    u2 name_cp_index     (属性名，如 "FunctionTable")
    u4 data_length       (属性数据长度)
    bytes[data_length]   (属性数据)
```

#### 11b. RLE 压缩源码映射

当前：每条指令 u2 行号（N 条指令 = 2N 字节）
RLE：连续相同行号合并为一个条目

```
u4 entry_count
for each entry:
    u2 line_number       (源码行号)
    u2 repeat_count      (连续多少条指令共享此行号)
```

典型脚本 100 条指令 / 20 个不同行号：当前 200 字节 → RLE 80 字节（节省 60%）。

**向后兼容**：bit2=0 时仍用旧格式（u2 per instruction），新 reader 同时支持两种。

#### 11c. 函数表属性（FunctionTable Attribute）

属性名：`"FunctionTable"`
数据格式：
```
u2 function_count
for each function:
    u2 name_cp_index       (函数名)
    u2 start_ip            (函数体在字节码中的起始 IP)
    u2 body_length         (函数体指令数)
    u2 param_count         (参数个数)
```

**价值**：
- 调试器可快速枚举文件中的所有函数（不需扫描字节码找 fundef）
- 为未来模块/类系统铺路
- 仅作为可选属性，不影响执行

### 12. 版本号

- Major version 保持 1（向后兼容）
- Minor version 升为 1（新增可选属性段 + RLE 源码映射）
- Reader 同时支持 v1.0 和 v1.1

***

## 实现步骤

### 第 1 步：指令长度表

**修改** `GSClassConstants.java`
- 新增 `instructionLength(byte opcode)` 静态方法，返回每条指令总字节数

### 第 2 步：BytecodeEncoder + EncodedBytecode

**新建** `src/main/java/org/gscript/compile/gclass/BytecodeEncoder.java`
**新建** `src/main/java/org/gscript/compile/gclass/EncodedBytecode.java`
- 从 GSClassWriter 提取编码逻辑（parseBytecode + preCollectConstants + writeInstruction）
- 输出 `byte[][]` + `Object[]` 到内存

### 第 3 步：BytecodeDecoder

**新建** `src/main/java/org/gscript/compile/gclass/BytecodeDecoder.java`
- `decode(byte[] code, Object[] cp)` → `String`（文本形式，供 dump 显示）

### 第 4 步：GSFunction 改为二进制

**修改** `GSFunction.java`
- `src`: `String[][]` → `byte[][]`
- 新增 `constantPool`: `Object[]`
- 构造器签名变更

### 第 5 步：GSFrame.getCode() 改为 byte[]

**修改** `GSFrame.java`
- `getCode()` 返回 `byte[]`

### 第 6 步：重写 GSInterpreter.eval 主循环

**修改** `GSInterpreter.java`
- 主循环 `switch(String)` → `switch(byte)`
- 37 个 case 分支全部改写
- fundef 切片 `byte[][]` + 共享 cp
- 更新所有 eval 重载（新增 `eval(byte[][], Object[], int[], String)`，旧 `eval(String[])` 内部编码后调用新方法）

### 第 7 步：GSClassReader 改为读 byte[][]

**修改** `GSClassReader.java`
- 读取字节码段：按指令长度表读原始字节到 `byte[][]`
- 不再转为 `String[][]`
- 支持 RLE 源码映射（bit2 flag）

### 第 8 步：GSClassWriter 改为用 BytecodeEncoder

**修改** `GSClassWriter.java`
- 用 BytecodeEncoder 编码
- 直接写 `byte[][]` 到流
- 支持 RLE 源码映射写出
- 支持属性段写出

### 第 9 步：GSClassData 改为 byte[][]

**修改** `GSClassData.java`
- `src`: `String[][]` → `byte[][]`
- 新增 `constantPool`: `Object[]`

### 第 10 步：TestScript 更新

**修改** `TestScript.java`
- `gen()` — 编码后调新 eval
- `compileGclass()` — 用 GSClassWriter（内部用 BytecodeEncoder）
- `runGclass()` — 传 byte[][] + cp 到新 eval
- `dumpGclass()` — 用 BytecodeDecoder 还原为文本显示

### 第 11 步：DapServer 更新

**修改** `DapServer.java`
- `compiledBytecodes`: `List<String[][]>` → `List<byte[][]>`
- 新增 `compiledCPs`: `List<Object[]>`
- `splitBytecode()` 删除，改用 `BytecodeEncoder.encode()`
- `compileScript()` — .script 分支用 BytecodeEncoder，.gclass 分支用 GSClassReader
- `startInterpreterThread()` — 调 `eval(byte[][], Object[], int[], String)`
- `handleConfigurationDone` — 遍历 `byte[][]`

### 第 12 步：gclass 格式增强

**修改** `GSClassConstants.java`
- 新增 flags: `FLAG_RLE_SOURCE_MAP = 0x0004`, `FLAG_HAS_ATTRIBUTES = 0x0008`
- 新增 minor version = 1

**修改** `GSClassWriter.java`
- 写 RLE 源码映射（bit2 flag）
- 写属性段（bit3 flag）— FunctionTable 属性

**修改** `GSClassReader.java`
- 读 RLE 源码映射（bit2 flag）
- 读属性段（bit3 flag）

### 第 13 步：清理 + 测试

- 删除 `src/main/resources/multi_a.gclass`（Plan A 遗留临时文件）
- 更新 `test_gclass.py` — dump 对比改用 BytecodeDecoder
- 新增性能对比测试（String[][] vs byte[][] 执行时间）
- 运行全部回归测试

***

## 关键文件清单

### 新建（3 个）

| 文件 | 说明 |
|---|---|
| `src/main/java/org/gscript/compile/gclass/BytecodeEncoder.java` | 文本字节码 → byte[][] + Object[] cp（内存编码器） |
| `src/main/java/org/gscript/compile/gclass/EncodedBytecode.java` | 编码结果容器（byte[][] + Object[]） |
| `src/main/java/org/gscript/compile/gclass/BytecodeDecoder.java` | byte[] → String（dump/调试显示用） |

### 修改（8 个）

| 文件 | 改动 |
|---|---|
| `GSClassConstants.java` | +instructionLength()、+新 flags、+minor version |
| `GSFunction.java` | src: String[][]→byte[][]、+constantPool 字段、构造器签名 |
| `GSFrame.java` | getCode() 返回 byte[] |
| `GSInterpreter.java` | 主循环 switch(byte)、37 个 case 重写、eval 重载更新 |
| `GSClassReader.java` | 读 byte[][]、RLE 源码映射、属性段 |
| `GSClassWriter.java` | 用 BytecodeEncoder、RLE 源码映射、属性段 |
| `GSClassData.java` | src: String[][]→byte[][]、+constantPool |
| `TestScript.java` | gen/compileGclass/runGclass/dumpGclass 更新 |
| `DapServer.java` | compiledBytecodes→byte[][]、splitBytecode→BytecodeEncoder |

### 不改动

- `DebugController.java` — 零影响（不访问 src）
- `GSFrame.java` 的异常处理逻辑 — 不变
- `GSEnv.java` — 不变
- `GSExceptionMonitor.java` — 不变
- 所有 `GSValue` 子类 — 不变
- `ByteCodeGenerator.java` — 不变（仍输出 ArrayList<String>，BytecodeEncoder 负责转换）
- `Lexer.java` / `Parser.java` / AST 节点 — 不变
- VSCode 扩展 — 不变

***

## Assumptions & Decisions

| 决策点 | 选择 | 理由 |
|---|---|---|
| 内存表示 | `byte[][]`（每条指令一个 byte[]） | 与现有 `String[][]` 结构对齐，改动最小；fundef 切片逻辑不变 |
| 常量池位置 | `GSFunction.constantPool` 字段，同文件函数共享引用 | 子函数通过 fundef 继承父函数的 cp 引用，无需复制 |
| 编码入口 | BytecodeEncoder 独立类，ByteCodeGenerator 不变 | 最小化改动；Generator 仍输出文本，编码是独立后续阶段 |
| 指令边界 | 按操作码查长度表（`instructionLength()`） | 无需存储偏移量表；查表 O(1) |
| gclass 版本 | Major=1, Minor=1 | 向后兼容；Reader 同时支持 v1.0 和 v1.1 |
| RLE 源码映射 | 可选（bit2 flag） | 旧文件仍用绝对 u2 格式；新文件默认 RLE |
| 属性段 | 可选（bit3 flag），FunctionTable 为首个属性 | 可扩展机制，为未来特性铺路 |
| dump 显示 | BytecodeDecoder 还原为文本 | 保留 dump 模式的可读性，便于调试和测试对比 |
| 旧 eval(String[]) | 保留，内部编码后调新方法 | 向后兼容 TestScript.gen 等现有调用 |
| ByteCodeGenerator | 不改动 | 仍输出 ArrayList<String>，编码由 BytecodeEncoder 负责 |

***

## 验证方案

1. **编译验证**：`mvn -q compile` + `mvn -q package`
2. **dump 一致性**：`TestScript <name> dump`（文本直编译）与 `TestScript <name> dumpgclass`（gclass 往返）输出完全一致
3. **执行一致性**：`TestScript <name>` 与 `TestScript <name> rungclass` 输出完全一致
4. **字符串空格**：`const s "hello world"` 在 byte[][] 内存中正确保留
5. **CRC32 校验**：篡改 .gclass 文件后拒绝加载
6. **RLE 源码映射**：v1.1 文件用 RLE 格式，v1.0 文件用绝对格式，两者 dump 结果一致
7. **属性段**：FunctionTable 属性正确记录所有函数
8. **回归测试**：
   - `test_gclass.py`（序列化/反序列化/执行一致性）
   - `test_dap_e2e.py`（17/17 DAP 端到端）
   - `test_multi_file.py`（19/19 多文件调试）
   - `test_cross_file_step.py`（7/7 跨文件单步）
   - `test_while_breakpoint.py`（9/9 循环断点）
   - `semantics_test.script`（120+ 语义测试）
9. **性能对比**：相同脚本在 String[][] 和 byte[][] 下的执行时间对比（预期 byte[][] 更快）
