# Plan B 完成计划：二进制内存表示 + gclass 格式增强

> **状态**：Steps 1-5 已完成（代码处于不可编译状态），本计划完成剩余 Steps 6-13。
> **前置**：用户已在上一会话批准 "Plan B + 格式增强" 方向。

## 一、当前状态分析

### 已完成（Steps 1-5）
| 文件 | 改动 |
|------|------|
| `GSClassConstants.java` | MINOR_VERSION=1, 新增 FLAG_RLE_SOURCE_MAP/FLAG_HAS_ATTRIBUTES/ATTR_FUNCTION_TABLE, `instructionLength(byte)` 方法 |
| `EncodedBytecode.java` | 新建：`byte[][] instructions` + `Object[] constantPool` 容器 |
| `BytecodeEncoder.java` | 新建：文本 `List<String>` → `byte[][]` + `Object[]` CP（含 CP 去重） |
| `BytecodeDecoder.java` | 新建：`byte[]` + CP → 文本（dump 用） |
| `GSFunction.java` | `src` 改为 `byte[][]`，新增 `constantPool` 字段，构造器改为 `(name, byte[][]src, Object[]cp, env)` |
| `GSFrame.java` | `getCode()` 返回 `byte[]` |

### 未完成（代码不可编译）
| 文件 | 问题 |
|------|------|
| `GSInterpreter.java` | 仍用 `String[] codes`/`String command`/`switch(command)`/旧构造器 → **编译失败** |
| `GSClassReader.java` | `readInstruction()` 返回 `String[]`，`src` 为 `String[][]` |
| `GSClassWriter.java` | 重复的 CP + 指令编码逻辑，从 `String[]` 写入 |
| `GSClassData.java` | `src` 为 `String[][]`，无 `constantPool` |
| `TestScript.java` | 用 `data.src` (String[][])，dumpGclass 手工重建文本 |
| `DapServer.java` | `compiledBytecodes` 为 `List<String[][]>`，有 `splitBytecode()` |
| 格式增强 | RLE/attributes/FunctionTable 常量已加，逻辑未实现 |

### 关键约束
- **调试器零影响**：`DebugController` 不访问 `function.src`/`constantPool`，只用 `sourceLines`/`baseOffset`/`sourcePath`/`name`。IP→行映射 `sourceLines[baseOffset + ip - 1]` 与字节码格式无关（ip 是指令索引）。
- **CP 索引**：1-based（索引 0 不用），同文件函数共享 constantPool 引用（fundef 切片继承）。
- **37 个操作码**（0x00-0x24）：const 拆为 5 个（const_a/i/f/s/b），固定指令长度 1/2/3/5/9 字节。

---

## 二、实施计划

### Part A：完成 byte[][] 迁移（恢复编译 + 全测试通过）

#### Step 6：重写 GSInterpreter.eval 主循环【最关键】

**文件**：`src/main/java/org/gscript/vm/GSInterpreter.java`

**6.1 添加 import**
```java
import org.gscript.compile.gclass.GSClassConstants;
import org.gscript.compile.gclass.BytecodeEncoder;
import org.gscript.compile.gclass.EncodedBytecode;
```

**6.2 添加辅助方法**（类内私有）
```java
private static int readU2(byte[] code, int off) {
    return ((code[off] & 0xFF) << 8) | (code[off + 1] & 0xFF);
}
private static short readS2(byte[] code, int off) {
    return (short) ((code[off] << 8) | (code[off + 1] & 0xFF));
}
```

**6.3 重写 eval(GSFrame, args) 主循环体**（行 76-558）

核心改动：
- `String[] codes = frame.getCode()` → `byte[] codes = frame.getCode()`
- `String command = codes[0]` → `byte opcode = codes[0]`
- `switch (command)` → `switch (opcode)`，case 标签改为 `GSClassConstants.OP_*`（byte）

各 case 操作数读取模式：
| 指令 | 操作数读取 |
|------|-----------|
| const_a/i/f/s/b | `cpIdx = readU2(codes,1)`；值从 `frame.function.constantPool[cpIdx]` 取，按类型构造 GSValue |
| arith_op/comp/rela_op/pushenv/popenv/new | `codes[1]`（子操作码 byte）→ 内部 switch |
| declare/store | `name = (String)cp[readU2(codes,1)]` |
| jump/false_jump/loop_jump/block_jump | `offset = readS2(codes,1)` |
| fundef | `name = (String)cp[readU2(codes,1)]`，`len = readU2(codes,3)` |
| fstore | `name = (String)cp[readU2(codes,1)]`，`argIdx = readU2(codes,3)` |
| invoke/constructor | `argCount = readU2(codes,1)` |
| try_start | 4 个 `readS2(codes, 1/3/5/7)` |
| 其余无操作数 | 直接执行 |

**const 值构造**（利用 GSValue 直接类型构造器，避免 String 转换）：
```java
case OP_CONST_A: temp = frame.function.getVariableFromScope((String)cp[cpIdx]); break;
case OP_CONST_I: temp = new GSInt((Integer)cp[cpIdx]); break;
case OP_CONST_F: temp = new GSFloat((Float)cp[cpIdx]); break;
case OP_CONST_S: temp = new GSString((String)cp[cpIdx]); break;
case OP_CONST_B: temp = GSBool.getGSBool((Boolean)cp[cpIdx]); break;
```

**fundef 切片改动**（行 282-298）：
```java
case OP_FUNDEF: {
    String name = (String) frame.function.constantPool[readU2(codes, 1)];
    int len = readU2(codes, 3);
    int ip = frame.getIP();
    GSEnv env = frame.function.getEnv();
    byte[][] src = new byte[len][];
    System.arraycopy(frame.function.src, ip, src, 0, len);
    // 子函数共享父函数的 constantPool 引用（同文件函数共享 CP）
    GSFunction function = new GSFunction(name, src, frame.function.constantPool, env);
    function.sourceLines = frame.function.sourceLines;
    function.sourcePath = frame.function.sourcePath;
    function.baseOffset = frame.function.baseOffset + ip;
    stack.push(function);
    frame.setIp(ip + len);
    break;
}
```

**6.4 重写 eval 入口方法**（行 607-682）

新增核心方法：
```java
public void eval(byte[][] codes, Object[] constantPool, int[] sourceLines, String sourcePath) {
    GSFunction anonymous = new GSFunction("null", codes, constantPool, global);
    anonymous.sourceLines = sourceLines;
    anonymous.sourcePath = sourcePath;
    anonymous.baseOffset = 0;
    GSFrame frame = new GSFrame(anonymous);
    try {
        eval(frame, null);
    } catch (DebugAbortException e) {
        // 调试会话终止
    } catch (GSException e) {
        System.out.println(String.format("Uncaught Error: %s at <anonymous>:%d",
            e.origin.toStringValue(), e.getIp()));
    }
}
```

旧入口转为编码后调用新方法：
- `eval(String[] src, int[], String)` → 用 `BytecodeEncoder` 编码后调 `eval(byte[][], Object[], int[], String)`
- `eval(String[] src)` / `eval(String[], int[])` → 委托上述
- **删除** `eval(String[][], int[], String)`（旧的 2D String 版本，被新 byte[][] 版本取代）

---

#### Step 7：更新 GSClassData

**文件**：`src/main/java/org/gscript/compile/gclass/GSClassData.java`

```java
public class GSClassData {
    public final byte[][] src;           // String[][] → byte[][]
    public final Object[] constantPool;  // 新增
    public final int[] sourceLines;
    public final String sourcePath;

    public GSClassData(byte[][] src, Object[] constantPool, int[] sourceLines, String sourcePath) {
        this.src = src;
        this.constantPool = constantPool;
        this.sourceLines = sourceLines;
        this.sourcePath = sourcePath;
    }
}
```

---

#### Step 8：重写 GSClassReader（读原始字节，不转 String）

**文件**：`src/main/java/org/gscript/compile/gclass/GSClassReader.java`

核心改动：
- **删除** `readInstruction(DataInputStream)` 方法（返回 String[] 的版本）
- **新增** `readRawInstruction(DataInputStream, byte opcode)` 方法：按 `instructionLength(opcode)` 读取固定字节数到 `byte[]`
- 字节码段解析改为：
```java
byte[][] src = new byte[bytecodeLength][];
for (int i = 0; i < bytecodeLength; i++) {
    byte opcode = dis.readByte();
    int len = GSClassConstants.instructionLength(opcode);
    byte[] instr = new byte[len];
    instr[0] = opcode;
    dis.readFully(instr, 1, len - 1);  // 读取剩余操作数字节
    src[i] = instr;
}
```
- `cp` 字段从 private 提升为方法返回值的一部分（传入 GSClassData）
- `deserialize()` 返回 `new GSClassData(src, cp, sourceLines, sourcePath)`

---

#### Step 9：重构 GSClassWriter（复用 BytecodeEncoder，消除重复逻辑）

**文件**：`src/main/java/org/gscript/compile/gclass/GSClassWriter.java`

核心改动：
- **删除** 内部 `CpEntry` 类、`cpEntries`/`cpIndex` 字段、`addUtf8/addInt/addFloat/addBool` 方法、`parseBytecode` 方法、`preCollectConstants` 方法、`writeInstruction` 方法（全部被 BytecodeEncoder 取代）
- **write() 方法重写**：
```java
public void write(List<String> bytecode, List<Integer> sourceLines, String sourcePath, OutputStream out) throws IOException {
    // 1. 用 BytecodeEncoder 编码（内存 byte[][] + Object[] CP）
    BytecodeEncoder encoder = new BytecodeEncoder();
    EncodedBytecode encoded = encoder.encode(bytecode);
    byte[][] instructions = encoded.instructions;
    Object[] cp = encoded.constantPool;

    // 2. sourcePath 加入 CP（需要单独处理，因为 sourcePath 不在指令流中）
    //    注意：BytecodeEncoder 的 CP 是 final 的，sourcePath 需在编码前或后追加
    //    方案：在 encoder 中暴露 addUtf8，或在 Writer 中重建 CP
    //    简化方案：Writer 仍维护自己的 sourcePath CP 追加逻辑
    ...
    // 3. 写 Header + CP + 字节码段（直接写 byte[]，不转换）+ SourceMap + CRC32
}
```

**sourcePath CP 处理方案**：BytecodeEncoder 的 CP 不含 sourcePath（它只编码指令中的常量）。Writer 需要把 sourcePath 追加到 CP 末尾。由于 BytecodeEncoder 的 CP 是 `Object[]`（不可变长度），Writer 需要复制扩展：
```java
int sourcePathCpIndex = 0;
if (sourcePath != null && !sourcePath.isEmpty()) {
    sourcePathCpIndex = cp.length;  // 追加到末尾，索引 = 当前长度
    Object[] newCp = new Object[cp.length + 1];
    System.arraycopy(cp, 0, newCp, 0, cp.length);
    newCp[sourcePathCpIndex] = sourcePath;
    cp = newCp;
}
```

**字节码段写入**（直接写 byte[]，无需逐指令编码）：
```java
for (byte[] instr : instructions) {
    dos.write(instr);
}
```

---

#### Step 10：更新 TestScript

**文件**：`src/main/java/org/gscript/TestScript.java`

- `runGclass()`：`interpreter.eval(data.src, data.constantPool, data.sourceLines, data.sourcePath)`
- `dumpGclass()`：用 `BytecodeDecoder.decode(data.src[i], data.constantPool)` 替代手工文本重建
- `loadGclass()` 日志不变
- `gen()` 的 `interpreter.eval(src.toArray(...))` 不变（String[] 入口仍保留，内部编码）

---

#### Step 11：更新 DapServer

**文件**：`src/main/java/org/gscript/vm/debug/dap/DapServer.java`

- `compiledBytecodes` 类型：`List<String[][]>` → `List<byte[][]>`
- 新增字段：`List<Object[]> compiledConstantPools`（与 compiledBytecodes 一一对应）
- `compileScript()`：
  - .gclass 加载：`compiledBytecodes.add(data.src)` + `compiledConstantPools.add(data.constantPool)`
  - .script 编译：用 `BytecodeEncoder` 替代 `splitBytecode()`，编码后存入两个列表
- **删除** `splitBytecode()` 方法
- `startInterpreterThread()`：
  - `List<byte[][]> bcs` + `List<Object[]> cps`
  - `interpreter.eval(bc, cp, sl, path)`

---

#### Step 12（验证点）：编译 + 运行全部测试

```bash
# 编译
mvn -q compile

# 运行 gclass 端到端测试（序列化/执行/CRC32/多脚本）
python tests/test_gclass.py

# 运行回归测试
python tests/test_dap_e2e.py
python tests/test_step_catch.py
python tests/test_while_breakpoint.py
python tests/test_multi_file.py
python tests/test_cross_file_step.py
```

**预期**：所有测试通过，输出与改动前一致（byte[][] 执行结果 == String[][] 执行结果）。

---

### Part B：gclass 格式增强（Part A 全部通过后）

#### Step 13：RLE 压缩源码映射

**目标**：源码行号数组常有长游程（多条字节码对应同一源码行），RLE 可显著减小文件体积。

**格式**（当 FLAG_RLE_SOURCE_MAP 置位时）：
```
u4 pairCount
pairCount × (u2 count, u2 line)
```
解码：对每对 (count, line)，展开 count 个 line。

**Writer 改动**（`GSClassWriter.write()`）：
- 编码 sourceLines 为 RLE 对
- 如果 RLE 对数 < 原始长度 × 0.75（压缩率 > 25%），设置 FLAG_RLE_SOURCE_MAP，写 RLE 格式
- 否则写原始格式（兼容 v1.0 reader）

**Reader 改动**（`GSClassReader.deserialize()`）：
- 检查 FLAG_RLE_SOURCE_MAP
- 若置位，读 pairCount 对，展开为 int[]
- 否则按原始格式读取

---

#### Step 14：Attributes 段 + FunctionTable 属性

**目标**：引入可扩展的属性段机制，首个属性 FunctionTable 记录所有函数元数据（名称/起始IP/体长），供调试器和工具链使用。

**格式**（当 FLAG_HAS_ATTRIBUTES 置位时，位于 SourceMap 之后）：
```
u2 attrCount
attrCount × attribute {
    u2 nameCpIndex    // UTF8 属性名
    u4 length          // 属性数据字节数
    byte[length] data  // 属性数据
}
```

**FunctionTable 属性数据格式**：
```
u2 funcCount
funcCount × {
    u2 nameCpIndex   // 函数名 UTF8
    u4 startIp        // 函数体在顶级字节码中的起始索引
    u4 bodyLen        // 函数体指令数
}
```

**Writer 改动**：
- 扫描 byte[][] 指令，找出所有 OP_FUNDEF 指令，提取 (nameCp, startIp, bodyLen)
- 构建 FunctionTable 属性数据
- 设置 FLAG_HAS_ATTRIBUTES，写属性段

**Reader 改动**：
- 检查 FLAG_HAS_ATTRIBUTES
- 若置位，读属性段，按 nameCpIndex 分发到对应属性解析器
- FunctionTable 解析后存入 GSClassData（新增 `FunctionEntry[] functions` 字段，可为 null）

**GSClassData 扩展**：
```java
public static class FunctionEntry {
    public final String name;
    public final int startIp;
    public final int bodyLen;
    // constructor + fields
}
public final FunctionEntry[] functions;  // 可为 null（无 attributes 段时）
```

---

#### Step 15（验证点）：格式增强测试

- `test_gclass.py` 增加：dump 与 dumpgclass 一致性仍通过（Decoder 透明处理 RLE/attributes）
- 新增验证：gclass 文件体积对比（RLE 压缩率）
- 新增验证：FunctionTable 正确性（函数名/位置匹配）

---

### Part C：清理

#### Step 16：清理 + 最终验证

- 删除临时文件 `multi_a.gclass`（如存在于 src/main/resources）
- 更新 `project_memory.md`（记录 Plan B 完成、gclass v1.1 格式）
- 运行全部测试套件确认无回归

---

## 三、假设与决策

| # | 决策 | 理由 |
|---|------|------|
| 1 | CP 索引 1-based（0 不用） | 与 Reader/Writer 现有设计一致，避免 0 索引歧义 |
| 2 | fundef 切片共享父函数 constantPool 引用 | 同文件函数共享 CP，避免复制开销；语义正确（同文件常量相同） |
| 3 | GSValue 用直接类型构造器（GSInt(int) 而非 GSInt(String)） | CP 中已是 Integer/Float/Boolean，避免 String 中间转换 |
| 4 | 保留 `eval(String[])` 入口 | TestScript.gen() 仍用 1D String 入口，内部编码后执行 |
| 5 | GSClassWriter 复用 BytecodeEncoder | 消除重复的 CP + 指令编码逻辑，单一数据源 |
| 6 | RLE 仅在压缩率 > 25% 时启用 | 避免小文件反而变大，向后兼容（flag 不置位则用旧格式） |
| 7 | Attributes 段位于 SourceMap 之后 | 可选段，不影响无属性文件的解析 |
| 8 | 不修改 ByteCodeGenerator | 生成器仍输出 `ArrayList<String>` 文本字节码，编码为 byte[][] 是独立步骤 |
| 9 | 不修改 DebugController | 调试器不访问 src/constantPool，零影响 |
| 10 | gclass 版本保持 1.1（MINOR_VERSION 已改） | RLE/attributes 是可选 flag，不改变主版本 |

---

## 四、文件变更清单

| 文件 | 操作 | Step |
|------|------|------|
| `vm/GSInterpreter.java` | 重写 eval 主循环 + 入口方法 | 6 |
| `gclass/GSClassData.java` | src→byte[][] + constantPool + FunctionEntry | 7, 14 |
| `gclass/GSClassReader.java` | 读原始字节 + RLE/attributes 解析 | 8, 13, 14 |
| `gclass/GSClassWriter.java` | 复用 BytecodeEncoder + RLE/attributes 写入 | 9, 13, 14 |
| `TestScript.java` | 适配新接口 + BytecodeDecoder | 10 |
| `vm/debug/dap/DapServer.java` | byte[][] + constantPool 列表 | 11 |
| `project_memory.md` | 记录完成状态 | 16 |

**不修改**：ByteCodeGenerator、Parser、Lexer、DebugController、GSFrame（已改）、GSFunction（已改）、GSClassConstants（已改）、BytecodeEncoder（已建）、BytecodeDecoder（已建）、EncodedBytecode（已建）

---

## 五、实施顺序

```
Step 6 (GSInterpreter)  ──┐
Step 7 (GSClassData)    ──┤
Step 8 (GSClassReader)  ──┼── Part A：恢复编译 + 测试通过
Step 9 (GSClassWriter)  ──┤
Step 10 (TestScript)    ──┤
Step 11 (DapServer)     ──┘
Step 12 (验证 Part A)   ───── 检查点：全测试通过
         │
Step 13 (RLE source map) ──┐
Step 14 (attributes)     ──┼── Part B：格式增强
Step 15 (验证 Part B)    ──┘
         │
Step 16 (清理 + 最终验证) ──── 完成
```

**Part A 完成后代码即可编译运行**；Part B 是纯增量增强，不影响已通过的功能。
