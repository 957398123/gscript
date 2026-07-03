# GScript Java 1.4 迁移计划

## 概述

将 GScript 项目从 Java 9+ 迁移到纯 Java 1.4，替换 Gson（自研 JSON 库）和 java.util.concurrent（自研并发原语），移除所有 Java 5+ 语言特性和 API。基础设施已就绪，本计划覆盖剩余的源码迁移、编译验证和测试。

---

## 当前状态分析

### 已完成（Phase 1 基础设施）
- **pom.xml** — 已配置 `<source>1.4</source><target>1.4</target>`，无外部依赖，maven-jar-plugin 替代 shade-plugin
- **JSON 库**（7 文件，`org.gscript.vm.debug.dap.json`）— JsonValue/JsonNull/JsonPrimitive/JsonObject/JsonArray/JsonParser/JsonWriter，Java 1.4 兼容（raw type、无注解、StringBuffer）
- **并发原语**（3 文件，`org.gscript.util`）— AtomicCounter/SimpleBlockingQueue/MinPriorityQueue，Java 1.4 兼容

### 待迁移（87 源文件）
全量扫描发现的 Java 5+ 特性分布：

| 类别 | 数量 | 影响文件数 | 复杂度 |
|------|------|-----------|--------|
| 泛型（`<Type>`） | 265 | 37 | 中（移除 + 加 cast） |
| 注解（`@Override` 等） | 110 | 47 | 低（删除） |
| 钻石操作符（`<>`） | 79 | 19 | 低（删除 `<>`） |
| String.format() | 57 | 5 | 中（改拼接） |
| 增强 for 循环 | 33 | 12 | 中（改 Iterator/index） |
| try-with-resources | 8 | 5 | 中（改 try/finally） |
| String.isEmpty() | 7 | 3 | 低（改 length()==0） |
| Lambda 表达式 | 5 | 3 | 中（改匿名内部类） |
| StringBuilder | 6 | 3 | 低（改 StringBuffer） |
| java.nio.file | 8 imports | 3 | 中（改 java.io） |
| StandardCharsets | 5 imports | 5 | 低（改 "UTF-8"） |
| readAllBytes() | 3 | 3 | 中（改手动读取） |
| ArrayDeque | 5 | 2 | 低（改 LinkedList） |
| 枚举（GSTokenType） | 1 | 1+6引用 | 高（类型安全枚举模式） |
| Gson API | ~30 | 1 | 高（JSON 库替换） |
| PriorityQueue/LinkedBlockingQueue/AtomicInteger/TimeUnit | 8 | 2 | 中（自研原语替换） |
| Set.of() | 1 | 1 | 低（改 HashSet） |
| 自动装箱/拆箱 | ~10 | 4 | 中（显式包装） |

---

## 关键设计决策

### 1. GSTokenType 枚举迁移：int 常量类
**方案**：将 `enum GSTokenType` 转为 `class GSTokenType`，所有枚举常量变为 `public static final int`。

**理由**：
- Java 1.4 的 `switch` 仅支持 `int`/`char`，int 常量天然兼容现有 6 个 switch 语句（`case GSTokenType.PLUS:` 无需改语法）
- 项目已有先例：`GSValue.type` 就是 int 字段 + 魔数
- `getValue()` 改为静态查找方法 `getValue(int type)`

**影响**：
- `GSToken.type` 字段从 `GSTokenType` 改为 `int`
- `GSToken.getType()` 返回类型从 `GSTokenType` 改为 `int`
- 所有 `token.getType().getValue()` 改为 `GSTokenType.getValue(token.getType())`
- 所有 `type == GSTokenType.PLUS` 比较无需改（int == int）

### 2. String.format() 替换：字符串拼接
**方案**：用 `+` 拼接替代 `String.format()`。

**理由**：Java 1.4 无 `String.format()`。`MessageFormat` 语法不同（`{0}` vs `%s`），改造成本更高。字符串拼接最直观，且编译器会优化为 StringBuffer。

### 3. ArrayDeque 替换：LinkedList
**方案**：`ArrayDeque<GSValue>` → `LinkedList`（用作栈：`push`/`pop`/`peek`）。

**理由**：`java.util.LinkedList` 自 Java 1.2 存在，支持 `Deque` 操作（`addFirst`/`removeFirst`/`peek`）。性能差异在解释器场景可忽略。

### 4. Lambda 替换：匿名内部类
**方案**：`new Thread(() -> {...})` → `new Thread(new Runnable() { public void run() {...} })`；`Comparator` lambda → 匿名 `Comparator` 实现。

### 5. java.nio.file 替换：java.io
- `Files.readAllBytes(path)` → 手动 `ByteArrayOutputStream` 循环读取
- `Files.newInputStream(path)` → `new FileInputStream(file)`
- `Paths.get(uri)` → `new File(uri)`
- `DirectoryStream<Path>` → `File.listFiles(FilenameFilter)`

### 6. Gson 替换：自研 JSON 库
- `import com.google.gson.*` → `import org.gscript.vm.debug.dap.json.*`
- `Gson gson = new Gson()` → 删除（JsonWriter/JsonParser 均为静态方法）
- `gson.toJson(tree)` → `JsonWriter.toJson(tree)`
- `JsonParser.parseString(str)` → `JsonParser.parseString(str)`（API 已对齐）
- `JsonObject`/`JsonArray` API 已兼容，仅需改 import + 删除泛型

### 7. 编译工具链
- **JDK 8**（支持 `-source 1.4 -target 1.4`）为最终验证环境
- 当前 JDK 9.0.4 不支持 1.4（最低 1.6），可作为**临时编译验证**（`-source 1.6 -target 1.6`）
- pom.xml 已配 1.4，JDK 8 环境下 `mvn compile` 即可

---

## 实施步骤

### Phase 2: GSTokenType 枚举迁移（核心基础）
**文件**：`compile/token/GSTokenType.java`、`compile/token/GSToken.java`、`compile/Parser.java`、`compile/gen/ByteCodeGenerator.java`

**步骤**：
1. 重写 `GSTokenType.java`：
   - `public class GSTokenType`（非 `enum`）
   - 51 个 `public static final int` 常量（PLUS=1, MINUS=2, ... EOF=51）
   - `private static final String[] VALUES = {"+", "-", ...}` 对应字符串
   - `public static String getValue(int type)` → 返回 `VALUES[type-1]`
   - `public static String getName(int type)` → 返回常量名（调试用）
2. 修改 `GSToken.java`：
   - `public int type`（原 `GSTokenType type`）
   - `getType()` 返回 `int`
   - 构造函数参数改 `int`
3. 修改 `Parser.java`：
   - 所有 `GSTokenType` 类型变量改 `int`
   - `token.getType().getValue()` → `GSTokenType.getValue(token.getType())`
   - 8 个增强 for 循环 `for (GSTokenType t : ASSIGN_TYPES)` 改为索引遍历
4. 修改 `ByteCodeGenerator.java`：
   - 同上，3 个 switch 语句无需改语法（`case GSTokenType.PLUS:` 对 int 常量有效）

### Phase 3: DapServer Gson → 自研 JSON 库
**文件**：`vm/debug/dap/DapServer.java`

**步骤**：
1. 替换 import：`com.google.gson.*` → `org.gscript.vm.debug.dap.json.*`
2. 删除 `private final Gson gson = new Gson();`
3. `AtomicInteger seq` → `AtomicCounter seq`（import `org.gscript.util.AtomicCounter`）
4. `gson.toJson(obj)` → `JsonWriter.toJson(obj)`
5. `JsonParser.parseString(str)` → 保持不变（API 已对齐）
6. `JsonObject`/`JsonArray` 操作：移除泛型，加 cast
7. `import java.nio.file.Files/Paths` → 删除，改用 `java.io.File`/`FileInputStream`
8. `import java.nio.charset.StandardCharsets` → 删除，用 `"UTF-8"` 字符串
9. `Files.readAllBytes(Paths.get(path))` → 手动读取循环
10. `Files.newInputStream(file.toPath())` → `new FileInputStream(file)`
11. 移除所有 `@Override`、泛型、钻石操作符、增强 for 循环、try-with-resources
12. `String.format()` → 字符串拼接
13. Lambda（第 1035 行 `new Thread(() -> {...})`）→ 匿名 `Runnable`

### Phase 4: TimerScheduler 并发原语迁移
**文件**：`vm/TimerScheduler.java`

**步骤**：
1. `import java.util.PriorityQueue` → `import org.gscript.util.MinPriorityQueue`
2. `import java.util.concurrent.LinkedBlockingQueue` → `import org.gscript.util.SimpleBlockingQueue`
3. `import java.util.concurrent.TimeUnit` → 删除
4. `import java.util.concurrent.atomic.AtomicInteger` → `import org.gscript.util.AtomicCounter`
5. `PriorityQueue<TimerTask> timerQueue = new PriorityQueue<>((a,b) -> ...)` → `MinPriorityQueue timerQueue = new MinPriorityQueue(new TimerTaskComparator())`
6. `LinkedBlockingQueue<TimerTask> readyQueue` → `SimpleBlockingQueue readyQueue`
7. `AtomicInteger nextId` → `AtomicCounter nextId`
8. `nextId.getAndIncrement()` → 保持不变（API 已对齐）
9. `readyQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)` → `readyQueue.poll(timeoutMs)`（SimpleBlockingQueue 用 ms）
10. Lambda `new Thread(() -> {...})` → 匿名 `Runnable`
11. `Map<Integer, TimerTask> taskMap` → `Map taskMap`（移除泛型）
12. `volatile` 关键字保留（Java 1.4 支持）

### Phase 5: DebugAgent + DapCLIMain 迁移
**文件**：`vm/debug/DebugAgent.java`、`vm/debug/dap/DapCLIMain.java`

**步骤**：
1. DebugAgent：2 个 Lambda → 匿名 `Runnable`；2 个 try-with-resources → try/finally + `close()`
2. DapCLIMain：2 个 try-with-resources → try/finally + `close()`
3. 移除泛型、钻石操作符、注解、增强 for 循环
4. `java.net.Socket`/`ServerSocket` 保留（Java 1.4 支持）

### Phase 6: 全量机械化迁移（剩余文件）
**按包分组处理**：

#### 6a. compile 包
- `Lexer.java`：`Set.of()` → `new HashSet(Arrays.asList(...))`；3 个 `StringBuilder` → `StringBuffer`；移除 `@SuppressWarnings`；移除泛型
- `Parser.java`：36 处泛型移除；增强 for 循环改 Iterator；`String.format()` 改拼接；移除 `@SuppressWarnings`
- `gen/ByteCodeGenerator.java`：26 处泛型；35 个 `String.format()`；增强 for 循环；`Map.Entry` 迭代
- `gen/ByteCodeOptimize.java`：泛型移除
- `gclass/*.java`（6 文件）：泛型移除；`StandardCharsets.UTF_8` → `"UTF-8"`；`Map<Byte,String>` 自动装箱改显式 `new Byte(code)`
- `node/*.java`（~30 文件）：仅移除 `@Override` 注解和少量泛型

#### 6b. vm 包
- `GSInterpreter.java`：`ArrayDeque<GSValue> stack` → `LinkedList`；`ArrayDeque<GSFrame> callStack` → `LinkedList`；泛型移除；`String.format()` 改拼接
- `GSFrame.java`：`ArrayDeque<GSExceptionMonitor>` → `LinkedList`；泛型移除
- `GSEnv.java`：泛型移除
- `stdlib/TimerLib.java`：泛型移除；`String.format()` 改拼接
- `stdlib/Console.java`：`StringBuilder` → `StringBuffer`
- `value/*.java`（~12 文件）：泛型移除；`String.isEmpty()` → `length()==0`；自动装箱改显式包装；`Map.Entry` 迭代改 Iterator

#### 6c. 根包
- `TestScript.java`：`java.nio.file` → `java.io`；`StandardCharsets` → `"UTF-8"`；`readAllBytes()` → 手动读取；try-with-resources → try/finally；`@SuppressWarnings` 删除；泛型移除
- `Test.java`：`java.nio.file` → `java.io.File.listFiles()`；`DirectoryStream` → `File.listFiles(FilenameFilter)`；try-with-resources → try/finally；泛型移除

### Phase 7: 编译验证
1. 确认 JDK 8 可用（`java -version` 显示 1.8.x）；若不可用，用 JDK 9 + `-source 1.6 -target 1.6` 临时验证
2. `mvn clean compile -q` 编译
3. 逐个修复编译错误（预期会有 cast 缺失、类型不匹配等）
4. 确认 `target/classes` 下所有 .class 生成成功

### Phase 8: 测试验证
1. **运行基线测试**：执行 `tests/run_baseline.py` 对比迁移前后输出
2. **逐项测试**：
   - `test_gclass.py`（65 项）— gclass 序列化/反序列化
   - `test_host_interaction.py`（12 项）— Java 宿主交互 API
   - `test_dap_e2e.py`（17 项）— DAP 调试器端到端（**关键：验证 Gson 替换正确**）
   - `test_timer.py`（15 项）— 定时器语义
   - `test_timer_debug.py`（25 项）— 定时器调试（**关键：验证并发原语替换**）
   - `test_multi_file.py`（19 项）— 多文件调试
   - `test_while_breakpoint.py`（9 项）— 循环断点
   - `test_cross_file_step.py`（7 项）— 跨文件单步
   - `test_step_catch.py`（7 项）— 异常单步
   - `test_path_mismatch.py`（3 项）— 路径规范化
3. **调试器逐行验证**：用 `test_dap_e2e.py` 验证断点命中、堆栈跟踪、变量检视、源码请求
4. **对比基线**：所有输出与 `tests/baseline/*.out` 完全一致

### Phase 9: 文档与记忆更新
1. 更新 `README.md`：标注 Java 1.4 兼容、移除 Gson 依赖说明
2. 更新 `project_memory.md`：新增"Java 1.4 迁移"章节（自研 JSON 库、并发原语、GSTokenType int 常量、编译工具链）
3. 更新 `topics.md`：记录迁移完成状态
4. 生成测试报告：测试用例数、通过数、调试记录

---

## 风险与缓解

| 风险 | 缓解措施 |
|------|---------|
| 泛型移除后 cast 缺失导致 ClassCastException | 编译期警告 + 测试覆盖；重点检查 Map.get() 返回值 |
| GSTokenType int 常量值与现有序列化数据冲突 | gclass 不序列化 token 类型（仅序列化字节码），无冲突 |
| Gson 替换后 DAP JSON 格式差异 | test_dap_e2e.py 覆盖 17 项 DAP 协议测试，包括 source 请求 |
| LinkedList 替代 ArrayDeque 性能 | 解释器栈操作量级小，性能差异可忽略；若 hotspot 可后续优化 |
| JDK 8 未安装 | 代码迁移不阻塞；JDK 9 + 1.6 可临时验证；最终需 JDK 8 |
| String.format → 拼接可读性下降 | 复杂格式用多行拼接 + 注释保持可读性 |

---

## 假设

1. 所有测试脚本（`tests/*.py`）本身无需修改（它们调用 `java -cp target/classes org.gscript.TestScript`，与 Java 版本无关）
2. `.script` 测试用例文件无需修改（gscript 语法不变）
3. `tests/baseline/*.out` 基线输出已存在且正确（Phase 0 已捕获）
4. VSCode 插件配置（`.vscode/launch.json`）无需修改（DAP 协议不变）
5. `readAllBytes` 替换实现需处理 `InterruptedIOException`（与原语义一致）
