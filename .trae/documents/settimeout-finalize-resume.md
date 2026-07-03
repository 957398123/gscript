# setTimeout/setInterval 收尾续作（验证竞态修复 + 文档同步）

## Context

上一轮会话已完成方案 B（守护线程计时 + 主线程串行回调）的全部实现：`TimerScheduler` / `TimerLib` 新建、`GSInterpreter` 新增 `callFunction` / `runEventLoop` / `installTimerGlobals`、各入口（`TestScript` / `DebugAgent` / `DapServer`）插入定时器安装与事件循环、`timer_test.script` + `test_timer.py` 15/15 通过、全套 154 回归通过。

随后执行收尾计划（`settimeout-debug-test-finalize.md`）时，发现并修复了一个 **TimerScheduler 竞态 bug**（setInterval event loop 提前退出）。本计划是该收尾的续作：**仅剩"验证竞态修复 + 跑回归 + 同步文档"三步**。

## Current State Analysis（Phase 1 探索结论，已逐项核实）

### 已完成（代码层面无需再动）

| 项                      | 文件                                                             | 核实                                                                                          |
| ---------------------- | -------------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| TimerScheduler 竞态修复    | `src/main/java/org/gscript/vm/TimerScheduler.java` 第 106-113 行 | setInterval 重入队已移入 poll 的同一 `synchronized(lock)` 块内，注释说明窗口期原因 ✅                             |
| Gson classpath 修复      | `tests/test_timer_debug.py` 第 33-38 行                          | `GSON_JAR` + `DEBUG_CP` 常量，`start_java()` 用 `DEBUG_CP`；`ensure_gclass()` 保持 `CLASSES_DIR` ✅ |
| disconnect detach 语义修正 | `tests/test_timer_debug.py` 第 284-305 行                        | 测试 2 改为验证 `proc.poll() is None`（detach 后存活），finally 改 kill 清理 ✅                             |
| runEventLoop 注释修正      | `src/main/java/org/gscript/vm/GSInterpreter.java` 第 862-877 行  | 注释改为"attach 模式 disconnect 仅分离调试器，需 terminate 请求终止" ✅                                        |

### 未完成（本计划范围）

1. **竞态修复尚未验证**：`TimerScheduler.java` 已改但未重编译 + 重跑 `test_timer_debug.py`。上一轮测试 1（waitForDebugger + setTimeout）全 10 项 PASS，测试 2（attachReady + setInterval）因竞态在 \~200ms 后退出（仅 tick 2 次）。修复后预期测试 2 通过。
2. **全套回归未跑**（修复 TimerScheduler 后需确认无回归）。
3. **README.md 无定时器章节**（grep `定时器|setTimeout|setInterval|TimerScheduler|runEventLoop|事件循环` → 无匹配）。现有章节：`语法定义` / `字节码` / `编译文件格式` / `运行` / `宿主交互 API`（行 768 起）。
4. **project\_memory.md 无定时器章节**（grep 同上 → 无匹配）。

## Proposed Changes

### 1. 重新编译 + 重跑 test\_timer\_debug.py（验证竞态修复）

```bash
mvn -q compile
python tests/test_timer_debug.py
```

预期：测试 1（waitForDebugger + setTimeout 回调断点 + terminated）全 10 项 PASS；测试 2（attachReady + setInterval 运行时附加 + 断点 + counter 变量 + detach 后存活）全 \~10 项 PASS。

**若测试 2 仍失败**：检查 `[JAVA STDERR]` 输出与 Java stdout（`tick N` 次数），定位是否仍有 event loop 提前退出。可能需进一步检查 `hasPending()` 在 readyQueue.offer 之前的可见性（当前修复已将 re-schedule 移入锁内，理论上无窗口期）。

### 2. 全套回归测试

依次跑（确认 TimerScheduler 改动无回归）：

```
test_gclass.py
test_host_interaction.py
test_dap_e2e.py
test_multi_file_debug.py
test_while_breakpoint.py
test_step_catch.py
test_cross_file_step.py
test_path_mismatch.py
test_timer.py
```

预期总数 154 + test\_timer\_debug 新增项，全 PASS。重点关注 `test_timer.py`（15 项，纯定时器语义）与 `test_dap_e2e.py`（断点/单步不受事件循环改动影响）。

### 3. 更新 README.md（新增定时器章节）

在 `## 宿主交互 API` 章节末尾（约行 820，使用示例之后）新增 `## 定时器 API（setTimeout / setInterval）` 章节：

* **概述**：JS 风格定时器，单线程语义。守护线程 `gscript-timer` 仅计时（不跑字节码），到期任务入 readyQueue，主线程 `runEventLoop` 串行执行回调。回调里设的断点/单步天然工作。

* **4 个全局函数**（表格）：

  * `setTimeout(callback, delayMs, ...args)` → 返回 timer id（整数）

  * `setInterval(callback, periodMs, ...args)` → 返回 timer id

  * `clearTimeout(id)` → 返回 null

  * `clearInterval(id)` → 返回 null

* **回调签名**：`function(args...)`，`args` 为透传的额外参数（`args[0]` 为 this=null，回调内从第 1 个参数起取额外参数）

* **事件循环**：主脚本 eval 返回后自动进入事件循环，pump 定时器队列直到排空（`hasPending()==false`）。纯 setTimeout 脚本排空后退出；纯 setInterval 脚本永不退出，需 `clearInterval` 或进程终止。

* **示例代码**（gscript）：

  ```javascript
  var id = setInterval(function() {
      counter = counter + 1;
      console.log("tick", counter);
      if (counter >= 3) { clearInterval(id); }
  }, 100);
  ```

### 4. 更新 project\_memory.md（新增定时器与事件循环章节）

在文件末尾新增 `## 定时器与事件循环（已完成）` 章节，记录：

* **方案 B 架构**：守护线程 `gscript-timer` 计时（peek/wait/poll timerQueue，到期移到 readyQueue），主线程 `runEventLoop` 串行 `callFunction` 执行回调。守护线程不接触 GSInterpreter 任何字段，与调试器（THREAD\_ID=1）兼容。

* **关键 API**：`GSInterpreter.callFunction(GSFunction, args)` 包装 `eval(GSFrame, args)`，复用 `callStack.push/pop` + `suspendCheck`，使 native 代码能回调 gscript 函数且断点/单步天然工作。`installTimerGlobals()` 在各入口（launch/attach/hosttest）插入注册 4 个全局函数。

* **竞态 bug 教训**：setInterval 重入队（re-schedule）必须在 poll 的同一 `synchronized` 块内完成（释放锁前），否则存在窗口期（task 已移出 timerQueue、尚未塞回、readyQueue 也未 offer），主线程 `hasPending()` 在此窗口看到双空 → event loop 提前退出。简单回调（counter++ + console.log）易触发，复杂回调（多语句）因 re-schedule 总能在回调完成前完成而掩盖 bug。

* **调试器影响**：THREAD\_ID 不变、callStack 不变；terminated 时机延后到 `runEventLoop` 返回后（`notifyInterpreterTerminated`）。attach 模式 disconnect 仅分离调试器（`controller.continueRun()` + `setDebugController(null)`），setInterval 程序继续运行（如同 node --inspect），需 terminate 请求或 kill 进程终止。

* **测试**：`test_timer.py`（15 项纯语义）+ `test_timer_debug.py`（waitForDebugger + attachReady 两模式端到端，验证回调断点命中、stackTrace、counter 变量、detach 后存活）。

## Assumptions & Decisions

1. **不改 Java 行为**：竞态修复属 bug 修复（非行为变更），使 setInterval 正确持续运行。disconnect(attach) 保持"分离不终止"语义。
2. **延续已批准的 finalize 计划**：README/project\_memory 内容范围与 `settimeout-debug-test-finalize.md` 第 4-5 节一致，本计划仅执行未完成部分。
3. **若竞态修复验证失败**：不臆造修复，回到分析阶段（检查 readyQueue/timerQueue 可见性、hasPending 锁粒度），必要时向用户反馈。
4. **测试套件命名**：以实际 `tests/` 目录文件名为准（如 `test_while_breakpoint.py` / `test_cross_file_step.py`，非摘要中的简写）。

## Verification

1. `python tests/test_timer_debug.py` → 测试 1 + 测试 2 全 PASS（关键：测试 2 验证 setInterval 持续运行 + detach 后存活）
2. 全套回归测试通过（无回归，预期 ≥154 PASS + timer\_debug 新增项）
3. `grep "定时器" README.md` → 命中新章节
4. `grep "定时器与事件循环" project_memory.md` → 命中新章节

