# 修复 pause on exceptions 测试 + 改善异常挂起体验

## Summary

错误信息增强（`formatMessage()` 显示 文件:行号 + 函数名 + 调用栈）已实现完成。pause on exceptions 机制（`DebugController.checkException()`）也已存在，但测试 `test_pause_exception.py` 有 3 个检查项失败。

**根因**：测试使用 `debugagent-eval` 模式（`startAttachListener`，非阻塞），第一次 eval 可能在 VSCode 连接并设置 `pauseOnException=true` 之前就抛出异常，导致 `checkException` 未被调用（`debugController` 为 null）。测试看到的 "第二次 stopped" 实际是后续 eval 的 entry 断点，而非异常断点。

**附带问题**：`checkException` 在异常跨帧传播时会被每帧的 catch 块重复调用，导致同一异常触发多次 `stopped(exception)` 事件，用户体验不佳。

## Current State Analysis

### 已完成（无需改动）
- [GSException.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSException.java)：增强构造器 + `formatMessage()` + `appendCaller()` + 调用栈快照
- [GSInterpreter.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java)：5 处 throw 改用增强构造器，2 处 OP_INVOKE/OP_CONSTRUCTOR catch 块加 `appendCaller`，4 处输出改用 `formatMessage()`
- [DebugController.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/debug/DebugController.java)：`checkException()` 已实现（lines 371-403），`pauseOnException` 字段 + `setPauseOnException()` 方法
- [DapServer.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/debug/dap/DapServer.java)：`handleSetExceptionBreakpoints()` 已实现（lines 590-607）

### 失败的 3 个检查项及根因
```
[PASS] 收到第二次 stopped（exception）     ← 收到了 stopped，但不是 exception
[FAIL] 第二次 stopped 原因 = exception      ← 实际 reason="entry"（后续 eval 的入口断）
[PASS] exception stackTrace 有帧
[PASS] 栈顶帧行号 > 0
[FAIL] 栈顶帧函数名 = badCall               ← 实际是 <anonymous>（后续 eval 的顶层帧）
[FAIL] 调用栈深度 >= 2                      ← 实际深度=1（只有顶层帧）
```

**时序问题**：`debugagent-eval` → `startAttachListener()`（非阻塞）→ 主线程立即 `eval()`。
若 VSCode 未及时连接，`debugController` 为 null，`checkException` 不被调用，异常直接打印。
测试的 "第一次 entry 断" 实际是第二次/第三次 eval 的入口断，异常已在第一次 eval 中无声打印。

## Proposed Changes

### Change 1: `test_pause_exception.py` — 改用 `debugagent-waitattach` 模式
**文件**: `tests/test_pause_exception.py`

**Why**: `waitForDebuggerAndAttach` 阻塞主线程直到 VSCode 连接 + `configurationDone`，确保 controller（含 `pauseOnException=true`）在 eval 前就位。

**What**:
- `start_java("debug_exception_test", "debugagent-eval")` → `start_java("debug_exception_test", "debugagent-waitattach")`
- `debugagent-waitattach` 只做 1 次 eval（非 3 次），删除 "后续 eval 继续入口断" 的注释
- 测试流程调整为：entry 断 → continue → exception 断 → continue → terminated
- continue 循环保持不变（处理异常传播到顶层时的第二次 checkException，若 Change 2 未实施）或直接到 terminated（若 Change 2 已实施）
- stdout 检查保持不变（`formatMessage()` 输出增强错误信息）

**预期时序**（waitattach 模式）:
1. Java 启动 → `waitForDebuggerAndAttach` 阻塞
2. Python 连接 → initialize → attach（创建 controller）→ setExceptionBreakpoints（`pauseOnException=true`）→ configurationDone
3. Java 主线程被唤醒，`eval()` → `prepareDebugEntry` → `requestEntryStop` → entryStopRequested=true
4. 首条指令 `suspendCheck` → reason="entry" → **第一次 stopped**
5. Python continue → 解释器执行 `badCall()` → `f()` 抛异常
6. catch 块 `checkException(badCallFrame, depth=2, e)` → reason="exception" → **第二次 stopped**
7. Python continue → 异常传播到顶层（Change 2 后不再挂起）→ `formatMessage()` 打印 → eval 返回
8. `notifyScriptCompleted` → **terminated**

### Change 2: `GSException.java` + `DebugController.java` — 异常只挂起一次
**文件**: `src/main/java/org/gscript/vm/GSException.java`, `src/main/java/org/gscript/vm/debug/DebugController.java`

**Why**: 当前 `checkException` 在异常跨帧传播时被每帧的 catch 块重复调用，同一异常触发多次 `stopped(exception)` 事件。改善为只在 throw 点（首次 `checkException` 调用）挂起一次，后续传播不再挂起。

**What**:

GSException.java — 加 `paused` 标志:
```java
/** 是否已因异常断点挂起过（避免同一异常在跨帧传播时反复挂起） */
private boolean paused = false;

public boolean isPaused() { return paused; }
public void setPaused(boolean paused) { this.paused = paused; }
```

DebugController.java `checkException()` — 开头加 `isPaused` 检查，决定挂起时设 `setPaused(true)`:
```java
public boolean checkException(GSFrame frame, int depth, GSException exception) {
    if (!pauseOnException) {
        return false;
    }
    if (exception.isPaused()) {
        return false;  // 同一异常已挂起过，不再重复挂起
    }
    synchronized (lock) {
        if (terminated) {
            return false;
        }
        suspended = true;
        suspendedFrame = frame;
        suspendedDepth = depth;
        suspendedLine = currentLine(frame);
        suspendedReason = "exception";
        exception.setPaused(true);  // 标记已挂起
    }
    // ... 后续不变（suspendListener.onSuspended + lock.wait）
}
```

**效果**: 异常在 throw 点挂起一次，continue 后异常传播到顶层不再挂起，直接被外层 catch 打印 `formatMessage()`。用户只需 continue 一次即可看到完整错误信息。

## Assumptions & Decisions

1. **不改字节码格式** — 用户明确要求。`paused` 标志是 GSException 的运行时字段，不参与 gclass 序列化。
2. **不改 checkException 的 caught/uncaught 区分** — 当前简化处理（所有 throw 都挂起），测试用 "uncaught" filter 但脚本异常确实是 uncaught，行为正确。后续可按需扩展。
3. **debug_exception_test.script 无需改动** — 已是多行格式（6 行），源码行映射正常。
4. **Java 1.4 兼容** — `paused` 用基本类型 boolean，getter/setter 显式命名，无泛型/注解。

## Verification

1. **编译**: `$env:JAVA_HOME="C:\Program Files\Java\jdk1.8.0_202"; mvn clean package -DskipTests`
2. **单独运行 pause exception 测试**: `python tests/test_pause_exception.py` — 预期 13+ passed, 0 failed
3. **全量回归测试**: 逐个运行 tests/ 下所有 test_*.py，确保 245+ 项全部通过（test_attach_smoke 环境依赖除外）
4. **手动验证错误信息**: `java -cp target/classes org.gscript.TestScript debug_exception_test run` — 预期输出:
   ```
   Uncaught Error: TypeError: function not exist.
     at debug_exception_test.script:3 (in badCall)
     at debug_exception_test.script:6 (in <anonymous>)
   ```
5. **更新 project_memory.md** — 记录 pause on exceptions 修复 + "只挂起一次" 设计决策
