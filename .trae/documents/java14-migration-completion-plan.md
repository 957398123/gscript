# GScript Java 1.4 迁移 — 收尾计划

> 本计划承接上一轮会话（上下文已丢失），基于当前代码库实际状态制定。
> 目标：完成 Java 1.4 迁移的最后几个阶段，交付可编译、测试通过、文档同步的项目。

---

## 一、当前状态分析（Phase 1 探索结论）

### 1.1 已完成的基础设施（持久化保留）
- **pom.xml** — `<source>1.4</source><target>1.4</target>`，无 Gson 依赖，maven-jar-plugin 2.4 设置 mainClass
- **自研 JSON 库**（7 文件，`vm/debug/dap/json/`）— `JsonValue/JsonNull/JsonPrimitive/JsonObject/JsonArray/JsonParser/JsonWriter`，API 与 Gson 对齐：
  - `JsonParser.parseString(json)` → `JsonValue`（对齐 Gson）
  - `JsonObject`: `addProperty(String,int/long/boolean/String)` / `add(String,JsonValue)` / `get(String)→JsonValue` / `has` / `getAsJsonObject(String)` / `getAsJsonArray(String)` / `getAsInt/getAsString/getAsBoolean(String)`
  - `JsonArray`: `add` / `size` / `get(int)→JsonValue` / `getAsJsonObject(int)` / `iterator()`
  - `JsonWriter.toJson(JsonValue)` 静态方法替代 `gson.toJson()`
- **自研并发原语**（3 文件，`util/`）— `AtomicCounter` / `SimpleBlockingQueue` / `MinPriorityQueue`

### 1.2 已完成迁移的源文件
- **GSTokenType.java** — enum → int 常量类（72 常量 + getValue/getName 静态方法）
- **GSToken.java** — `GSTokenType type` → `int type`
- **Lexer.java** — Set.of→HashSet、StringBuilder→StringBuffer、String switch→if-else、泛型移除
- **Parser.java** — GSTokenType 数组→int[]、方法签名→int、增强 for→索引、泛型/钻石/String.format 全部移除（agent 已确认）
- **TimerScheduler.java** — PriorityQueue→MinPriorityQueue、LinkedBlockingQueue→SimpleBlockingQueue、AtomicInteger→AtomicCounter、lambda→匿名 Runnable
- **compile/node/*.java**（46 文件）— @Override 移除、泛型移除（agent 已确认）
- **stdlib/Console.java + TimerLib.java + gen/ByteCodeOptimize.java** — @Override/泛型/StringBuilder/钻石 全部移除（agent 已确认）

### 1.3 后台 agent 仍在运行（11 个，部分为重复实例）
| Agent | 目标文件 | 状态 |
|-------|---------|------|
| 74cec60b | stdlib + ByteCodeOptimize | ✅ 已完成 |
| 4f5dea08 | Parser.java | ✅ 已完成 |
| a2768c85 | Parser.java（重复） | 🔄 运行中 |
| fc23a72a | Parser.java（重复） | 🔄 运行中 |
| 4d605d2d | ByteCodeGenerator.java | 🔄 运行中 |
| 25e302eb | ByteCodeGenerator.java（重复） | 🔄 运行中 |
| 0a3cf8f8 | ByteCodeGenerator.java（重复） | 🔄 运行中 |
| 5b8b8155 | TestScript + Test | 🔄 运行中 |
| 7b9469b6 | DebugAgent + DapCLIMain | 🔄 运行中 |
| 24bc6ade | vm/value/*.java（12 文件） | 🔄 运行中 |
| 0292a1ad | gclass/*.java（6 文件） | 🔄 运行中 |
| 44972ec9 | GSInterpreter + GSFrame + GSEnv | 🔄 运行中 |

**风险**：Parser.java 和 ByteCodeGenerator.java 各有 3 个 agent 在写同一文件，存在竞态（最后写入者胜出）。需在 agent 全部完成后逐文件验证最终状态。

### 1.4 待处理工作（本计划核心）
1. **DapServer.java 迁移**（Phase 3，约 1233 行，最复杂的手动任务）— Gson→自研 JSON、AtomicInteger→AtomicCounter、java.nio→java.io、@Override/泛型/钻石/增强 for/lambda/try-with-resources/String.format 全部移除
2. **后台 agent 完成后验证** — 逐文件确认最终状态正确（防止重复 agent 竞态破坏）
3. **编译验证**（Phase 7）
4. **全量回归测试**（Phase 8）— 10 个测试套件 ~176 用例，对比 baseline
5. **文档与记忆同步**（Phase 9）

---

## 二、待实现变更

### Phase 3：DapServer.java 手动迁移（核心手动任务）

**文件**：`e:\JProjects\gscript\src\main\java\org\gscript\vm\debug\dap\DapServer.java`

#### 3.1 import 替换
- 删除：`com.google.gson.Gson/JsonArray/JsonElement/JsonObject/JsonParser`（5 行）
- 删除：`java.nio.charset.StandardCharsets`、`java.nio.file.Files`、`java.nio.file.Paths`（3 行）
- 删除：`java.util.concurrent.atomic.AtomicInteger`
- 新增：`org.gscript.vm.debug.dap.json.JsonArray`、`JsonObject`、`JsonParser`、`JsonValue`、`JsonWriter`
- 新增：`org.gscript.util.AtomicCounter`
- 注意：`JsonElement` 无对应物，统一替换为 `JsonValue`

#### 3.2 字段替换
- `private final Gson gson = new Gson();` → 删除
- `private final AtomicInteger seq = new AtomicInteger(0);` → `private final AtomicCounter seq = new AtomicCounter(0);`
- `private final List<String> scriptPaths = new ArrayList<>();` → `private final List scriptPaths = new ArrayList();`（及 compiledBytecodes/compiledConstantPools/compiledSourceLines/varRefs/sourceRefs/frameList 同理）

#### 3.3 JSON API 调用替换
- `gson.toJson(msg)` → `JsonWriter.toJson(msg)`（2 处：sendMessage、handleLaunchAttach 日志）
- `JsonParser.parseString(json).getAsJsonObject()` → 保持不变（API 已对齐）
- `JsonElement` 类型 → `JsonValue`（参数/变量声明，如 `for (JsonElement fe : ...)`）
- `seq.incrementAndGet()` → `seq.incrementAndGet()`（AtomicCounter 同名方法，无需改）
- `args.get("x").getAsString()/getAsInt()/getAsBoolean()` → 保持不变（JsonValue 有这些方法）
- `args.get("x").isJsonObject()/isJsonArray()` → 保持不变（JsonValue 有这些方法）

#### 3.4 增强 for 循环 → 索引/迭代器
- `for (String line : headers.split("\r\n"))` → 索引 for
- `for (JsonElement fe : args.getAsJsonArray("files"))` → `JsonArray arr = args.getAsJsonArray("files"); for (int i = 0; i < arr.size(); i++) { JsonValue fe = arr.get(i); ... }`
- `for (JsonElement be : args.getAsJsonArray("breakpoints"))` → 同上
- `for (JsonElement f : args.getAsJsonArray("filters"))` → 同上
- `for (int line : lines)` → `for (int i = 0; i < lines.size(); i++) { int line = ((Integer) lines.get(i)).intValue(); ... }`
- `for (byte[][] bc : compiledBytecodes)` → 索引 for + cast
- `for (String path : scriptPaths)` → 索引 for + cast
- `for (Map.Entry<String, GSValue> e : vals.entrySet())` → `Iterator it = vals.entrySet().iterator(); while (it.hasNext()) { Map.Entry e = (Map.Entry) it.next(); ... }`（3 处：addEnvVariables、handleVariables 两处）

#### 3.5 java.nio → java.io
- `new String(headerBuf.toByteArray(), StandardCharsets.UTF_8)` → `new String(headerBuf.toByteArray(), "UTF-8")`（2 处）
- `json.getBytes(StandardCharsets.UTF_8)` → `json.getBytes("UTF-8")`
- `new String(body, 0, read, StandardCharsets.UTF_8)` → `new String(body, 0, read, "UTF-8")`
- `new String(buf.toByteArray(), StandardCharsets.UTF_8)`（DapOutputOutputStream.flushLine）→ `new String(buf.toByteArray(), "UTF-8")`
- `Paths.get(framePath).getFileName().toString()` → `new File(framePath).getName()`（handleStackTrace，更简洁且 Java 1.4 兼容）
- `Files.readAllBytes(Paths.get(path))` → 手动读取循环：
  ```java
  FileInputStream fis = new FileInputStream(path);
  ByteArrayOutputStream bos = new ByteArrayOutputStream();
  byte[] buf = new byte[4096];
  int n;
  while ((n = fis.read(buf)) != -1) { bos.write(buf, 0, n); }
  fis.close();
  String content = new String(bos.toByteArray(), "UTF-8");
  ```
- `Files.newInputStream(gclassFile.toPath())`（try-with-resources）→ `new FileInputStream(gclassFile)` + try/finally

#### 3.6 @Override 移除
- 4 处：`onSuspended`（898 行）、`DapOutputOutputStream.write(int)`（1205）、`write(byte[],int,int)`（1213）、`flush()`（1220）

#### 3.7 lambda → 匿名 Runnable
- `startInterpreterThread` 第 1035 行 `Thread t = new Thread(() -> {...}, "gscript-interpreter")` → `new Thread(new Runnable() { public void run() { try {...} catch (...) {...} } }, "gscript-interpreter")`
- 注意：lambda 体内捕获了外部可变列表，已用 `new ArrayList<>(scriptPaths)` 快照，匿名类需改为 final 局部变量捕获

#### 3.8 try-with-resources → try/finally
- `compileScript` 第 982-990 行：`try (InputStream gin = Files.newInputStream(...)) { ... }` → `InputStream gin = null; try { gin = new FileInputStream(gclassFile); ... } finally { if (gin != null) gin.close(); }`

#### 3.9 其他
- `addEnvVariables` 方法签名 `com.google.gson.JsonArray varsArray` → `JsonArray varsArray`
- `String.format` — DapServer 中未使用（grep 确认），无需处理
- 泛型 `Map<String, GSValue> merged` → `Map merged`（handleVariables），取值处 cast `(String) e.getKey()`、`(GSValue) e.getValue()`

### Phase 6 收尾：后台 agent 完成后验证

等待所有后台 agent 完成后，逐文件验证最终状态：

1. **Parser.java** — 确认无重复 agent 竞态破坏（3 个 agent 写同一文件，最后完成者胜出）。grep 验证：无 `GSTokenType[]`、无 `String.format`、无泛型、无钻石、无增强 for
2. **ByteCodeGenerator.java** — 同上（3 个 agent）。grep 验证：无 `@Override`、无 `GSTokenType`（变量/参数）、无泛型、无 `String.format`、无增强 for
3. **gclass/*.java**（6 文件）— grep 验证：无 `StandardCharsets`、无 `import java.nio`、无泛型、无增强 for、无 `@Override`
4. **vm/value/*.java**（12 文件，含 GSString.java 的 8 处 @Override）— grep 验证：无 `@Override`、无泛型、无 `String.isEmpty()`（→ `length()==0`）、无 `StringBuilder`、GSValue.fromJavaObject 无自动装箱
5. **DebugAgent.java + DapCLIMain.java** — grep 验证：无 lambda、无 try-with-resources、无 `@Override`、无泛型、无 `java.nio`
6. **GSInterpreter.java + GSFrame.java + GSEnv.java** — grep 验证：无 `ArrayDeque`（→ LinkedList）、无 `@Override`、无泛型、无 `String.format`
7. **TestScript.java + Test.java** — grep 验证：无 `java.nio.file`、无 `readAllBytes()`、无 try-with-resources、无 `@Override`、无泛型

**验证命令**（每个文件）：
```
grep -nE "@Override|<\w+>|new \w+<>|String\.format|StringBuilder|StandardCharsets|java\.nio|AtomicInteger|readAllBytes" <file>
```
预期：仅 Javadoc 注释中的 `{@literal <X>}` 等匹配，无实际代码匹配。

### Phase 7：编译验证

**约束**：开发机仅 JDK 9.0.4，`-source 1.4` 不支持（最低 1.6）。

**策略**：
1. 先尝试 `mvn compile`（pom 配置 1.4），预期 JDK 9 报 `source release 1.4 requires target release 1.4` 或类似错误
2. 临时改 pom 为 `<source>1.6</source><target>1.6</target>` 编译，**目的**：捕获 Java 7+ 特性（lambda/try-with-resources/String switch/Set.of/Files/StandardCharsets/钻石操作符）
3. 修复所有编译错误（类型不匹配、缺失 import、cast 缺失等）
4. 编译通过后，**grep 全量扫描**确认无 Java 1.5 残留模式：
   - `@Override`（应 0）
   - 泛型 `<Identifier>`（仅 Javadoc 注释允许）
   - `new \w+<>` 钻石（应 0）
   - `String.format`（应 0）
   - `StringBuilder`（应 0，全用 StringBuffer）
   - `for (Type x : ` 增强 for（应 0）
   - `StandardCharsets`、`java.nio`、`AtomicInteger`、`ArrayDeque`、`readAllBytes`（应 0）
5. **恢复 pom 为 1.4**（最终交付物）
6. **已知限制记录**：JDK 9 无法生成 1.4 字节码（class file version）；交付的是 1.4 兼容源码 + 1.6 可编译验证。真实 1.4 字节码需 JDK 8（未安装），但源码已确保无 1.5+ 语法/API。

### Phase 8：全量回归测试

**测试套件**（10 个，~176 用例）：
1. test_timer.py（15）
2. test_host_interaction.py（12）
3. test_gclass.py（65）
4. test_dap_e2e.py（17）
5. test_while_breakpoint.py（9）
6. test_multi_file.py（19）
7. test_cross_file_step.py（7）
8. test_step_catch.py（7）
9. test_path_mismatch.py（3）
10. test_timer_debug.py（22）

**执行**：
1. `cd e:\JProjects\gscript && mvn compile -q`（或 `mvn package -q` 打 jar）
2. `python tests\run_baseline.py` — 生成新 baseline 输出
3. 逐文件对比 `tests/baseline/*.out`（迁移前）与新输出，确认 pass/fail 数量一致
4. **验收标准**：所有测试 pass 数量与原 baseline 一致（原 baseline 显示如 test_timer.out "15 passed, 0 failed"）
5. 若有失败：调试修复 → 重测，直至全绿

**调试验证**（用户要求"使用调试器逐行调试"）：
- 至少跑通一个 DAP 端到端调试场景（test_dap_e2e.py 已覆盖：断点命中、单步、变量查看、调用栈）
- 确认 Gson 替换后 DAP 消息收发正常（initialize/launch/setBreakpoints/configurationDone/stackTrace/scopes/variables/evaluate/disconnect 全链路）

### Phase 9：文档与记忆同步

1. **README.md** — 新增"Java 1.4 兼容性"章节：说明无外部依赖、自研 JSON 库替代 Gson、自研并发原语替代 java.util.concurrent、GSTokenType enum→int 常量、编译要求
2. **project_memory.md** — 新增"Java 1.4 迁移"章节：记录迁移范围、关键设计决策（GSTokenType int 常量、自研 JSON 库 API 对齐 Gson、JDK 9 限制）、经验教训
3. **topics.md** — 同步本次会话摘要

---

## 三、假设与决策

### 假设
1. **JDK 限制**：开发机仅 JDK 9.0.4，无法真正生成 1.4 字节码。交付 1.4 兼容源码，用 1.6 编译验证 + grep 模式扫描确保无 1.5+ 语法。
2. **后台 agent**：11 个 agent 中部分为重复实例（Parser/ByteCodeGenerator 各 3 个），最后完成者胜出。Phase 6 逐文件验证可捕获竞态破坏。
3. **测试 baseline**：`tests/baseline/*.out` 为迁移前输出（10 文件有效），作为迁移后对比基准。

### 关键决策
1. **GSTokenType 用 int 常量而非类型安全 enum 模式**：Java 1.4 switch 仅支持 int/char，项目先例（GSValue.type 用 int），改动最小。
2. **自研 JSON 库 API 对齐 Gson**：JsonObject/JsonArray/JsonParser/JsonWriter 方法名与 Gson 一致，DapServer 改动最小化（多数调用点无需改）。
3. **`JsonElement` → `JsonValue`**：Gson 的 JsonElement 在自研库中对应 JsonValue（基类），所有 `JsonElement` 引用统一替换。
4. **String switch → if-else 链**：Java 1.4 不支持 String switch，Lexer/Parser 中的 String switch 改为 if-else `.equals()` 链。
5. **不安装 JDK 8**：尊重现有环境，不引入新 JDK。1.4 源码兼容性通过 1.6 编译 + grep 双重保证。

---

## 四、验证步骤（最终验收清单）

- [ ] DapServer.java 迁移完成，无 Gson/nio/AtomicInteger/@Override/泛型/lambda/try-with-resources 残留
- [ ] 所有后台 agent 完成，逐文件 grep 验证无 Java 1.5+ 模式
- [ ] `mvn compile`（临时 1.6）成功，零编译错误
- [ ] grep 全量扫描：@Override=0、泛型=0（仅 Javadoc）、钻石=0、String.format=0、StringBuilder=0、StandardCharsets=0、java.nio=0、AtomicInteger=0、ArrayDeque=0
- [ ] pom.xml 恢复为 1.4
- [ ] `python tests\run_baseline.py` 全绿，10 套件 pass 数量与原 baseline 一致
- [ ] test_dap_e2e.py 通过（验证 Gson 替换后 DAP 全链路正常）
- [ ] README.md 新增 Java 1.4 兼容性章节
- [ ] project_memory.md + topics.md 同步迁移记录

---

## 五、执行顺序

1. 等待后台 agent 完成（Parser/ByteCodeGenerator 重复实例可能陆续通知）
2. **手动迁移 DapServer.java**（Phase 3，可立即开始，不依赖 agent）
3. agent 全部完成后，执行 Phase 6 逐文件验证（修复竞态破坏）
4. Phase 7 编译验证 + grep 扫描
5. Phase 8 全量测试 + baseline 对比
6. Phase 9 文档与记忆同步
7. 最终验收清单逐项确认
