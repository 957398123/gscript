# 实现 setTimeout / setInterval（方案 B：单线程 + 守护定时器线程）

## Context

gscript 当前是纯同步单线程解释器，无事件循环、无任务队列、原生函数无法回调 gscript 函数。用户希望增加类 JS 的 `setTimeout`/`setInterval`。

选定方案 B：**JS 单线程语义不变**——守护线程只负责计时，到期任务入队，由主解释器线程串行执行回调。这样调试器（强假设单线程、`THREAD_ID=1`）几乎无感，闭包也天然安全。

预期结果：gscript 支持 `setTimeout(cb, ms, ...args)` / `setInterval(cb, ms, ...args)` / `clearTimeout(id)` / `clearInterval(id)`；回调里断点/单步/变量查看正常工作；调试模式下所有定时器排空后才发 terminated。

## 核心设计

### 执行模型
- 主脚本 `eval()` 返回后，**同一线程**进入 `runEventLoop()`：循环 `scheduler.poll(timeout)` 取到期任务 → `callFunction(task.cb, task.args)` 同步执行 → 直到 scheduler 无 pending 任务
- 守护线程（`TimerScheduler`）只计时 + 把到期任务塞入 `BlockingQueue readyQueue`，**不跑字节码**
- 单线程串行：定时器回调、调试器挂起检查、callStack 操作都在主线程，无并发陷阱

### 调试器影响（极小）
- `THREAD_ID=1` 不变、callStack 不变、`suspendCheck` 在回调里天然工作（`callFunction` 走 `eval` → push frame → 每条指令检查 controller）
- 唯一改动：**terminated 时机**从"eval 返回"改为"runEventLoop 返回"
- `DebugAbortException`（用户 disconnect）传播出 event loop，走 catch 分支不发 terminated（保持现有 attach 行为）

## 新增文件

### 1. `src/main/java/org/gscript/vm/TimerScheduler.java`
守护定时器调度器，与 GSInterpreter 解耦（不持有 interpreter 引用）。

- 内部类 `TimerTask`：`int id; GSFunction callback; ArrayList<GSValue> args; long nextRunTime; long period;`（period>0 表示 setInterval）
- 字段：
  - `PriorityQueue<TimerTask> timerQueue`（按 nextRunTime 排序，守护线程等待用）
  - `BlockingQueue<TimerTask> readyQueue`（到期任务，主线程 poll）
  - `Map<Integer, TimerTask> taskMap`（cancel 查找用）
  - `AtomicInteger nextId`
  - `Thread timerThread`（守护线程）
  - `volatile boolean stopped`
- 方法：
  - `int schedule(GSFunction cb, long delayMs, ArrayList<GSValue> args)` — setTimeout
  - `int scheduleAtFixedRate(GSFunction cb, long periodMs, ArrayList<GSValue> args)` — setInterval
  - `boolean cancel(int id)` — clearTimeout/clearInterval 共用
  - `TimerTask pollReady(long timeoutMs)` — 主线程取到期任务（带超时，0 表示非阻塞）
  - `boolean hasPending()` — timerQueue + readyQueue 都空且无活跃 setInterval
  - `void shutdown()` — 停止守护线程
- 守护线程循环：peek timerQueue 头部 → `wait(剩余时间)` 或被新增任务的 notify 唤醒 → 到期则 poll 塞入 readyQueue 并 notify 主线程 → 若 period>0 重新计算 nextRunTime 塞回 timerQueue

### 2. `src/main/java/org/gscript/vm/stdlib/TimerLib.java`
全局函数注册（仿 [Console.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/stdlib/Console.java) 模式）。

- 构造捕获 `GSInterpreter interpreter`
- 提供 4 个 `GSNativeFunction`：`setTimeout`/`setInterval`/`clearTimeout`/`clearInterval`
- `setTimeout(args)`：`args[1]`=回调 GSValue(type=6 GSFunction)、`args[2]`=delay(GSInt→long)、`args[3..]`=传给回调的额外参数；构造回调 args 列表 `[GSNull.NULL(this), ...extraArgs]`；调 `interpreter.scheduleTimeout(...)` 返回 `GSInt(id)`
- `clearTimeout(args)`：`args[1]`=id，调 `interpreter.cancelTimer(id)`

## 修改文件

### 3. [GSInterpreter.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java)
- 新增字段 `private TimerScheduler timerScheduler;`（lazy，首次 schedule 时 `new`）
- 新增方法：
  - `public GSValue callFunction(GSFunction fn, ArrayList<GSValue> args)` — 包装 private `eval(GSFrame, args)`（:112）：`new GSFrame(fn)` → 记录 `stack.size()` → `eval(frame, args)` → 返回 `stack.size()>记录值 ? stack.pop() : GSNull.NULL`。这是 native 回调 gscript 函数的唯一入口。
  - `public int scheduleTimeout(GSFunction cb, long delay, ArrayList<GSValue> args)` — 委托 scheduler
  - `public int scheduleInterval(GSFunction cb, long period, ArrayList<GSValue> args)`
  - `public void cancelTimer(int id)`
  - `public void runEventLoop()` — 核心循环：
    ```
    while (timerScheduler != null && timerScheduler.hasPending()) {
        TimerTask task = timerScheduler.pollReady(timeout);
        if (task == null) continue;
        try { callFunction(task.callback, task.args); }
        catch (DebugAbortException e) { throw e; }  // 传播，终止 loop
        catch (Throwable e) { e.printStackTrace(); }  // 回调异常不终止 loop（类 JS）
    }
    if (timerScheduler != null) timerScheduler.shutdown();
    ```
  - `public void installTimerGlobals()` — 注册 TimerLib 的 4 个函数到 global（`addVariableToGlobal("setTimeout", ...)` 等）

### 4. [TestScript.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/TestScript.java)
在创建 interpreter 后调 `installTimerGlobals()`，eval 后调 `runEventLoop()`：
- `gen()` :102-106 — eval 后 `interpreter.runEventLoop()`
- `runGclass()` :164-166 — 同上
- `debugAgent()` attachReady 分支 :301-311 — eval 后 `interpreter.runEventLoop()` 再 `agent.stop()`
- `hostTest()` 若用到定时器也加（按需）
- waitForDebugger 分支不用改（eval 在 DebugAgent.onConfigurationDone 内）

### 5. [DapServer.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/debug/dap/DapServer.java) `startInterpreterThread()` :1032-1064
在 try 块内所有 eval 完成后（:1048 `所有文件 eval 正常结束` 之后）插入：
```java
interpreter.runEventLoop();
```
`DebugAbortException` catch 块（:1049）不跑 event loop（用户主动终止）。其他 catch 也不跑。terminated 发送（:1060）保持原位（此时 event loop 已退出）。

### 6. [DebugAgent.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/debug/DebugAgent.java) `onConfigurationDone()` :242-259
同 DapServer：try 块正常完成后（:247 之后、catch 之前）插入 `interp.runEventLoop()`。注意解释器创建处 :236-238 需加 `interpreter.installTimerGlobals()`（与 console 并列）。

## 关键正确性要点

1. **GSFunction.env 重入安全**：回调函数字节码以 `OP_PUSHENV` 开头，`PUSHENV` 创建新 env（parent=当前 function.env），`GSFrame.destroy()` 把 function.env 恢复到 env.parent（即 PUSHENV 前的值）。多次 callFunction 同一 GSFunction，env 链正确往返。已在 [GSFrame.java:218-236](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSFrame.java#L218) 注释确认。
2. **args 约定**：`callFunction` 的 args 须按 `OP_INVOKE` 约定，`args[0]`=this（回调用 `GSNull.NULL`），实际参数从 index 1。
3. **回调无 return 时栈状态**：`callFunction` 用 `stack.size()` 前后对比决定是否 pop，避免误 pop 调用者栈。
4. **setInterval 永不退出**：纯 setInterval 脚本 event loop 永不返回。普通 run 模式进程常驻，需 Ctrl+C；调试模式靠 disconnect 触发 `DebugAbortException` 退出。文档需说明。
5. **回调异常**：gscript 异常（GSException）在 runEventLoop 捕获打印、继续下一个任务（类 JS 全局错误处理）；`DebugAbortException` 传播终止循环。

## 验证（测试计划）

### 1. 新建测试脚本 `src/main/resources/timer_test.script`
覆盖：setTimeout 基本触发、传参、延迟顺序、setInterval 多次、clearInterval、clearTimeout、0 延迟、嵌套 setTimeout、回调里读闭包变量。

### 2. 新建 Python 测试 `tests/test_timer.py`（仿现有测试风格）
跑 `TestScript timer_test run`，校验 stdout 输出顺序符合预期。至少 12 用例。

### 3. semantics_test 扩展
若 semantics_test 是 gscript 内部断言式测试，新增 [24] 定时器语义用例。

### 4. 调试器测试
- **debugagent 模式**：`TestScript timer_test debugagent`，在 setTimeout 回调行设断点，验证：断点命中、单步进入、变量查看、callStack 显示回调帧、continue 后定时器继续、所有定时器结束后才发 terminated。
- **debugagent-attach 模式**（用户特别要求）：脚本用 `setInterval` 持续输出计数，`TestScript timer_test debugagent-attach` 启动后 VSCode attach，在回调设断点验证运行时附加命中；disconnect 后 `DebugAbortException` 退出 event loop。
- 通过 `dap_debug.log` 验证 terminated 时机正确。

### 5. 回归
跑全部现有 139 测试（gclass/host/e2e/multi_file/while/step_catch/cross_file/path_mismatch）确认无回归。

## 文档同步
- [README.md](file:///e:/JProjects/gscript/README.md)：新增「定时器 API」章节（setTimeout/setInterval/clearTimeout/clearInterval 语义、单线程协作式说明、setInterval 永不退出提示）
- 项目记忆 `project_memory.md`：新增「定时器与事件循环」章节

## 风险与回退
- 风险低：改动集中在新增文件 + 5 处入口的 2 行插入 + 2 处调试器收尾的 1 行插入，不碰字节码、不碰语法、不碰调试器线程模型
- 回退简单：删除 TimerScheduler/TimerLib，还原 5 处入口与 2 处调试器收尾的插入行
