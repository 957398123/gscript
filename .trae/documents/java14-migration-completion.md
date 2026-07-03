# GScript Java 1.4 迁移收尾计划

## 概述

将 GScript 项目从 Java 9+ 迁移到纯 Java 1.4。前期已完成大部分迁移工作（基础设施、JSON 库、并发原语、DapServer 全量重写、GSTokenType 枚举转 int 常量、46 个 node 文件、gclass 7 文件、DebugAgent、ByteCodeGenerator、TestScript、TimerScheduler、Console/TimerLib、GSInterpreter/GSFrame/GSEnv）。本计划聚焦**收尾阶段**：修复剩余未迁移文件、编译验证、测试验证、文档更新。

## 当前状态分析（Phase 1 探索结果）

### 已完成迁移（已验证 clean）
- `pom.xml` — `<source>1.4</source><target>1.4</target>`，零外部依赖，maven-jar-plugin 设 mainClass
- `src/main/java/org/gscript/vm/debug/dap/json/*.java`（7 文件）— 自研 JSON 库，API 对齐 Gson
- `src/main/java/org/gscript/util/*.java`（3 文件）— AtomicCounter/SimpleBlockingQueue/MinPriorityQueue
- `src/main/java/org/gscript/compile/token/GSTokenType.java` — 72 个 `public static final int` 常量
- `src/main/java/org/gscript/compile/token/GSToken.java`、`Lexer.java`
- `src/main/java/org/gscript/compile/node/*.java`（46 文件）
- `src/main/java/org/gscript/compile/gclass/*.java`（7 文件，已用 1.8 编译验证零错误）
- `src/main/java/org/gscript/compile/gen/ByteCodeOptimize.java`
- `src/main/java/org/gscript/compile/Parser.java` — switch 1+嵌套 switch 2 已修复（16 个 case 标签已加 `GSTokenType.` 前缀）
- `src/main/java/org/gscript/compile/gen/ByteCodeGenerator.java` — 43 个 @Override 移除、9 处 type 改 int、41 处 String.format 改拼接、41 处增强 for 改索引
- `src/main/java/org/gscript/vm/debug/dap/DapServer.java` — 全量重写 1290 行，grep 验证 clean
- `src/main/java/org/gscript/vm/debug/DebugAgent.java`、`DapCLIMain.java`
- `src/main/java/org/gscript/vm/stdlib/Console.java`、`TimerLib.java`
- `src/main/java/org/gscript/vm/TimerScheduler.java`
- `src/main/java/org/gscript/vm/GSInterpreter.java`、`GSFrame.java`、`GSEnv.java`（后台 agent 44972ec9 已完成）
- `src/main/java/org/gscript/TestScript.java`、`Test.java`（java.nio→File、readAllBytes→手动读、generics 移除）

### 剩余待迁移（本次计划处理）

#### A. 手动修复 3 个文件（无 agent 处理）

**1. `Parser.java` — switch 3 剩余 3 个未限定 case 标签**
- 位置：`parseAccessPropertyOrMemberExpressionOrCallExpression()` 第 1157/1161/1167 行
- 现状：
  ```java
  case IDENTIFIER: {  // Identifier
  case LPAREN: {      // "(", Expression, ")"
  case LBRACKET: {    // <ArrayLiteral>
  ```
- 修复：加 `GSTokenType.` 前缀 → `case GSTokenType.IDENTIFIER:` 等
- 原因：int 常量 switch 要求 case 标签用限定名（枚举 switch 才允许非限定名）

**2. `DebugController.java` — 5 处 generics + 3 处 autoboxing**
- 第 55 行：`Map<String, Set<Integer>> breakpoints = new HashMap<>()` → `Map breakpoints = new HashMap()`
- 第 74 行：`WeakHashMap<GSFrame, Integer> framePrevLines = new WeakHashMap<>()` → `WeakHashMap framePrevLines = new WeakHashMap()`
- 第 133 行：`Collection<Integer> lines` → `Collection lines`
- 第 138 行：`new HashSet<>(lines)` → `new HashSet(lines)`
- 第 249 行：`Set<Integer> bps = breakpoints.get(...)` → `Set bps = (Set) breakpoints.get(...)`
- 第 231 行：`Integer prevLineObj = framePrevLines.get(frame)` → `Integer prevLineObj = (Integer) framePrevLines.get(frame)`
- 第 250 行：`bps.contains(line)` → `bps.contains(new Integer(line))`（避免 autoboxing）
- 第 297 行：`framePrevLines.put(frame, line)` → `framePrevLines.put(frame, new Integer(line))`

**3. `TestScript.java` — 2 处 String.format**
- 第 98 行：`String.format("%4d [line %-3d] %s", i, ln, bc.get(i))` → 字符串拼接
- 第 197 行：`String.format("%4d [line %-3d] %s", i, ln, text)` → 字符串拼接
- 拼接形式：`("" + i + " [line " + ln + "] " + bc.get(i))`，用 `padLeft` 辅助或直接简化为无填充格式（dump 输出仅调试用，格式微调可接受；但为保持 baseline 一致，尽量还原 `%4d`/`%-3d` 填充效果——用辅助方法实现）

#### B. 等待并验证运行中 agent

**后台 agent 24bc6ade（vm/value/*.java，11 文件）仍在运行**
- 已知待处理：`GSArray.java:17` StringBuilder、`GSFunction.java:121` String.format、`GSNativeFunction.java:42` String.format、`GSString.java` 8 处 @Override
- 策略：**不重复 spawn**，等待完成通知，完成后 grep 验证全部 11 文件 clean

#### C. 更新测试脚本 classpath

**`tests/test_timer_debug.py` — 移除 Gson jar 引用**
- 第 30-38 行：`GSON_JAR`/`DEBUG_CP` 定义移除 Gson
- 修复：`DEBUG_CP = CLASSES_DIR`（自研 JSON 库已编译进 target/classes，无需外部 jar）
- 第 156 行注释同步更新

## 假设与决策

1. **JDK 9.0.4 限制**：本机仅装 JDK 9，不支持 `-source 1.4`（最低 1.6）。
   - 决策（前期已定）：pom.xml 保持 `<source>1.4</source><target>1.4</target>`（源级合规，真实 JDK 1.4 可编译）；本机验证用临时 1.6 编译 + grep 扫描 1.5 语法模式。
   - 风险：1.5-only API（如 `StringBuilder`、`String.format`）无法被 1.6 编译捕获，依赖 grep 兜底。已扫描确认仅余上述已列明位置。

2. **baseline 对比**：`tests/baseline/*.out`（10 文件）为迁移前输出。迁移后语义应完全一致，仅 dump 格式可能因 String.format→拼接 有微调（TestScript.java 第 98/197 行）。若 dump 输出格式变化导致 baseline 不匹配，需在辅助方法中还原 `%4d`/`%-3d` 填充。

3. **不改动业务逻辑**：仅做语法/API 机械化迁移，不改变字节码生成、调试器协议、运行时语义。

## 实施步骤

### Step 1: 等待 vm/value agent 完成（被动）
- 监听 agent 24bc6ade 完成通知
- 完成后：`Grep` 扫描 `src/main/java/org/gscript/vm/value/*.java` 确认零 Java 5+ 模式
- 若 agent 报告有遗漏，手动补修

### Step 2: 修复 Parser.java switch 3
- 用 `Edit` 替换第 1156-1167 行的 switch 块，3 个 case 标签加 `GSTokenType.` 前缀
- 验证：`Grep "^\s*case [A-Z]"` 在 Parser.java 应返回 0 匹配

### Step 3: 迁移 DebugController.java
- 顺序 `Edit`（同文件多 Edit 需串行，避免并行丢失）：
  1. 第 55 行 generics 移除
  2. 第 74 行 generics 移除
  3. 第 133 行 signature 改 raw Collection
  4. 第 138 行 diamond 移除
  5. 第 231 行加 (Integer) cast
  6. 第 249 行改 raw Set + cast
  7. 第 250 行 autoboxing 修复
  8. 第 297 行 autoboxing 修复
- 验证：`Grep` 确认 DebugController.java 无 `<` 泛型、无 diamond

### Step 4: 修复 TestScript.java String.format
- 第 98/197 行替换为字符串拼接 + 辅助填充方法（保持 `%4d`/`%-3d` 对齐效果）
- 在 TestScript 末尾或 Test.java 加 `private static String pad(int n, int width)` / `padLeft` 辅助方法
- 验证：`Grep "String\.format"` 在 TestScript.java 应返回 0 匹配

### Step 5: 更新 test_timer_debug.py classpath
- 第 30-38 行：删除 GSON_JAR 定义，`DEBUG_CP = CLASSES_DIR`
- 第 156 行注释更新

### Step 6: 编译验证（Phase 7）
- 临时改 pom.xml `<source>1.6</source><target>1.6</target>`
- 运行 `mvn -q compile`（cwd=e:\JProjects\gscript）
- 修复所有编译错误（预期：少量 cast 缺失、类型不匹配）
- 编译通过后恢复 pom.xml 为 `<source>1.4</source><target>1.4</target>`
- 最终全量 grep 扫描：
  ```
  Grep pattern: \b(List|Map|Set|ArrayList|HashMap|HashSet|LinkedList|Collection|Iterator|WeakHashMap|Stack)<|new\s+\w+<>|@Override|String\.format\s*\(|StringBuilder|java\.nio|AtomicInteger|StandardCharsets|com\.google\.gson|for\s*\([A-Z]\w+\s+\w+\s*:|try\s*\(
  path: src/main/java
  ```
  预期：仅 JAVADOC 注释中的 `{@code List<String>}` 等文档性引用（无实际代码）

### Step 7: 测试验证（Phase 8）
- 运行 `python tests\run_baseline.py`（会覆盖 tests/baseline/*.out —— **需先备份**）
- 改为先输出到临时目录对比：
  - 复制 `run_baseline.py` 逻辑，输出到 `tests/post_migration/`
  - 用 `diff` 或 Python 脚本对比 `tests/baseline/*.out` vs `tests/post_migration/*.out`
- 全套 10 个测试套件应全部通过，输出与 baseline 一致
- 若有失败：分析原因，修复，重跑

### Step 8: 文档与记忆更新（Phase 9）
- **`README.md`**：若提及 Gson/Java 9+ 依赖，更新为"纯 Java 1.4，零外部依赖"
- **`c:\Users\95739\.trae-cn\memory\projects\-e-JProjects-gscript\project_memory.md`**：
  - 更新"技术栈"段：Java 1.4（从 Java 9+ 迁移完成）、零外部依赖、自研 JSON 库 + 并发原语
  - 新增"Java 1.4 迁移"章节：记录迁移范围、关键决策（GSTokenType 枚举→int、自研 JSON 库 API 对齐 Gson、JDK 9 验证策略）、经验教训
- **`c:\Users\95739\.trae-cn\memory\projects\-e-JProjects-gscript\20260703\topics.md`**：追加本次 session 的 topic 摘要

## 验证清单

- [ ] Parser.java 无未限定 case 标签
- [ ] DebugController.java 无 generics/diamond/autoboxing
- [ ] TestScript.java 无 String.format
- [ ] vm/value/*.java（11 文件）无 Java 5+ 模式（agent 完成后验证）
- [ ] test_timer_debug.py 不再引用 Gson jar
- [ ] `mvn compile`（临时 1.6）零错误
- [ ] 全量 grep 扫描 src/main/java 仅余 JAVADOC 文档性引用
- [ ] pom.xml 恢复为 1.4/1.4
- [ ] 10 个测试套件全部通过
- [ ] post-migration 输出与 baseline 一致（dump 格式允许填充效果等价）
- [ ] project_memory.md / topics.md / README.md 已更新

## 风险与回滚

- **编译错误风险**：DebugController.java autoboxing 修复可能遗漏边界（如 `bps.contains(line)` 若 line 为 int 会自动装箱，1.4 下需显式 `new Integer(line)`）。逐处核对。
- **baseline 不匹配风险**：TestScript.java dump 格式化替换可能改变输出。用辅助方法还原填充。
- **agent 遗漏风险**：vm/value agent 可能遗漏文件。完成后全量 grep 兜底。
- **回滚**：所有改动均在 git 工作区，必要时 `git diff` 审查、`git checkout` 回滚单文件。
