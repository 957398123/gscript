# gclass 二进制文件格式设计与实现计划

## Context

当前 gscript 的字节码以 `String[][]`（文本指令数组）形式存在于内存中，无法持久化或网络传输。编译产物只能以 `.gtxt`（人类可读文本 dump）落盘，且没有对应的加载器——每次运行都需从 `.script` 源码重新编译。

**目标**：设计类似 Java `.class` 的二进制 gclass 文件格式，包含字符串常量池（去重 + 解决 `const s` 空格问题）、可选源码行映射、源文件路径、CRC32 校验。解释器能直接加载并执行 gclass 文件。

**分两步实施**：

* **第一步（本次）**：方案 A — gclass 序列化/反序列化层，加载后还原为 `String[][]`，解释器主循环不改动

* **第二步（后续）**：方案 B — 升级为 `byte[][]` 二进制内存表示，解释器改为 `switch(opcode byte)`

本次只实施方案 A。

***

## Current State Analysis（经 Phase 1 探查验证）

### 现有字节码内存表示

* `ByteCodeGenerator`（ByteCodeGenerator.java:17-24）输出 **1D 文本** `ArrayList<String> bytecode` + 平行的 `ArrayList<Integer> sourceLines`。

* 指令格式为空格分隔的文本，如 `"const s hello world"`、`"arith_op plus"`、`"fundef add 10"`、`"invoke 2"`、`"try_start 5 10 -1 12"`。

* **关键陷阱**：`const s` 的值可能含空格（如 `"hello world"`），不能用 `split(" ")` 解析。`GSInterpreter.eval(String[], ...)`（GSInterpreter.java:611-617）用 `code.substring` 手工截取 type 字符和 value 来规避此问题。

### 现有执行链路

1. `TestScript.gen`（TestScript.java:31-67）：`/<name>.script` (classpath) → Lexer → Parser → ByteCodeGenerator → `eval(src.toArray())`
2. `DapServer.compileScript`（DapServer.java:709-727）：文件路径 → 读取 → Lexer → Parser → ByteCodeGenerator → 存入 `compiledBytecodes`/`compiledSourceLines`
3. `DapServer.startInterpreterThread`（DapServer.java:736-774）：循环 `interpreter.eval(bc, sl, path)` 每个文件
4. `GSInterpreter.eval(String[], int[], String)`（GSInterpreter.java:607-634）：1D→2D 拆分 → 创建匿名 GSFunction → `eval(frame, null)`

### GSFunction 可序列化字段（GSFunction.java）

| 字段            | 类型           | 序列化 | 说明                                  |
| ------------- | ------------ | --- | ----------------------------------- |
| `name`        | String       | ✅   | 函数名（匿名="null"）                      |
| `src`         | String\[]\[] | ✅   | 2D 字节码（fundef 内联子函数）                |
| `baseOffset`  | int          | ✅   | 子函数在顶级字节码中的偏移                       |
| `sourceLines` | int\[]       | ✅   | 顶级源码行映射（共享不切片）                      |
| `sourcePath`  | String       | ✅   | 源文件路径                               |
| `env`         | GSEnv        | ❌   | 运行时闭包，加载时重新绑定到 `interpreter.global` |
| `type`        | int          | ❌   | 运行时类型标记（固定=6）                       |

### fundef 内联机制（GSInterpreter.java:282-298）

`fundef <name> <len>` 后紧跟 `len` 条指令作为函数体。执行时切片 `src[ip..ip+len]` 创建子 GSFunction，子函数共享顶级 `sourceLines`，`baseOffset = parent.baseOffset + ip`。**gclass 保持此机制不变**——序列化时 fundef 体作为普通指令流写入，反序列化时还原为 String\[]\[] 自然保持内联结构。

***

## gclass 文件格式规范

### 整体结构

```
+============================+
|       File Header          |   20 字节固定
+============================+
|       Constant Pool        |   变长
+============================+
|       Bytecode Section     |   变长（指令流）
+============================+
|    Source Map (可选)       |   变长
+============================+
|    CRC32 Footer            |   4 字节
+============================+
```

所有多字节整数采用 **Big-Endian（大端序）**。

### File Header（20 字节）

| 偏移 | 长度 | 字段                    | 说明                                     |
| -- | -- | --------------------- | -------------------------------------- |
| 0  | 4  | magic                 | `0x4753434C`（ASCII "GSCL"）             |
| 4  | 1  | major\_version        | 主版本号 `1`                               |
| 5  | 1  | minor\_version        | 次版本号 `0`                               |
| 6  | 2  | flags                 | 位标志（bit0=有SourceMap, bit1=有SourcePath） |
| 8  | 2  | constant\_pool\_count | 常量池条目数（索引从1开始，0=无引用）                   |
| 10 | 4  | bytecode\_length      | 字节码段指令条数                               |
| 14 | 2  | source\_path\_index   | 源文件路径在常量池的索引，0=无                       |
| 16 | 4  | crc32                 | 整个文件（不含CRC自身）的 CRC32 校验                |

### Constant Pool

索引从 1 开始（0 保留）。每条以 1 字节 tag 开头：

| Tag    | 类型    | 编码                      | 用途                   |
| ------ | ----- | ----------------------- | -------------------- |
| `0x01` | UTF8  | u2 length + UTF-8 bytes | 字符串字面量、变量名、函数名、源文件路径 |
| `0x02` | Int   | s4 (4字节有符号)             | `const i` 的整数值       |
| `0x03` | Float | f4 (IEEE 754 单精度)       | `const f` 的浮点值       |
| `0x04` | Bool  | u1 (0=false, 1=true)    | `const b` 的布尔值       |

### Bytecode Section

每条指令：1 字节操作码 + 变长操作数。操作数类型：

* `u1`：子操作码或小计数（1字节无符号）

* `u2`：常量池索引或计数（2字节无符号）

* `s2`：跳转偏移量（2字节有符号，-32768\~32767）

### Source Map Section（可选，flags bit0=1 时存在）

| 偏移 | 长度        | 说明                                      |
| -- | --------- | --------------------------------------- |
| 0  | 4         | source\_line\_count（= bytecode\_length） |
| 4  | count × 2 | 每条指令对应 u2 源码行号（1-based，0=未设置）           |

### 操作码表（32 条原始指令 → const 拆分后共 37 条 → 1 字节二进制码）

经核对 `GSInterpreter.eval`（GSInterpreter.java:88-558）的 switch 分支，原始指令共 32 条（含 `const`）。
将 `const` 拆为 5 个独立操作码（省去类型 tag），`arith_op`/`comp`/`rela_op`/`pushenv`/`popenv`/`new` 保留主+子操作码结构，再加 `nop` 保留位，共 37 条：

| 操作码            | 值    | 操作数                       | 原 text 指令                                     |
| -------------- | ---- | ------------------------- | --------------------------------------------- |
| const\_a       | 0x01 | u2 cp\_index              | const a \<name>                               |
| const\_i       | 0x02 | u2 cp\_index              | const i \<value>                              |
| const\_f       | 0x03 | u2 cp\_index              | const f \<value>                              |
| const\_s       | 0x04 | u2 cp\_index              | const s \<value>（空格问题终结）                      |
| const\_b       | 0x05 | u2 cp\_index              | const b \<value>                              |
| lda\_null      | 0x06 | —                         | lda\_null                                     |
| lda\_nan       | 0x07 | —                         | lda\_nan                                      |
| arith\_op      | 0x08 | u1 sub\_op                | arith\_op plus/minus/mul/div/modulo/neg/ls/rs |
| comp           | 0x09 | u1 sub\_op                | comp eq/neq/seq/sneq/gt/ge/lt/le              |
| rela\_op       | 0x0A | u1 sub\_op                | rela\_op b\_and/b\_or/b\_xor/b\_not/l\_not    |
| copy           | 0x0B | —                         | copy                                          |
| copy2          | 0x0C | —                         | copy2                                         |
| swap           | 0x0D | —                         | swap                                          |
| pop            | 0x0E | —                         | pop                                           |
| getfield       | 0x0F | —                         | getfield                                      |
| putfield       | 0x10 | —                         | putfield                                      |
| declare        | 0x11 | u2 cp\_index              | declare \<name>                               |
| store          | 0x12 | u2 cp\_index              | store \<name>                                 |
| pushenv        | 0x13 | u1 sub\_op                | pushenv function/loop/block                   |
| popenv         | 0x14 | u1 sub\_op                | popenv loop/block                             |
| jump           | 0x15 | s2 offset                 | jump \<offset>                                |
| false\_jump    | 0x16 | s2 offset                 | false\_jump \<offset>                         |
| loop\_jump     | 0x17 | s2 offset                 | loop\_jump \<offset>                          |
| block\_jump    | 0x18 | s2 offset                 | block\_jump \<offset>                         |
| fundef         | 0x19 | u2 name\_cp, u2 body\_len | fundef \<name> \<len>（体紧跟其后）                  |
| fstore         | 0x1A | u2 name\_cp, u2 arg\_idx  | fstore \<name> \<index>                       |
| invoke         | 0x1B | u2 arg\_count             | invoke \<argCount>                            |
| constructor    | 0x1C | u2 arg\_count             | constructor \<argCount>                       |
| return         | 0x1D | —                         | return                                        |
| new            | 0x1E | u1 sub\_op                | new Object/Array                              |
| throw          | 0x1F | —                         | throw                                         |
| try\_start     | 0x20 | s2×4 offsets              | try\_start \<a> \<b> \<c> \<d>                |
| try\_end       | 0x21 | —                         | try\_end                                      |
| finally\_check | 0x22 | —                         | finally\_check                                |
| incr           | 0x23 | —                         | incr                                          |
| decr           | 0x24 | —                         | decr                                          |
| nop            | 0x00 | —                         | 保留                                            |

***

## 实现步骤（方案 A）

### 第1步：创建常量定义类

**新建** `src/main/java/org/gscript/compile/gclass/GSClassConstants.java`

定义所有操作码常量（`OP_CONST_A = 0x01` 等）、子操作码常量、常量池 tag 常量、文本名↔二进制码映射表（`Map<String, Byte>` 和反向 `Map<Byte, String>`）。

### 第2步：创建序列化器 GSClassWriter

**新建** `src/main/java/org/gscript/compile/gclass/GSClassWriter.java`

**输入**：`ArrayList<String> bytecode`（1D 文本指令）+ `ArrayList<Integer> sourceLines` + `String sourcePath`

**输出**：写入 `OutputStream`（或 `byte[]`）

**核心逻辑**：

1. 解析 1D 文本为 2D 指令数组（**复用** **`GSInterpreter.eval`** **第 611-617 行的** **`const`** **特殊解析逻辑**）：

   ```java
   // const 指令特殊处理：value 可能含空格，不能用 split
   if (code.length() > 5 && "const".equals(code.substring(0, 5))) {
       int start = code.indexOf(' ', 0) + 1;
       String typeChar = code.substring(start, start + 1);   // "a"|"i"|"f"|"s"|"b"
       String value = code.substring(start + 2);              // 值（可含空格）
       codes[i] = new String[]{"const", typeChar, value};
   } else {
       codes[i] = code.split(" ");  // 其他指令按空格切分
   }
   ```
2. 遍历所有指令收集常量到常量池（`LinkedHashMap` 去重 + 保持插入顺序）：

   * `const a <name>` → name 存为 UTF8

   * `const i <value>` → value 存为 Int

   * `const f <value>` → value 存为 Float

   * `const s <value>` → value 存为 UTF8（**解决空格问题**）

   * `const b <value>` → value 存为 Bool

   * `declare/store/fstore/fundef <name>` → name 存为 UTF8

   * `sourcePath` → 存为 UTF8
3. 写入 header（magic、version、flags、CP count、bytecode length、source\_path CP index，CRC32 先占 0）
4. 写入常量池（每条：tag + data，UTF8 用 modified UTF-8 与 JVM 一致）
5. 写入字节码段（每条：opcode byte + operands，按操作码表编码）
6. 写入 source map（每条指令的 u2 行号，flags bit0=1）
7. 回填 CRC32（覆盖 header 起始到 source map 末尾），写入 4 字节 footer

**子操作码映射**（GSClassConstants 中定义）：

* `arith_op`: plus=1, minus=2, mul=3, div=4, modulo=5, neg=6, ls=7, rs=8

* `comp`: eq=1, neq=2, seq=3, sneq=4, gt=5, ge=6, lt=7, le=8

* `rela_op`: b\_and=1, b\_or=2, b\_xor=3, b\_not=4, l\_not=5

* `pushenv`: function=1, loop=2, block=3

* `popenv`: loop=1, block=2

* `new`: Object=1, Array=2

### 第3步：创建反序列化器 GSClassReader

**新建** `src/main/java/org/gscript/compile/gclass/GSClassReader.java`

**输入**：`InputStream`（或文件路径）

**输出**：`GSClassData` 对象，包含 `String[][] src` + `int[] sourceLines` + `String sourcePath`

**核心逻辑**：

1. 读 header，校验 magic + version + CRC32（先读全部字节再校验）
2. 读常量池到 `Object[]` 数组（按 tag 解析 UTF8/Int/Float/Bool）
3. 读字节码段：每条指令按操作码查表得到文本操作码名，操作数从 CP 取值拼装为 `String[]`，组装成 `String[][]`

   * 例如 `0x04 (const_s) 0x00 0x03` → CP\[3] 是 "hello world" → `new String[]{"const", "s", "hello world"}`

   * 这与现有 `GSFunction.src` 的 `String[][]` 格式完全一致
4. 读 source map 到 `int[]`
5. 返回 `GSClassData`

**新建** `src/main/java/org/gscript/compile/gclass/GSClassData.java` — 简单数据容器类，持有 `String[][] src`、`int[] sourceLines`、`String sourcePath`。

### 第4步：GSInterpreter 新增 eval 重载

**修改** `src/main/java/org/gscript/vm/GSInterpreter.java`

新增方法：

```java
public void eval(String[][] codes, int[] sourceLines, String sourcePath) {
    GSFunction anonymous = new GSFunction("null", codes, global);
    anonymous.sourceLines = sourceLines;
    anonymous.sourcePath = sourcePath;
    anonymous.baseOffset = 0;
    GSFrame frame = new GSFrame(anonymous);
    try {
        eval(frame, null);
    } catch (DebugAbortException e) {
        // 调试会话被终止，正常退出（与现有 eval(String[],...) 一致）
    } catch (GSException e) {
        System.out.println(String.format("Uncaught Error: %s at <anonymous>:%d",
                e.origin.toStringValue(), e.getIp()));
    }
}
```

与现有 `eval(String[], int[], String)`（GSInterpreter.java:607-634）的区别：**跳过 1D** **`String[]`** **→ 2D** **`String[][]`** **的拆分步骤**（gclass 反序列化已预拆分为 `String[][]`），其余逻辑（异常处理、frame 创建）完全一致。

现有三个 `eval` 重载保留不变（向后兼容）。

### 第5步：TestScript 增加 gclass 支持

**修改** `src/main/java/org/gscript/TestScript.java`

扩展 `main` 的 args 解析（现有仅支持 `<name>` 和 `<name> dump`），新增三个模式：

* `<name>` — 现有：编译 + 执行（保留不变）

* `<name> dump` — 现有：打印字节码↔源码行映射（保留不变）

* `<name> compile` — **新增**：编译 `/<name>.script` (classpath) → 写出 `gtxt/<name>.gclass`（与 `.gtxt` 同目录，即 `target/classes/gtxt/`）

* `<name> rungclass` — **新增**：加载 classpath 资源 `/<name>.gclass` → `GSClassReader.deserialize` → `interpreter.eval(codes, sourceLines, sourcePath)` 执行

* `<name> dumpgclass` — **新增**：加载 `/<name>.gclass` → 反序列化 → 打印字节码↔源码行映射（与 `dump` 输出对比验证正确性）

**实现要点**：

* `compile` 模式：复用现有 Lexer/Parser/ByteCodeGenerator 编译，调用 `GSClassWriter.write(bytecode, sourceLines, sourcePath)` 写到 `Paths.get(rootUrl.toURI()).resolve("gtxt").resolve(name + ".gclass")`

* `rungclass`/`dumpgclass` 模式：`Test.class.getResourceAsStream("/" + name + ".gclass")` 读取字节 → `GSClassReader.deserialize(inputStream)` → 得到 `GSClassData`（含 `String[][] src` + `int[] sourceLines` + `String sourcePath`）→ 调用新增的 `interpreter.eval(codes, sourceLines, sourcePath)` 重载

### 第6步：DapServer 增加 gclass 支持（可选，本次可做可不做）

**修改** `src/main/java/org/gscript/vm/debug/dap/DapServer.java` 的 `compileScript`（DapServer.java:709-727）

在方法开头增加 .gclass 缓存检查：

```java
private void compileScript(String path) throws Exception {
    // 推导 .gclass 候选路径：与 .script 同目录，扩展名替换为 .gclass
    String gclassPath = path.replaceAll("\\.script$", ".gclass");
    File gclassFile = new File(gclassPath);
    File scriptFile = new File(path);
    // 若 .gclass 存在且比 .script 新（或 .script 不存在），直接加载 gclass
    if (gclassFile.exists() &&
        (!scriptFile.exists() || gclassFile.lastModified() >= scriptFile.lastModified())) {
        try (InputStream in = Files.newInputStream(gclassFile.toPath())) {
            GSClassData data = GSClassReader.deserialize(in);
            compiledBytecodes.add(data.src);           // String[][] 直接用
            compiledSourceLines.add(data.sourceLines);
            // sourcePath 用 canonicalize 规范化（与现有断点匹配逻辑一致）
            scriptPaths.set(scriptPaths.indexOf(path), canonicalize(path));
        }
        log("[FLOW] 从 gclass 加载: " + gclassPath);
        return;
    }
    // 回退：原有 .script 编译逻辑（不变）
    String content = new String(Files.readAllBytes(Paths.get(path)), ...);
    // ... 现有 Lexer/Parser/ByteCodeGenerator 编译 ...
}
```

**注意**：`startInterpreterThread`（DapServer.java:750-756）调用 `interpreter.eval(bc, sl, path)` 其中 `bc` 是 1D `String[]`。加载 gclass 后得到的是 2D `String[][]`，需改为调用新增的 `eval(String[][], int[], String)` 重载。可通过 `compiledBytecodes` 类型从 `List<String[]>` 改为 `List<String[][]>` 统一处理，或在 `startInterpreterThread` 中判断类型分发。**推荐**：将 `compiledBytecodes` 改为 `List<String[][]>`，同时让 `compileScript` 的 .script 分支也做 1D→2D 预拆分（复用 eval 中的拆分逻辑），统一数据流。

### 第7步：端到端测试

**新建** `tests/test_gclass.py`

测试流程：

1. 编译 `multi_a.script` → `multi_a.gclass`
2. 加载 `multi_a.gclass` → 执行 → 验证输出与直接编译执行一致
3. 验证 source map 完整性（dump 模式对比）
4. 验证 CRC32 校验（篡改文件后应拒绝加载）
5. 验证 `const s "hello world"` 字符串含空格正确序列化/反序列化

***

## 关键文件清单

### 新建（4 个）

| 文件                                                               | 说明                                                      |
| ---------------------------------------------------------------- | ------------------------------------------------------- |
| `src/main/java/org/gscript/compile/gclass/GSClassConstants.java` | 操作码常量 + 文本↔二进制映射表                                       |
| `src/main/java/org/gscript/compile/gclass/GSClassWriter.java`    | 序列化器：文本字节码 → .gclass 二进制                                |
| `src/main/java/org/gscript/compile/gclass/GSClassReader.java`    | 反序列化器：.gclass → String\[]\[] + sourceLines + sourcePath |
| `src/main/java/org/gscript/compile/gclass/GSClassData.java`      | 反序列化结果数据容器                                              |

### 修改（3 个）

| 文件                                                      | 改动                                                   |
| ------------------------------------------------------- | ---------------------------------------------------- |
| `src/main/java/org/gscript/vm/GSInterpreter.java`       | 新增 `eval(String[][], int[], String)` 重载（跳过 1D→2D 拆分） |
| `src/main/java/org/gscript/TestScript.java`             | 新增 `compile`/`run gclass`/`dumpgclass` 命令行模式         |
| `src/main/java/org/gscript/vm/debug/dap/DapServer.java` | `compileScript()` 增加 .gclass 缓存加载逻辑                  |

### 不改动

`GSFunction`、`GSFrame`、`GSEnv`、`GSExceptionMonitor`、`DebugController`、所有 `GSValue` 子类、`ByteCodeGenerator`、`Lexer`、`Parser`、所有 AST 节点。

***

## 向后兼容

* `.script` 直接编译执行路径完全保留（`eval(String[])` 系列不变）

* `.gtxt` 文本 dump 保留（调试用）

* `ByteCodeGenerator` 仍输出 `ArrayList<String>` 文本字节码，`GSClassWriter` 作为独立后续阶段转换

* 调试器（`DebugController`、`DapServer` 的断点/单步/源码映射逻辑）完全不受影响

***

## Assumptions & Decisions（关键决策）

| 决策点            | 选择                            | 理由                                                          |
| -------------- | ----------------------------- | ----------------------------------------------------------- |
| 实施方案           | 方案 A（序列化层），方案 B 留待后续          | 用户选择"先A后B分两步"；A 改动小、解释器主循环不动、风险低                            |
| 字节序            | Big-Endian                    | 与 Java `.class` 一致，便于跨平台网络传输                                |
| `const` 编码     | 拆为 5 个独立操作码（const\_a/i/f/s/b） | 省去 1 字节类型 tag；彻底解决 `const s "hello world"` 空格问题（值存常量池而非指令流） |
| 常量池索引          | 从 1 开始（0=无引用）                 | 与 Java `.class` 一致；0 可表示"无 sourcePath"等语义                   |
| 常量池去重          | 字符串/变量名/函数名/源文件路径统一存为 UTF8    | 减小文件体积（同名变量只存一份）                                            |
| Int/Float/Bool | 独立常量池条目（非 UTF8）               | 避免运行时字符串解析为数值，加载即用                                          |
| 校验机制           | CRC32（4字节 footer）             | 用户明确要求；检测传输/存储损坏，开销低                                        |
| 源码映射           | 可选段（flags bit0 控制）            | 非调试场景可省略减小体积；调试器加载时需含映射                                     |
| 源文件路径          | 可选（flags bit1），存为 UTF8 常量     | 多文件调试区分文件；非调试可省略                                            |
| fundef 内联      | 保持现有机制不变                      | 子函数字节码内联在父函数流中，序列化时作为普通指令写入即可                               |
| 向后兼容           | 现有 `eval(String[])` 系列完全保留    | `.script` 直接编译执行路径不动，回归零风险                                  |
| DapServer 第6步  | 标记为可选                         | 优先完成核心 1-5 步 + 测试；DapServer 集成可后续补                          |

***

## 验证方案

1. **编译验证**：`mvn -q compile` + `mvn -q package`
2. **序列化正确性**：`TestScript multi_a dump` 与 `TestScript multi_a dumpgclass` 的字节码映射对比应完全一致
3. **执行正确性**：`TestScript multi_a compile` → `TestScript multi_a rungclass` 的输出应与 `TestScript multi_a` 完全一致
4. **字符串空格**：编写含 `const s "hello world"` 的测试脚本，验证 gclass 序列化/反序列化后字符串完整
5. **CRC32 校验**：手动篡改 .gclass 文件 1 字节，验证 `GSClassReader` 抛出校验错误
6. **回归测试**：运行 `test_dap_e2e.py`（17/17）、`test_multi_file.py`（19/19）、`test_cross_file_step.py`（7/7）确认无回归
7. **调试器兼容**：通过 DapServer 加载 .gclass 文件调试，验证断点/单步/变量查看正常

