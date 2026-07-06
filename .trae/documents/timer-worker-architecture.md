# 定时器 EventLoop Worker 化改造

## Context

### **问题**:当前 gscript 定时器模型要求宿主在主线程显式调用 `runEventLoop()` 阻塞 pump 定时器队列。这违背了「定时器是引擎内部职责」的语义——主线程(创建解释器的线程)被阻塞,无法继续业务逻辑;且多线程业务场景下,外部线程无法安全地随时调用 gscript 执行代码(`stack`/`callStack` 是非线程安全的 `LinkedList`,靠单线程串行保证)。

**目标**:改造为「内部 worker 线程后台驱动 + 外部调用线性化提交」模型(方案 1,V8 Isolate 模型)。外部线程(主线程/业务线程)随时调用 `eval`/`evalScript`/`evalExpression`/`callFunction` 等 API,这些调用被包装成任务投递到统一队列,由常驻 worker 线程串行执行。调用方阻塞等结果(线性化语义)。定时器回调也走同一队列,与外部调用天然串行。`runEventLoop()` 语义升级为 `awaitIdle`(等待 worker 排空),调用点零改动。

**预期收益**:

* 主线程不再被 `runEventLoop` 阻塞;业务宿主 eval 完脚本主体后即可返回,定时器回调由 worker 后台执行

* 多业务线程调用 gscript 安全线性化,无需宿主加锁

* 调试器单线程假设保持(THREAD\_ID=1 逻辑 id 不变,callStack 固定在 worker)

***

## 设计概览

### 线程模型(改造后)

| 线程                               | 职责                                                      | 跑字节码?     |
| -------------------------------- | ------------------------------------------------------- | --------- |
| `gscript-worker`(常驻守护)           | 独占解释器执行权:消费 taskQueue,执行所有 eval/callFunction/定时器回调      | ✅ 全部      |
| `gscript-timer`(守护)              | 仅计时,到期后通过 `TaskDispatcher.dispatch(task)` 投递到 taskQueue | ❌         |
| `gscript-interpreter`(launch 模式) | 提交 eval 给 worker + awaitIdle 等待 + 发 terminated          | ❌(改为监督线程) |
| 外部业务线程                           | 调用 eval/callFunction → 包装 EvalTask 提交 → 阻塞 await        | ❌         |

### 任务队列统一

两类任务都进 `taskQueue`(`SimpleBlockingQueue`):

* **外部提交任务**:eval/callFunction/evalScript/evalExpression 包装为 `EvalTask` 子类

* **定时器到期任务**:TimerScheduler 到期后 `dispatch(task)` → GSInterpreter 包装为 `TimerEvalTask` → offer 到 taskQueue

worker 主循环:`nextDelayMs()` 作 poll 超时,超时表示定时器到点(TimerScheduler 已 dispatch),继续取 taskQueue。

### 双路径(避免递归死锁)

`Thread.currentThread() == workerThread` 判断:

* **worker 自己调用**(如定时器回调内 callFunction、eval 内部 OP\_INVOKE)→ 直接执行(`xxxDirect`)

* **外部线程调用** → 包装 EvalTask + `submitAndAwait` 阻塞等结果

***

## 改动清单

### 1. `src/main/java/org/gscript/util/SimpleBlockingQueue.java`

新增两个方法(现有 API 不变):

```java
/** 无限阻塞取头部元素(worker 主循环用,无定时器时阻塞等外部任务) */
public synchronized Object take() throws InterruptedException {
    while (queue.isEmpty()) wait();
    return queue.removeFirst();
}

/** 清空队列(shutdown 排空剩余任务用) */
public synchronized void clear() { queue.clear(); }
```

### 2. `src/main/java/org/gscript/vm/TimerScheduler.java`

**解耦改造:移除 readyQueue,引入 TaskDispatcher 接口**

* 新增嵌套接口:`public static interface TaskDispatcher { void dispatch(TimerTask task); }`

* 字段:删除 `readyQueue`,新增 `private final TaskDispatcher dispatcher`

* 构造器:`public TimerScheduler(TaskDispatcher dispatcher)`

* 计时线程到期分支(原 line 113-129):`readyQueue.offer(task)` 替换为 `dispatcher.dispatch(task)`,**仍在** **`synchronized(lock)`** **内**(保留原 hasPending 窗口期修复语义)

* 删除 `pollReady(long)`

* `nextDelayMs()` / `hasPending()` 保留,语义收窄为 timerQueue-only(readyQueue 已不存在)

* `schedule` / `scheduleAtFixedRate` / `cancel` / `shutdown` 不变

### 3. `src/main/java/org/gscript/vm/GSInterpreter.java`(主改造)

#### 新增字段

```java
private Thread workerThread;
private final SimpleBlockingQueue taskQueue = new SimpleBlockingQueue();
private volatile boolean workerBusy = false;   // awaitIdle 观察,避免"队列空但 worker 正在跑"误判
private volatile boolean workerStopped = false;
```

#### 类声明

```java
public class GSInterpreter implements TimerScheduler.TaskDispatcher {
```

#### 构造器(原 line 85)

```java
public GSInterpreter() {
    ensureTimerScheduler();      // new TimerScheduler(this)
    installTimerGlobals();
    installTypeGlobals();
    startWorker();               // 新增
}
```

#### TaskDispatcher 实现

```java
public void dispatch(TimerScheduler.TimerTask task) {
    taskQueue.offer(new TimerEvalTask(task));
}
```

#### EvalTask 类体系(非静态内部类,访问 workerStopped)

```
EvalTask (abstract, non-static inner)
  字段: lock, done, error
  方法: abstract run() / final execute() / final await() / final signalError() / final getError()
├─ RunnableEvalTask  — 包装 void 任务(eval 全家桶)
├─ CallableEvalTask  — 包装返回 GSValue 任务(callFunction/evalExpression),含 awaitResult()
└─ TimerEvalTask     — 包装定时器回调,run() 调 callFunctionDirect

TaskRunnable / TaskCallable (static interface, 1.4 兼容替代 lambda)
```

`execute()`:try { run() } catch(Throwable) { 存 error } finally { done=true; notifyAll }
`await()`:synchronized 等待 done,有 error 则重抛

#### worker 主循环

```java
private void runWorkerLoop() {
    while (!workerStopped) {
        long delay = (timerScheduler != null) ? timerScheduler.nextDelayMs() : -1L;
        EvalTask task = null;
        try {
            task = (delay < 0) ? (EvalTask) taskQueue.take()
                               : (EvalTask) taskQueue.poll(delay);
        } catch (InterruptedException e) {
            if (workerStopped) break;
            continue;
        }
        if (task == null) continue;  // 定时器到点,TimerScheduler 已 dispatch
        workerBusy = true;
        try { task.execute(); }
        finally { workerBusy = false; }
        // 定时器任务的 DebugAbortException:停止 worker(对齐原 runEventLoop 行为)
        if (task instanceof TimerEvalTask) {
            Throwable err = task.getError();
            if (err instanceof DebugAbortException) {
                workerStopped = true;
                if (timerScheduler != null) timerScheduler.shutdown();
                drainTaskQueue();
                break;
            }
        }
    }
}
```

#### eval 全家桶双路径统一模式

```java
public void evalXxx(final 参数...) {
    if (Thread.currentThread() == workerThread) {
        evalXxxDirect(参数...);
        return;
    }
    try {
        submitAndAwait(new RunnableEvalTask(new TaskRunnable() {
            public void run() throws Throwable { evalXxxDirect(参数...); }
        }));
    } catch (RuntimeException e) { throw e; }
    catch (Throwable e) { throw new RuntimeException(e); }
}
private void evalXxxDirect(参数...) { /* 原实现原样搬入 */ }
```

**需直接改造的入口(3 个,直接执行字节码)**:

* `eval(byte[][], Object[], int[], String, String)` line 868 → 双路径 + evalDirect

* `evalScript(String)` line 967 → 双路径 + evalScriptDirect

* `evalExpression(String)` line 994 → 双路径(CallableEvalTask)+ evalExpressionDirect

**自动复用包装的入口(5 个,内部委托上面 3 个)**:

* `eval(byte[][], Object[], int[], String)` → 委托 5-arg

* `eval(String[], int[], String)` / `eval(String[], int[])` / `eval(String[])` → 委托 4-arg → 5-arg

* `evalScriptFile/Stream/GclassFile/Stream` → 委托 5-arg eval 或 evalScriptContent

#### callFunction 双路径

```java
public GSValue callFunction(final GSFunction fn, final ArrayList args) {
    if (Thread.currentThread() == workerThread) {
        return callFunctionDirect(fn, args);
    }
    try {
        CallableEvalTask task = new CallableEvalTask(new TaskCallable() {
            public GSValue call() throws Throwable { return callFunctionDirect(fn, args); }
        });
        submitAndAwait(task);
        return task.awaitResult();
    } catch (DebugAbortException e) { throw e; }      // 调试终止透传
    catch (GSException e) { throw e; }                // gscript 异常透传
    catch (RuntimeException e) { throw e; }
    catch (Throwable e) { throw new RuntimeException(e); }
}
private GSValue callFunctionDirect(GSFunction fn, ArrayList args) { /* 原 line 1255 实现 */ }
```

#### runEventLoop 新语义(awaitIdle)

```java
public void runEventLoop() {
    if (Thread.currentThread() == workerThread) return;  // 死锁保护
    while (!workerStopped) {
        if (!workerBusy
                && taskQueue.isEmpty()
                && (timerScheduler == null || !timerScheduler.hasPending())) {
            return;
        }
        try { Thread.sleep(10); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
    }
}
```

* 10ms 轮询(避免 worker 内部状态锁竞争),精度对 CLI/调试场景足够

* workerBusy 保证:worker 正跑任务时 awaitIdle 不返回(任务可能 setTimeout 使 hasPending=true)

* setInterval 永不退出(与现状一致),需 DAP terminate → DebugAbortException → worker 停止

* 删除原 `finally { timerScheduler.shutdown() }`(scheduler 生命周期改由 shutdown() 管)

#### shutdown 新方法

```java
public void shutdown() {
    workerStopped = true;
    if (timerScheduler != null) timerScheduler.shutdown();
    if (workerThread != null) workerThread.interrupt();
    drainTaskQueue();  // 剩余 EvalTask signalError "worker stopped",避免外部永久阻塞
}
```

#### scheduleTimeout/scheduleInterval/cancelTimer

不变(worker 内调用,无并发)。建议入口加 `if (workerStopped) return -1` 防御。

### 4. 调用点(零改动,行为升级)

所有 `runEventLoop()` 调用点(TestScript 4-5 处 + GSInterpreter.debugAttach + DebugAgent launch + DapServer launch)**0 改动**:

* 语义从「主线程 pump readyQueue」升级为「阻塞等 worker 排空 taskQueue + 无 pending timer」

* DebugAgent/DapServer 的 `gscript-interpreter` 线程:eval 提交给 worker(阻塞 await)+ runEventLoop 阻塞 awaitIdle,terminated 通知时机不变

### 5. `README.md`

更新 `## 定时器机制(EventLoop)` 章节:

* 顶层描述:从"主线程串行执行回调"改为"内部 worker 线程独占执行,外部调用线性化提交"

* 三层设计表:① 核心机制 = `TimerScheduler`(计时守护 + TaskDispatcher)+ `gscript-worker`(worker 线程 + taskQueue);② 入口函数不变;③ 宿主直调 API 增加 `shutdown()`

* 新增"线程模型"小节:worker 单线程独占 stack/callStack;外部线程 eval/callFunction 经 taskQueue 串行化;runEventLoop 改为 awaitIdle 语义

* API 表:`runEventLoop()` 改为"awaitIdle:等待 taskQueue 排空 + 无 pending 定时器",新增 `shutdown()` 行

* 新增 warning:`setVariable`/`getVariable` 未线性化,并发调用有 race 风险(后续 follow-up)

***

## 边界情况处理

| 场景                                                 | 处理                                                                                                  |
| -------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| shutdown 后再调用 eval/callFunction                    | submitAndAwait 检查 workerStopped → 抛 RuntimeException                                                |
| shutdown 后调 runEventLoop                           | workerStopped=true 直接返回                                                                             |
| worker 执行中任务异常                                     | execute() 内 catch 存 error,外部 await 重抛;worker 循环不退出                                                  |
| DebugAbortException 在外部 eval                       | evalDirect 静默吞(同现状),worker 继续;后续定时器任务会再触发 → worker 停止                                               |
| DebugAbortException 在外部 callFunction               | callFunctionDirect 透传 → CallableEvalTask 存 error → 外部 await 重抛                                      |
| DebugAbortException 在定时器回调                         | TimerEvalTask 存 error → worker 检查 → workerStopped=true + scheduler.shutdown + drain                 |
| setInterval 永不退出                                   | awaitIdle 永不返回(同现状);DAP terminate 触发 DebugAbortException 路径                                         |
| 嵌套 setTimeout(脚本内再 setTimeout)                     | worker 跑 eval 时 setTimeout 入 timerQueue,eval 返回后 worker 下轮 poll(delay) 等,到期 dispatch,全 worker 串行无死锁 |
| `this` 转义(TimerScheduler(this) + startWorker 在构造器) | TimerScheduler 构造期不调 dispatch;worker start 后立即 take 阻塞;happens-before 由 Thread.start 保证             |
| await 死锁风险                                         | await 锁是 EvalTask.lock,与 taskQueue 锁正交;worker 不调 await;外部不持 taskQueue 锁                             |
| setVariable/getVariable race                       | 本次不线性化,README 文档化警告;hostTest 串行无 race;DAP evaluate 走 frame.env 不经此路径                                |

***

## 验证方法

### 1. 编译(JDK 1.8,生成 1.4 字节码)

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk1.8.0_202"
mvn clean package -DskipTests
```

确认 class 文件 major version=48,无 1.5+ API 调用(URL.toURI 等陷阱)。

### 2. TestScript 手测

```powershell
java -cp target/classes org.gscript.TestScript timer_test run           # 定时器基础
java -cp target/classes org.gscript.TestScript hosttest hosttest         # eval/evalExpression/callFunction 包装
java -cp target/classes org.gscript.TestScript callfn_test debugagent-callfn  # callFunction + 断点
```

### 3. 全量 Python 测试(对照 baseline\_new/)

```powershell
python tests\test_timer.py
python tests\test_host_interaction.py
python tests\test_gclass.py
python tests\test_dap_e2e.py
python tests\test_while_breakpoint.py
python tests\test_multi_file.py
python tests\test_cross_file_step.py
python tests\test_step_catch.py
python tests\test_path_mismatch.py
python tests\test_timer_debug.py
python tests\test_wait_attach.py
python tests\test_callfn_breakpoint.py
python tests\test_pause_exception.py
python tests\test_debug_mode_basic.py
python tests\test_debug_mode_eval_expression.py
python tests\test_debug_mode_multi_eval.py
python tests\test_debug_mode_unconnected.py
python tests\test_debug_mode_wait_attach.py
python tests\test_attach_smoke.py
```

期望所有测试 0 failed(对照 baseline\_new/ 的 .out 文件)。

### 4. 关键场景抽查

* setInterval + VSCode terminate:应在 1-2 个 tick 内 terminated

* attach + disconnect 后 setInterval 继续:disconnect 后 tick 继续,再 terminate 才停

* callFunction + 断点:debugagent-callfn 模式,addNumbers 内断点命中,continue 返回 30

***

## 风险与回滚

### 风险等级

| 风险                                          | 等级 | 缓解                                                                              |
| ------------------------------------------- | -- | ------------------------------------------------------------------------------- |
| `this` 转义                                   | 中  | TimerScheduler 构造期不调 dispatch;worker start 后阻塞;happens-before 由 Thread.start 保证 |
| EvalTask await 死锁                           | 中  | await 锁与 taskQueue 锁正交;worker 不调 await                                          |
| DebugAbortException 在外部 eval 静默吞后 worker 不停 | 低  | 后续定时器任务再触发 → worker 停止                                                          |
| workerBusy volatile 窗口                      | 低  | volatile 保证可见性;窗口期保守不返回                                                         |
| setVariable race                            | 低  | 本次不线性化,文档化;hostTest 串行;DAP 不经此路径                                                |
| launch 模式 terminated 时机                     | 低  | 本质不变,仅多一层线程间接                                                                   |

### 回滚策略

* 所有改动放一个 git commit,便于 `git revert` 一键回滚

* 回滚验证:`git revert HEAD` + `mvn clean compile` + 全量 Python 测试对照 baseline\_new

### 关键不变量(改造前后对照)

| 不变量                           | 旧                                  | 新                                           |
| ----------------------------- | ---------------------------------- | ------------------------------------------- |
| 字节码执行单线程串行                    | 主线程/解释器线程                          | worker 线程                                   |
| callStack 在 suspendCheck 期间不变 | ✓                                  | ✓                                           |
| DebugAbortException 终止事件循环    | runEventLoop 透传 + finally shutdown | TimerEvalTask 触发 worker 停止 + drain          |
| 纯 setTimeout 脚本正常退出           | hasPending=false                   | taskQueue 空 + hasPending=false              |
| 纯 setInterval 永不退出            | ✓                                  | ✓                                           |
| 调试器断点/单步在定时器回调工作              | ✓                                  | ✓(callFunctionDirect → eval → suspendCheck) |
| runEventLoop 调用点 0 改动         | -                                  | ✓                                           |

***

## 关键文件

* `src/main/java/org/gscript/vm/GSInterpreter.java` — 主改造(worker 线程、EvalTask 体系、eval/callFunction 双路径、runEventLoop→awaitIdle、shutdown、implements TaskDispatcher)

* `src/main/java/org/gscript/vm/TimerScheduler.java` — 解耦(移除 readyQueue、新增 TaskDispatcher、dispatch 替代 offer)

* `src/main/java/org/gscript/util/SimpleBlockingQueue.java` — 新增 take() + clear()

* `README.md` — 更新定时器机制章节

* `src/main/java/org/gscript/vm/debug/DebugAgent.java` / `DapServer.java` — 调用点 0 改动,需回归测试验证 launch 模式线程协作

