# gclass 字节码优化 — 完成计划

> **背景**：用户要求优化 gscript 字节码，设计类似 Java .class 的 gclass 二进制格式（含字符串常量池、源码映射、CRC32 校验），解释器执行二进制字节码而非文本。
> 用户已批准 "Plan B + 格式增强" 方向（二进制内存表示 `byte[][]` + `Object[]` 常量池 + RLE 源码映射 + 属性段）。

## 一、当前状态分析（已通过读代码核实）

### 已完成且正确（Steps 1-10）
| 文件 | 状态 |
|------|------|
| `gclass/GSClassConstants.java` | ✅ 37 操作码 + flags(RLE/ATTR) + `instructionLength()` |
| `gclass/EncodedBytecode.java` | ✅ `byte[][]` + `Object[]` 容器 |
| `gclass/BytecodeEncoder.java` | ✅ 文本→二进制 + CP 去重 + const 空格处理 |
| `gclass/BytecodeDecoder.java` | ✅ 二进制→文本（dump 用） |
| `vm/value/GSFunction.java` | ✅ `src` 为 `byte[][]`，`constantPool` 字段，新构造器 |
| `vm/GSFrame.java` | ✅ `getCode()` 返回 `byte[]` |
| `vm/GSInterpreter.java` | ✅ `switch(byte opcode)` 全 37 case，`readU2/readS2`，新 `eval(byte[][], Object[], int[], String)` |
| `gclass/GSClassData.java` | ✅ `byte[][]` src + `Object[]` constantPool |
| `gclass/GSClassReader.java` | ✅ 按 `instructionLength()` 读原始字节（不转 String），CRC32 校验 |
| `gclass/GSClassWriter.java` | ✅ 复用 BytecodeEncoder，旧 CP 逻辑已删 |
| `TestScript.java` | ✅ dumpGclass 用 BytecodeDecoder，runGclass 用新 eval 签名 |

### ❌ Step 11 未完成：DapServer 编译阻塞点
经读代码核实，`DapServer.java` 处于**不一致状态**（无法编译）：

| 问题 | 位置 | 说明 |
|------|------|------|
| 缺 import | 行 8-14 | 未导入 `BytecodeEncoder`、`EncodedBytecode` |
| .gclass 路径漏加 CP | 行 733 | `compiledBytecodes.add(data.src)` 后未 `compiledConstantPools.add(data.constantPool)` |
| .script 路径类型错误 | 行 749-758 | `splitBytecode(bc1d)` 返回 `String[][]`，但 `compiledBytecodes` 是 `List<byte[][]>` → **编译失败** |
| 残留 splitBytecode | 行 768-782 | 应删除的旧方法仍存在 |
| ✅ startInterpreterThread | 行 791-828 | 已正确迁移（消费端用 `byte[][]`/`Object[]`/`eval(bc,cp,sl,path)`） |
| ✅ 字段声明/clear | 行 92/96/455 | 已正确 |

**根因**：`compileScript()`（生产端）未完成迁移，而 `startInterpreterThread()`（消费端）已完成，导致类型不匹配。

### ⏳ 未开始（Steps 12-16）
- Step 12：编译 + 全测试
- Step 13：RLE 压缩源码映射
- Step 14：Attributes 段 + FunctionTable 属性
- Step 15：格式增强测试
- Step 16：清理 + 记忆更新

### 关键约束（不变）
- **调试器零影响**：`DebugController` 不访问 `function.src`/`constantPool`，只用 `sourceLines`/`baseOffset`/`sourcePath`/`name`
- **CP 索引 1-based**（0 不用），同文件函数共享 constantPool 引用（fundef 切片继承）
- **CRC32 覆盖** Header[0-15] + [20-end]，新增段（attributes）天然被覆盖，无需改 CRC 逻辑
- **gclass 版本 1.1**（MINOR_VERSION 已改），RLE/attributes 是可选 flag，不改主版本

---

## 二、实施计划

### Part A：修复 DapServer + 恢复编译 + 全测试通过

#### Step 11（补完）：修复 DapServer.compileScript()

**文件**：`src/main/java/org/gscript/vm/debug/dap/DapServer.java`

**11.1 添加 import**（行 12 后）
```java
import org.gscript.compile.gclass.BytecodeEncoder;
import org.gscript.compile.gclass.EncodedBytecode;
```

**11.2 修复 .gclass 加载路径**（行 733 附近）
```java
// 从 .gclass 加载
try (InputStream gin = Files.newInputStream(gclassFile.toPath())) {
    GSClassReader reader = new GSClassReader();
    GSClassData data = reader.deserialize(gin);
    compiledBytecodes.add(data.src);
    compiledConstantPools.add(data.constantPool);   // 新增：常量池也要加入
    compiledSourceLines.add(data.sourceLines);
    gclassLen = data.src.length;
}
```

**11.3 修复 .script 编译路径**（行 749-758）
```java
String[] bc1d = gen.getByteCode().toArray(new String[0]);
// 用 BytecodeEncoder 编码为二进制（byte[][] + Object[] CP）
BytecodeEncoder encoder = new BytecodeEncoder();
EncodedBytecode encoded = encoder.encode(Arrays.asList(bc1d));
ArrayList<Integer> sl = gen.getSourceLines();
int[] slArr = new int[sl.size()];
for (int i = 0; i < sl.size(); i++) {
    slArr[i] = sl.get(i);
}
compiledBytecodes.add(encoded.instructions);
compiledConstantPools.add(encoded.constantPool);
compiledSourceLines.add(slArr);
log("[FLOW] 编译完成: " + path + "，bytecode 长度=" + encoded.instructions.length);
```
（需补 `import java.util.Arrays;`）

**11.4 删除 splitBytecode() 方法**（行 763-782 整个方法）

**11.5 修复 compileScript 的 Javadoc**（行 719）
`统一转为 2D String[][]` → `统一转为 byte[][] + Object[] 常量池`

---

#### Step 12（验证点）：编译 + 运行全部测试

```bash
# 1. 全量编译（验证 Part A 无编译错误）
mvn -q clean compile

# 2. gclass 端到端测试（序列化/执行/CRC32/多脚本一致性）
python tests/test_gclass.py

# 3. 回归测试（确认 byte[][] 改动不影响调试器）
python tests/test_dap_e2e.py
python tests/test_step_catch.py
python tests/test_while_breakpoint.py
python tests/test_multi_file.py
python tests/test_cross_file_step.py
```

**预期**：全部通过。若编译失败，定位并修复（重点是 DapServer 类型一致性）。
**若 .script 资源缺失**：检查 `src/main/resources/` 是否有 .script 文件（Maven 构建会复制到 target/classes）。

---

### Part B：gclass 格式增强（Part A 全通过后）

#### Step 13：RLE 压缩源码映射

**目标**：源码行号数组常有长游程（多条字节码对应同一源码行），RLE 显著减小文件体积。

**二进制格式**（当 `FLAG_RLE_SOURCE_MAP` 置位时，替代原始 SourceMap）：
```
u4 pairCount
pairCount × (u2 count, u2 line)   // count=连续相同行号数，line=行号
```
解码：对每对 (count, line)，展开 count 个 line 到 int[]。

**GSClassWriter 改动**（`write()` 方法 SourceMap 段）：
1. 先把 `sourceLines` 编码为 RLE 对（游程压缩）
2. 判断压缩率：若 `rlePairs.size() < sourceLines.size() × 0.75`（压缩率 > 25%）→ 设置 `FLAG_RLE_SOURCE_MAP`，写 RLE 格式
3. 否则写原始格式（向后兼容，flag 不置位）

```java
// RLE 编码
List<int[]> rlePairs = new ArrayList<>();  // each: {count, line}
if (hasSourceMap) {
    int prev = sourceLines.get(0);
    int count = 1;
    for (int i = 1; i < sourceLines.size(); i++) {
        int cur = sourceLines.get(i);
        if (cur == prev) { count++; }
        else { rlePairs.add(new int[]{count, prev}); prev = cur; count = 1; }
    }
    rlePairs.add(new int[]{count, prev});
}
boolean useRle = hasSourceMap && rlePairs.size() < sourceLines.size() * 0.75;
if (useRle) flags |= GSClassConstants.FLAG_RLE_SOURCE_MAP;
```

写入：
```java
if (hasSourceMap) {
    if (useRle) {
        dos.writeInt(rlePairs.size());
        for (int[] p : rlePairs) { dos.writeShort(p[0]); dos.writeShort(p[1]); }
    } else {
        dos.writeInt(sourceLines.size());
        for (Integer line : sourceLines) { dos.writeShort(line); }
    }
}
```

**GSClassReader 改动**（`deserialize()` SourceMap 段，行 134-142）：
```java
int[] sourceLines = null;
if (hasSourceMap) {
    boolean useRle = (flags & GSClassConstants.FLAG_RLE_SOURCE_MAP) != 0;
    if (useRle) {
        int pairCount = dis.readInt();
        // 先算总长度
        List<int[]> pairs = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < pairCount; i++) {
            int count = dis.readUnsignedShort();
            int line = dis.readUnsignedShort();
            pairs.add(new int[]{count, line});
            total += count;
        }
        sourceLines = new int[total];
        int idx = 0;
        for (int[] p : pairs) { Arrays.fill(sourceLines, idx, idx + p[0], p[1]); idx += p[0]; }
    } else {
        int lineCount = dis.readInt();
        sourceLines = new int[lineCount];
        for (int i = 0; i < lineCount; i++) { sourceLines[i] = dis.readUnsignedShort(); }
    }
}
```

---

#### Step 14：Attributes 段 + FunctionTable 属性

**目标**：引入可扩展属性段机制，首个属性 FunctionTable 记录所有函数元数据（名称/起始IP/体长），供调试器和工具链使用。

**二进制格式**（当 `FLAG_HAS_ATTRIBUTES` 置位时，位于 SourceMap 之后）：
```
u2 attrCount
attrCount × attribute {
    u2 nameCpIndex      // UTF8 属性名（CP 索引）
    u4 length            // 属性数据字节数
    byte[length] data    // 属性数据
}
```

**FunctionTable 属性数据格式**（nameCpIndex 指向 CP 中 "FunctionTable" UTF8）：
```
u2 funcCount
funcCount × {
    u2 nameCpIndex   // 函数名 UTF8（CP 索引）
    u4 startIp        // 函数体在顶级字节码中的起始索引
    u4 bodyLen        // 函数体指令数
}
```

**GSClassData 扩展**：
```java
public static class FunctionEntry {
    public final String name;
    public final int startIp;
    public final int bodyLen;
    public FunctionEntry(String name, int startIp, int bodyLen) {
        this.name = name; this.startIp = startIp; this.bodyLen = bodyLen;
    }
}
public final FunctionEntry[] functions;  // 可为 null（无 attributes 段时）

// 构造器新增 functions 参数（旧调用点传 null）
```

**GSClassWriter 改动**：
1. 扫描 `byte[][] instructions`，找出所有 `OP_FUNDEF` 指令：
   - `nameCp = readU2(instr, 1)`，`bodyLen = readU2(instr, 3)`
   - `startIp` = 该 fundef 指令的索引 + 1（函数体紧随 fundef 指令）
2. 把 "FunctionTable" 字符串加入 CP（追加到末尾，记录其 CP 索引）
3. 构建 FunctionTable 属性数据字节
4. 设置 `FLAG_HAS_ATTRIBUTES`，在 SourceMap 之后写属性段

**GSClassReader 改动**（SourceMap 之后）：
```java
FunctionEntry[] functions = null;
if (hasAttributes) {
    int attrCount = dis.readUnsignedShort();
    for (int a = 0; a < attrCount; a++) {
        int nameCp = dis.readUnsignedShort();
        int len = dis.readInt();
        String attrName = (String) cp[nameCp];
        byte[] attrData = new byte[len];
        dis.readFully(attrData);
        if (GSClassConstants.ATTR_FUNCTION_TABLE.equals(attrName)) {
            functions = parseFunctionTable(attrData, cp);
        }
        // 未知属性：跳过（前向兼容）
    }
}
```

**TestScript**：可选地在 dumpGclass 中打印 FunctionTable（若有），便于人工核对。

---

#### Step 15（验证点）：格式增强测试

**test_gclass.py 扩展**：
1. dump vs dumpgclass 一致性仍通过（Decoder 透明处理 RLE/attributes，不影响指令文本）
2. 新增：gclass 文件体积对比（RLE 压缩率，打印压缩前后字节数）
3. 新增：FunctionTable 正确性（通过 dumpgclass 输出核对函数名/位置，或在 TestScript 加 `--functable` 模式打印）

**回归**：所有 Part A 测试仍通过（RLE/attributes 是可选 flag，旧 reader 读不到也不影响执行）。

---

### Part C：清理

#### Step 16：清理 + 最终验证 + 记忆更新

1. 检查 `target/classes/multi_a.gclass`（测试副产物，可保留或清理）
2. 更新 `c:\Users\95739\.trae-cn\memory\projects\-e-JProjects-gscript\project_memory.md`：
   - 记录 Plan B 完成（byte[][] 内存表示 + gclass v1.1 格式）
   - 记录 RLE/attributes 设计要点
   - 记录 DapServer compileScript 迁移教训（生产端/消费端需同步迁移）
3. 运行全部测试套件确认无回归

---

## 三、假设与决策

| # | 决策 | 理由 |
|---|------|------|
| 1 | 优先修复 DapServer 恢复编译 | 当前代码不可编译，必须先修复才能验证任何改动 |
| 2 | RLE 仅在压缩率 > 25% 时启用 | 避免小文件反而变大；flag 不置位则用旧格式，向后兼容 |
| 3 | Attributes 段位于 SourceMap 之后 | 可选段，不影响无属性文件解析；CRC 天然覆盖 |
| 4 | 未知属性跳过（前向兼容） | 未来可加新属性，旧 reader 不崩溃 |
| 5 | FunctionTable 扫描 OP_FUNDEF 提取元数据 | startIp = fundef 索引+1，bodyLen = fundef 操作数 |
| 6 | GSClassData 加 FunctionEntry[] functions（可为 null） | 无 attributes 段时为 null，不影响现有调用 |
| 7 | 不修改 ByteCodeGenerator/Parser/Lexer/DebugController | 生成器仍输出文本，编码为 byte[][] 是独立步骤；调试器零影响 |
| 8 | 保留 `eval(String[])` 文本入口 | TestScript.gen() 仍用，内部 BytecodeEncoder 编码后执行 |
| 9 | gclass 版本保持 1.1 | RLE/attributes 是可选 flag，不改主版本 |

---

## 四、文件变更清单

| 文件 | 操作 | Step |
|------|------|------|
| `vm/debug/dap/DapServer.java` | 修复 compileScript（import + BytecodeEncoder + CP add + 删 splitBytecode） | 11 |
| `gclass/GSClassWriter.java` | 加 RLE 编码 + attributes 段写入 | 13, 14 |
| `gclass/GSClassReader.java` | 加 RLE 解码 + attributes 段解析 | 13, 14 |
| `gclass/GSClassData.java` | 加 FunctionEntry 内部类 + functions 字段 | 14 |
| `TestScript.java` | （可选）dumpGclass 打印 FunctionTable | 15 |
| `tests/test_gclass.py` | 加 RLE 压缩率 + FunctionTable 验证 | 15 |
| `project_memory.md` | 记录完成状态 | 16 |

**不修改**：GSClassConstants（已就绪）、BytecodeEncoder/Decoder/EncodedBytecode（已就绪）、GSInterpreter（已就绪）、GSFunction/GSFrame（已就绪）、ByteCodeGenerator、Parser、Lexer、DebugController

---

## 五、实施顺序

```
Step 11 (修复 DapServer) ──┐
Step 12 (编译+测试 Part A) ──┘ 检查点：全测试通过
         │
Step 13 (RLE source map)  ──┐
Step 14 (attributes)       ──┼── Part B：格式增强
Step 15 (测试 Part B)      ──┘
         │
Step 16 (清理+记忆)        ──── 完成
```

**Part A 完成后代码即可编译运行且全测试通过**；Part B 是纯增量增强（可选 flag），不影响已通过的功能。
