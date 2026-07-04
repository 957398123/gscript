# 增强 gscript 运行/调试异常信息

## Context

用户运行 gscript 时控制台报 `Uncaught Error: TypeError: function not exist. at <anonymous>:524`，无法定位错误：
- **524 是字节码 IP，不是源码行号**——对用户毫无意义
- **没有源码文件路径**——多文件场景无法定位
- **没有函数名**——不知道在哪个函数里
- **没有调用栈**——不知道是从哪里调用过来的
- 即便调试模式一步步步进，异常直接传播到顶层 catch 打印，不会挂起（除非用户在 VSCode 手动勾选 exception filter）

## 现状（关键发现）

经探索，**pause on exceptions 的调试器侧机制已完整实现**：
- [DebugController.checkException()](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/debug/DebugController.java#L371-L403) 已实现挂起逻辑（reason="exception"）
- [GSInterpreter.eval 的 catch 块](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java#L784-L789) 已调用 `debugController.checkException(frame, callStack.size(), e)`
- [DapServer.handleSetExceptionBreakpoints](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/debug/dap/DapServer.java#L590-L607) 已处理 DAP 协议（caught/uncaught filter）
- [DebugController.setPauseOnException](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/debug/DebugController.java#L160-L162) 已实现
- capabilities 已声明 exceptionBreakpointFilters

**挂起期间 callStack 完整**（eval 的 finally 还没 pop），VSCode stackTrace 请求能拿到完整调用栈。所以 pause on exceptions 模式下信息是齐全的——只需用户在 VSCode 勾选 exception filter。

**真正缺失的是未捕获异常打印时的信息**（4 处 `System.out.println("Uncaught Error: ... at <anonymous>:" + e.getIp())`），以及非调试模式根本没 sourceLines 可映射。

## 方案

### 1. GSException 增强（核心）

[GSException.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSException.java) 当前只有 `function` / `ip` / `origin`。增强为携带源码映射 + 调用栈快照：

- **新增字段**：
  - `sourceLines` (int[])、`sourcePath` (String)、`baseOffset` (int) — 从 throw 点 frame.function 快照
  - `callStack` (ArrayList) — 传播路径上的 caller 帧信息（每项 StackFrameInfo）
- **新增内部静态类** `StackFrameInfo`：`function` / `sourcePath` / `sourceLine`
- **新增构造器**：接收 `frame`（GSFrame），从中提取 function.name / sourceLines / sourcePath / baseOffset / ip
- **新增方法**：
  - `getSourceLine()` — 用 `sourceLines[baseOffset + (ip - 1)]` 计算 throw 点源码行（与 [DebugController.currentLine](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/debug/DebugController.java#L452-L462) 同公式）
  - `getSourcePath()` — 返回 sourcePath
  - `appendCaller(GSFrame callerFrame)` — 异常跨函数传播时追加 caller 帧（计算 caller 的源码行 + 文件 + 函数名）
  - `formatMessage()` — 统一格式化：`Uncaught Error: <msg>` + 顶层位置 + 调用栈每帧
- **保留** 原 `setIp`/`getIp`/`function`（[frame.handleException](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSFrame.java#L113-L166) 用 ip 做 monitor 范围比对，不能去掉）

**注意**：`ip` 字段会被 `setIp()` 重定位到 caller 的 invoke 指令位置（用于 handleException monitor 比对）。throw 点的原始源码行需要在 `appendCaller` 之前**先快照**到 `getSourceLine()` 用的字段。具体：构造时用 throw 点的 frame+ip 算出 `originSourceLine` 存起来（不被 setIp 影响）；callStack 每项也各自存自己 frame 当时的源码行。

### 2. 5 处 throw 点改造（[GSInterpreter.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java)）

5 处 `throw new GSException(frame.function.name, frame.getIP() - 1, ...)` 改为新构造器（传 frame）：
- [line 389](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java#L389) "Cannot read properties of null"
- [line 405](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java#L405) "Cannot set properties of null"
- [line 658](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java#L658) "function not exist"
- [line 704](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java#L704) "constructor function not exist"
- [line 738](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java#L738) OP_THROW（用户脚本 throw）

### 3. 2 处异常传播点追加 caller

异常跨函数传播时追加 caller 帧到 callStack：
- [OP_INVOKE catch 块 line 650](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java#L646-L651)：`ex.setIp(frame.getIP() - 1);` 之后加 `ex.appendCaller(frame);`
- [OP_CONSTRUCTOR catch 块 line 684](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java#L682-L686)：同上

`appendCaller` 内部用 caller frame 当前的 getIP()-1（= invoke 指令位置）+ sourceLines/baseOffset 算 caller 源码行。

### 4. 4 处错误输出点改造

改为统一调用 `e.formatMessage()`：
- [line 869](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java#L869) 5-arg eval 顶层
- [line 978](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java#L978) evalScript 非调试分支
- [line 1000](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java#L1000) evalExpression 调试分支
- [line 1302](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java#L1302) runEventLoop 定时器回调（当前最简陋，连 at 位置都没有）

**输出格式**（Java 1.4 兼容，StringBuffer 拼接）：
```
Uncaught Error: TypeError: function not exist.
  at script.script:42 (in function add)
  at script.script:15 (in function main)
  at script.script:3 (in <anonymous>)
```
- 无 sourceLines 时回退到 `<anonymous>:<ip>`（兼容老行为）
- 无 sourcePath 时显示 `<anonymous>`
- callStack 顺序：顶层 throw 点在最前，caller 依次在后（从内到外）

### 5. 非调试模式 sourceLines

当前 [compile()](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java#L921-L930) 简化路径丢弃了 `ByteCodeGenerator.getSourceLines()`。改造：

- [EncodedBytecode.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/compile/gclass/EncodedBytecode.java) 加 `public int[] sourceLines` 字段（可为 null）
- `compile()` 填充 `encoded.sourceLines`（复用 [compileScriptContent line 1140-1142](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java#L1140-L1142) 的 sourceLines 转换逻辑）
- [evalScript 非调试分支 line 951-952](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java#L950-L953)：传 `encoded.sourceLines` 给 4-arg eval
- [evalExpression 非调试分支 line 972-974](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java#L971-L974)：手动设 `anonymous.sourceLines = encoded.sourceLines;`

这样非调试模式异常也能映射源码行。sourcePath 仍为 null（显示 `<anonymous>`），因为非调试模式无文件路径概念。

## 不需要改的部分

- DebugController.checkException — 已实现，pause on exceptions 挂起逻辑可用
- DapServer.handleSetExceptionBreakpoints — 已实现 DAP 协议
- DebugController.setPauseOnException — 已实现
- SuspendListener "exception" reason — 已支持

**pause on exceptions 只需用户在 VSCode 的 Breakpoints 面板勾选 "Caught Exceptions" / "Uncaught Exceptions"**，异常抛出时自动挂起在 throw 行，VSCode 显示完整调用栈 + 变量。本方案无需改这部分代码，但会在验证环节确认其确实工作。

## Java 1.4 兼容性注意

- 无泛型：`ArrayList` 而非 `ArrayList<StackFrameInfo>`
- 无自动装箱：`new Integer(line)` 显式包装
- 无增强 for：用索引或 Iterator
- 无 String.format：用 StringBuffer 拼接
- 无三目运算 lub 陷阱：`String.valueOf(...)` 统一类型
- 用 JDK 1.8 编译：`$env:JAVA_HOME="C:\Program Files\Java\jdk1.8.0_202"; mvn clean package -DskipTests`

## 文件清单

| 文件 | 改动 |
|------|------|
| `src/main/java/org/gscript/vm/GSException.java` | 加字段/构造器/方法/内部类（核心） |
| `src/main/java/org/gscript/vm/GSInterpreter.java` | 5 处 throw + 2 处传播 + 4 处输出 + compile/evalScript/evalExpression |
| `src/main/java/org/gscript/compile/gclass/EncodedBytecode.java` | 加 sourceLines 字段 |

## 验证

### 1. 全量回归测试
```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk1.8.0_202"
mvn clean package -DskipTests
python tests/run_all.py
```
预期 228 项全通过（test_host_interaction 的 "Uncaught Error" 断言只检查关键字存在，新格式仍包含 "Uncaught Error"，应通过；若断言失败需同步更新 baseline）。

### 2. 手动验证错误信息增强
写一个会触发 "function not exist" 的脚本（如 `var f = null; f();`），运行查看输出应类似：
```
Uncaught Error: TypeError: function not exist.
  at test.script:1 (in <anonymous>)
```
再写一个跨函数调用的（`function add(a,b){return a+b;} function main(){var f=null; f();} main();`），验证调用栈：
```
Uncaught Error: TypeError: function not exist.
  at test.script:2 (in function main)
  at test.script:3 (in <anonymous>)
```

### 3. 验证 pause on exceptions（调试模式）
- VSCode launch.json 配置 attach 模式
- Breakpoints 面板勾选 "Uncaught Exceptions"
- 运行会抛异常的脚本
- 预期：VSCode 在 throw 行挂起，调用栈面板显示完整调用链，变量面板可查看局部变量
- 这部分机制已存在，主要验证确实工作（若有 bug 再修）

### 4. 更新 baseline
若测试输出格式变化导致 baseline 不匹配，更新 `tests/baseline/` 和 `tests/baseline_new/`（先确认新格式正确）。
