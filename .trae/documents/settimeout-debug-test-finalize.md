# setTimeout/setInterval 调试器测试收尾 + 文档更新

## Context

setTimeout/setInterval 功能（方案 B：单线程 + 守护定时器线程）已实现完成：
- `TimerScheduler.java` / `TimerLib.java` 新建
- `GSInterpreter.java` 新增 `callFunction` / `runEventLoop` / `installTimerGlobals` 等
- `TestScript.java` / `DebugAgent.java` / `DapServer.java` 在各入口插入 `installTimerGlobals` + `runEventLoop`
- `timer_test.script` + `test_timer.py`（15/15 通过）
- 全套 154 回归测试通过

**剩余收尾工作**（本计划范围）：调试器端到端测试 `test_timer_debug.py` 当前因 Gson classpath 问题失败；文档（README + 项目记忆）尚未同步定时器章节。

## Current State Analysis（Phase 1 探索结论）

### 问题 1：Gson classpath（test_timer_debug.py 启动 Java 失败）
- `test_timer_debug.py` 的 `start_java()` 用 `java -cp target/classes org.gscript.TestScript <name> <mode>`
- `debugagent` / `debugagent-attach` 模式经过 `DebugAgent` → `DapServer` → Gson（DAP JSON 处理）
- `target/classes` **不含** Gson 依赖（Gson 仅在 `mvn package` 打 fat jar 时包含）
- 报错：`NoClassDefFoundError: com/google/gson/JsonElement`
- **根因**：classpath 缺 Gson。`ensure_gclass()`（compile 模式）不需要 Gson，可保持 `target/classes`；只有 `start_java()`（debugagent 模式）需要补 Gson

**Gson jar 已确认存在**：`C:\Users\95739\.m2\repository\com\google\code\gson\gson\2.10.1\gson-2.10.1.jar`（pom.xml 声明 2.10.1）

### 问题 2：测试 2（attachReady）的 disconnect 语义预期错误
当前 `test_debugagent_attach()` 注释与断言假设：
> "disconnect（attach 模式：分离触发 DebugAbortException 终止 event loop）" + `proc.returncode == 0`

**实际行为**（`DapServer.handleDisconnect` 第 808-830 行）：
- attach 模式（`agent != null`）：`controller.continueRun()` 唤醒挂起的解释器 + `setDebugController(null)` 清除控制器 → **分离不终止**
- 解释器继续运行 setInterval（永不退出）→ **进程不退出，测试会超时**

这是**正确的 JS 语义**（如同 `node --inspect` 分离后程序继续运行）。VSCode 对 attach 配置点"停止"发送的是 `disconnect`（`terminateDebuggee=false`），detach-without-kill 是预期行为。

**结论**：测试 2 应改为验证"detach 后程序继续运行"，再由测试 kill 进程。**无需改 Java 行为**。

### 路径映射验证（断点能否命中）
- `compileGclass` 设置 `sourcePath = name + ".script"`（如 `timer_test.script`，相对路径）
- `mapToRemotePath`：localRoot=`resources`、remoteRoot="" → 本地绝对路径剥离前缀得 `timer_test.script`
- 两者匹配 ✅（与现有 `test_attach_smoke.py` 用 `debug_attach_test.script` 同模式，已验证可行）
- 断点目标：timer_test.script:13（zero 回调内 `console.log("OUT:zero")`）、timer_debug.script:5（setInterval 回调内 `counter = counter + 1`）

### 测试 1（waitForDebugger）流程确认
configurationDone → onConfigurationDone 启动解释器线程 → eval 注册 setTimeout → runEventLoop → zero 回调命中 line 13 → stopped(breakpoint) → continue → 其余回调无 line 13 断点 → 全部排空 → runEventLoop 返回 → notifyInterpreterTerminated → terminated → 进程退出 0 ✅

### 测试 2（attachReady）流程确认（修正后）
attach(setController→pause) → setBreakpoints → configurationDone → 收到 stopped(pause) → continue → 下个 setInterval tick 命中 line 5 → stopped(breakpoint) → stackTrace/scopes/variables(含 counter) → disconnect → 程序继续运行(setInterval) → 测试验证 `proc.poll() is None` → kill 进程 ✅

### runEventLoop 文档注释不准确（次要）
`GSInterpreter.runEventLoop` 注释称"纯 setInterval 脚本永不退出，需 disconnect 触发 DebugAbortException 终止"。但 disconnect(attach) 实际分离不终止。**仅注释不准，改注释不改行为**。

## Proposed Changes

### 1. 修改 `tests/test_timer_debug.py`（核心修复）

**a) 新增 Gson jar 路径常量 + classpath 构造：**
```python
GSON_JAR = os.path.join(
    os.path.expanduser("~"),
    ".m2", "repository", "com", "google", "code", "gson",
    "gson", "2.10.1", "gson-2.10.1.jar",
)
# debugagent 模式需要 Gson（DapServer JSON 处理），target/classes 不含依赖
DEBUG_CP = CLASSES_DIR + os.pathsep + GSON_JAR
```

**b) `start_java()` 改用含 Gson 的 classpath：**
```python
def start_java(name, mode):
    cmd = [JAVA, "-cp", DEBUG_CP, "org.gscript.TestScript", name, mode]
    return subprocess.Popen(cmd, stdout=PIPE, stderr=PIPE, text=True, ...)
```
- `ensure_gclass()`（compile 模式，不需 Gson）保持 `CLASSES_DIR` 不变

**c) `test_debugagent_attach()` 改 disconnect 断言为 detach 语义：**

替换末尾的 disconnect 块：
```python
# disconnect（attach 模式：分离调试器，程序继续运行 setInterval）
resp = client.send_request("disconnect", {})
check("disconnect 成功", resp.get("success", False))
client.close()
# 验证：分离后解释器继续运行（setInterval 仍在跑，进程未退出）
time.sleep(0.3)
check("detach 后程序继续运行（未退出）", proc.poll() is None)
```

替换 finally 块（不再期望 returncode==0，改为 kill 清理）：
```python
finally:
    if proc.poll() is None:
        proc.kill()
        try: proc.wait(timeout=5)
        except Exception: pass
    check("Java 进程已清理", True)
    if proc.stderr:
        err = proc.stderr.read()
        if err.strip():
            print(f"  [JAVA STDERR] {err.strip()[:500]}")
```

**d) `test_debugagent_wait()` 的 finally 块**：保持期望 returncode==0（模式 1 正常退出），但确保异常时打印 stderr（已存在，微调）。

### 2. 修改 `src/main/java/org/gscript/vm/GSInterpreter.java`（仅注释）

`runEventLoop()` 方法注释（第 862-874 行）修正为准确描述：
- 旧："纯 setInterval 脚本永不退出，需 disconnect 触发 DebugAbortException 终止"
- 新："纯 setInterval 脚本永不退出。attach 模式 disconnect 仅分离调试器（程序继续运行），需宿主 kill 进程或调用 terminate 请求终止（terminate 触发 DebugAbortException 传播出循环）"

**不改 `catch (DebugAbortException e) { throw e; }` 行为**（保持传播，供 launch 模式 disconnect / terminate 请求使用）。这是注释准确性修复，非行为变更。

### 3. 运行测试

- `python tests/test_timer_debug.py` — 验证测试 1（waitForDebugger + setTimeout 断点）+ 测试 2（attachReady + setInterval 运行时附加）全部 PASS
- 全套回归：依次跑 `test_gclass.py` / `test_host_interaction.py` / `test_dap_e2e.py` / `test_multi_file_debug.py` / `test_while.py` / `test_step_catch.py` / `test_cross_file_debug.py` / `test_path_mismatch.py` / `test_timer.py` — 确认无回归（预期 154 + 新增 timer_debug 测试）

### 4. 更新 `README.md`

在 `## 宿主交互 API` 章节后新增 `## 定时器 API（setTimeout / setInterval）` 章节，包含：
- 概述：JS 风格定时器，单线程语义（守护线程计时，回调在主线程串行执行）
- 4 个全局函数签名：`setTimeout(callback, delayMs, ...args)`、`setInterval(callback, periodMs, ...args)`、`clearTimeout(id)`、`clearInterval(id)`
- 返回值：timer id（整数）
- 回调签名：`function(args...)`（args 为透传的额外参数）
- 事件循环说明：主脚本 eval 返回后自动进入事件循环，pump 定时器队列直到排空（setInterval 需 clearInterval 或进程终止）
- 简短示例代码

### 5. 更新项目记忆 `project_memory.md`

新增"定时器与事件循环"章节，记录：
- 方案 B 架构（守护线程计时 + readyQueue + 主线程 runEventLoop 串行回调）
- `callFunction` 设计（包装 eval，stack 前后对比决定是否 pop）
- `installTimerGlobals` 在各入口的插入位置
- 调试器影响：THREAD_ID 不变、callStack 不变、terminated 时机延后到 runEventLoop 返回
- attach 模式 disconnect 语义（分离不终止，setInterval 程序继续运行）
- 测试：test_timer.py（15）+ test_timer_debug.py（waitForDebugger + attachReady）

## Assumptions & Decisions

1. **不改 Java 行为**：disconnect(attach) 保持"分离不终止"语义（正确的 JS 语义，VSCode attach 停止 = detach）。测试改为验证 detach 后程序继续运行再 kill。
2. **Gson classpath 用 Maven 本地仓库 jar**（非 fat jar）：只需 `mvn compile`（快），target/classes 类始终最新；Gson 版本硬编码 2.10.1（与 pom.xml 一致，升级时同步改）。
3. **`ensure_gclass` 不需 Gson**：compile 模式（Lexer/Parser/ByteCodeGenerator/GSClassWriter）不依赖 Gson，保持 `CLASSES_DIR`。
4. **runEventLoop 的 DebugAbortException 保持 re-throw**：供 launch 模式 disconnect / terminate 请求终止事件循环。attachReady 模式测试用 disconnect（detach）不触发它。仅修注释。
5. **不实现 `terminateDebuggee` flag**：handleDisconnect 简化为 attach=detach / launch=terminate，足够当前用例（未来可扩展）。

## Verification

1. `python tests/test_timer_debug.py` → 两个测试全 PASS（测试 1 验证 setTimeout 回调断点 + terminated；测试 2 验证 setInterval 运行时附加 + 断点 + 变量 + detach 后存活）
2. 全套回归测试通过（无回归）
3. README 新章节渲染正确
4. project_memory.md 已同步
