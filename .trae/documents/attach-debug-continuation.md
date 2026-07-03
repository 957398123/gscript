# 方案 B 续作：完成 attach 调试模式剩余实现 + 验证 + 测试案例

## 摘要

本计划承接已批准的 `attach-debug-source-content.md`。经代码核查，**步骤 1–5 已全部完成**（gclass SourceContent 属性、GSFunction/GSInterpreter 源码内容传递 + volatile、DebugAgent 类、DapServer attach 分流、VSCode 扩展配置）。本计划仅处理**剩余的步骤 6 收尾 + 步骤 7 验证 + 项目记忆更新 + VSCode 测试案例交付**。

## 当前状态分析（已核查确认）

### 已完成（步骤 1–5，无需再动）

| 模块 | 核查证据 |
| --- | --- |
| gclass SourceContent 属性 | [GSClassConstants.java:266](file:///e:/JProjects/gscript/src/main/java/org/gscript/compile/gclass/GSClassConstants.java#L266) `ATTR_SOURCE_CONTENT`；[GSClassData.java:45](file:///e:/JProjects/gscript/src/main/java/org/gscript/compile/gclass/GSClassData.java#L45) `sourceContent` 字段 + 6 参构造器；[GSClassWriter.java:55](file:///e:/JProjects/gscript/src/main/java/org/gscript/compile/gclass/GSClassWriter.java#L55) 5 参 `write()` + 属性写入；[GSClassReader.java:189-190](file:///e:/JProjects/gscript/src/main/java/org/gscript/compile/gclass/GSClassReader.java#L189) 解析分支 |
| GSFunction/GSInterpreter | [GSFunction.java:68](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/value/GSFunction.java#L68) `sourceContent` 字段；[GSInterpreter.java:47](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java#L47) `volatile`；[GSInterpreter.java:112-115](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java#L112) 本地变量捕获；[GSInterpreter.java:406](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java#L406) fundef 继承；[GSInterpreter.java:636-666](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java#L636) 4 参→5 参 eval 委托 + 5 参重载 |
| DebugAgent 类 | [DebugAgent.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/debug/DebugAgent.java) 完整实现（两种模式、onConfigurationDone、stop、getSourceContents） |
| DapServer attach 分流 | [DapServer.java:452](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/debug/dap/DapServer.java#L452) handleLaunchAttach attach 分支；:512-513 路径映射；:574 configurationDone 分流；:637/663-665 stackTrace sourceReference；:710 scopes agent 兜底；:799 disconnect 分流；:856 handleSource；:876 notifyInterpreterTerminated；:938 mapToRemotePath |
| VSCode 扩展配置 | [package.json:62-119](file:///e:/JProjects/gscript/vscode-extension/package.json#L62) attach 增加 localRoot/remoteRoot/stopOnEntry + 两个 initialConfigurations |

### 步骤 6 部分完成（TestScript.java）

已完成：
- [TestScript.java:55-60](file:///e:/JProjects/gscript/src/main/java/org/gscript/TestScript.java#L55) `debugagent`/`debugagent-attach` 模式分支
- [TestScript.java:147](file:///e:/JProjects/gscript/src/main/java/org/gscript/TestScript.java#L147) `compileGclass` 已传 `content` 给 writer
- [TestScript.java:63](file:///e:/JProjects/gscript/src/main/java/org/gscript/TestScript.java#L63) usage 提示已更新

**未完成**：
- [TestScript.java:165](file:///e:/JProjects/gscript/src/main/java/org/gscript/TestScript.java#L165) `runGclass` 仍用 4 参 eval（缺 `data.sourceContent`）
- `debugAgent(String name, boolean attachReady)` 方法**未添加**（文件止于 hostTest）
- `src/main/resources/debug_attach_test.script` **未创建**
- `.vscode/launch.json` 仅含 launch 配置，**缺 attach 配置**（用户测试需要）

### 步骤 7 未执行

编译 + 回归测试（139 测试）未跑。

## 剩余实现步骤

### 步骤 6.1：TestScript.runGclass 改 5 参 eval

**文件**：[TestScript.java:165](file:///e:/JProjects/gscript/src/main/java/org/gscript/TestScript.java#L165)

**改动**：单行——把 `data.sourcePath` 后补 `data.sourceContent`：

```java
interpreter.eval(data.src, data.constantPool, data.sourceLines, data.sourcePath, data.sourceContent);
```

**理由**：`runGclass` 模式加载 gclass 后执行，应把 sourceContent 一并传入，使运行时函数链携带源码（即便非调试也无害——sourceContent 仅在 attach 调试 source 请求时读取）。

### 步骤 6.2：TestScript 新增 debugAgent 方法

**文件**：[TestScript.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/TestScript.java)（在 hostTest 方法后追加）

**实现**（与原计划一致，按已实现的 DebugAgent API 对齐）：

```java
// =========================================================================
//  DebugAgent 调试模式（debugagent / debugagent-attach）
// =========================================================================

/**
 * DebugAgent 演示：加载 gclass + 启动调试代理。
 *
 * <p>验证 attach 调试两种启用方式：
 * <ul>
 *   <li>attachReady=false：waitForDebugger 模式，阻塞等待 VSCode 连接后执行（"debug 模式启用"）</li>
 *   <li>attachReady=true：attachReady 模式，解释器先运行，VSCode 随后附加（"运行时 attach"）</li>
 * </ul>
 *
 * @param name 脚本名（不含扩展名，需先 compile 生成 gclass）
 * @param attachReady true=运行时附加模式，false=等待调试器模式
 */
public static void debugAgent(String name, boolean attachReady) throws Exception {
    GSClassData data = loadGclass(name);
    if (data == null) return;
    if (data.sourceContent == null) {
        System.err.println("警告: gclass 不含源码内容，attach 模式无法在 VSCode 显示源码");
        System.err.println("请重新编译: TestScript " + name + " compile");
    }
    int port = 4711;
    DebugAgent agent = new DebugAgent(port);
    if (attachReady) {
        // 模式 2：先启动解释器运行，再监听附加
        GSInterpreter interpreter = new GSInterpreter();
        interpreter.addVariableToGlobal("console", new Console());
        agent.setInterpreter(interpreter);
        agent.addGclass(data);
        agent.startAttachListener();  // 后台监听，立即返回
        System.err.println("[测试] 解释器开始运行，VSCode 可随时附加（端口 " + port + "）");
        // 主线程运行解释器（VSCode 连接后因 controller 设置而挂起）
        interpreter.eval(data.src, data.constantPool, data.sourceLines,
                data.sourcePath, data.sourceContent);
        System.err.println("[测试] 解释器运行结束");
        agent.stop();
    } else {
        // 模式 1：等待 VSCode 连接后运行
        agent.addGclass(data);
        agent.waitForDebuggerAndRun();  // 阻塞直到调试会话结束
        System.err.println("[测试] 调试会话结束");
    }
}
```

**导入**：需在 TestScript.java 顶部 import 区补 `import org.gscript.vm.debug.DebugAgent;`（DebugAgent 在 `org.gscript.vm.debug` 包）。

### 步骤 6.3：创建测试脚本

**新文件**：`e:\JProjects\gscript\src\main\resources\debug_attach_test.script`

内容采用原计划的 factorial + processItems 测试脚本（含递归、循环、数组、函数调用，覆盖断点/单步/调用栈/变量查看场景）：

```gscript
// gscript attach 调试测试脚本
// 用法：
//   1. mvn package
//   2. java -cp target/gscript-1.0-SNAPSHOT.jar org.gscript.TestScript debug_attach_test compile
//   3. java -cp target/gscript-1.0-SNAPSHOT.jar org.gscript.TestScript debug_attach_test debugagent
//      或 debugagent-attach
//   4. VSCode attach（端口 4711）

var counter = 0;

function calculateFactorial(n) {
    if (n <= 1) {
        return 1;
    }
    return n * calculateFactorial(n - 1);
}

function processItems(items) {
    var total = 0;
    var i = 0;
    while (i < items.length) {
        total = total + items[i];
        i = i + 1;
    }
    return total;
}

console.log("=== attach 调试测试开始 ===");

var fact5 = calculateFactorial(5);
console.log("5! = " + fact5);

var numbers = [10, 20, 30, 40, 50];
var sum = processItems(numbers);
console.log("sum = " + sum);

counter = fact5 + sum;
console.log("counter = " + counter);

console.log("=== attach 调试测试结束 ===");
```

### 步骤 6.4：补 .vscode/launch.json attach 配置

**文件**：`.vscode/launch.json`（现有仅 2 个 launch 配置，追加 2 个 attach 配置）

在 configurations 数组末尾追加：

```json
{
    "type": "gscript",
    "request": "attach",
    "name": "Attach to gscript (debug mode)",
    "host": "localhost",
    "port": 4711,
    "localRoot": "${workspaceFolder}/src/main/resources",
    "remoteRoot": "",
    "stopOnEntry": false
},
{
    "type": "gscript",
    "request": "attach",
    "name": "Attach to gscript (runtime)",
    "host": "localhost",
    "port": 4711,
    "localRoot": "${workspaceFolder}/src/main/resources",
    "remoteRoot": ""
}
```

**说明**：localRoot 指向 resources 目录（VSCode 端打开 .script 的位置），remoteRoot 空串（gclass 的 sourcePath 为相对名 `debug_attach_test.script`）。mapToRemotePath 会把本地绝对路径剥到 `debug_attach_test.script`，与 gclass sourcePath 匹配。

### 步骤 7：编译 + 回归验证

1. `mvn clean package -DskipTests`（Java 9 + Maven 3.9.16，需 `JAVA_HOME=C:\Program Files\Java\jdk-9.0.4`）
2. 编译测试 gclass：`java -cp target/gscript-1.0-SNAPSHOT.jar org.gscript.TestScript debug_attach_test compile`
   - 验证输出含 `source content: xxx chars`
3. dump 验证 sourceContent 往返：`java -cp ... org.gscript.TestScript debug_attach_test dumpgclass`
   - 可选：人工确认 gclass 加载无异常
4. 运行全部 8 个 Python 回归测试（确保 139 测试全通过，无回归）：
   - `tests/test_gclass.py`（65）
   - `tests/test_host_interaction.py`（12）
   - `tests/test_dap_e2e.py`（17）
   - `tests/test_multi_file.py`（19）
   - `tests/test_while_breakpoint.py`（9）
   - `tests/test_step_catch.py`（7）
   - `tests/test_cross_file_step.py`（7）
   - `tests/test_path_mismatch.py`（3）
5. **关键回归点**：GSClassWriter.write 签名变化（4 参重载委托 5 参）、GSClassData 6 参构造器、eval 5 参重载——test_gclass.py 直接覆盖 gclass 往返，是核心回归保障。

### 步骤 8：更新项目记忆文件

**文件**：`c:\Users\95739\.trae-cn\memory\projects\-e-JProjects-gscript\project_memory.md`

在末尾追加新章节「attach 调试模式（方案 B）」，记录：
- gclass SourceContent 属性机制（属性段 u4 长度 + UTF-8，避开 CP writeShort 65535 限制）
- DebugAgent 两种模式（waitForDebuggerAndRun 阻塞 / startAttachListener 后台）
- DapServer attach 分流（agent 非 null 时走独立路径：不编译本地脚本、source 请求返回源码、disconnect 分离不终止）
- 路径映射（localRoot/remoteRoot 前缀替换，剥本地绝对路径到 gclass sourcePath 相对名）
- volatile debugController + 本地变量捕获（避免 attach disconnect 跨线程竞态）
- sourceReference 机制（stackTrace 设 reference>0，VSCode 发 source 请求，DapServer 从 agent.getSourceContents() 返回）
- TestScript debugagent/debugagent-attach 模式
- 测试脚本 debug_attach_test.script

**文件**：`c:\Users\95739\.trae-cn\memory\projects\-e-JProjects-gscript\20260703\topics.md`

追加本次 session 的 topic 摘要。

### 步骤 9：交付 VSCode attach 测试案例

在最终回复中给出：
1. 完整的两种模式操作步骤（waitForDebugger / attachReady）
2. 断点设置建议（calculateFactorial 递归行、processItems 循环体、counter 赋值行）
3. 验证清单（断点命中、变量查看 fact5/sum/counter、单步、调用栈、源码显示来自 gclass）
4. 常见问题排查（dap_debug.log 位置、端口占用、sourceContent 缺失警告）

## 假设与决策

1. **runGclass 也改 5 参 eval**：即便 runGclass 非调试场景，传 sourceContent 无害（仅 attach 调试 source 请求时读取），保持与 debugAgent 调用一致，避免分支差异。
2. **debugAgent 方法签名沿用原计划**：`(String name, boolean attachReady)`，与已添加的 main 分支调用 `debugAgent(name, false/true)` 对齐。
3. **.vscode/launch.json 追加 attach 配置**：用户要求"给我一个测试的案例，我要使用 vscode 附加测试效果"，直接在 launch.json 配好可立即 F5 测试，无需用户手写。
4. **测试脚本用 factorial + processItems**：覆盖递归（调用栈深）、循环（断点反复命中）、数组（变量展开）、函数调用（step in/out），是验证调试器全功能的经典用例。
5. **回归测试不新增 Python 用例**：attach 调试是手动 VSCode 验证场景（涉及 socket + VSCode UI），Python e2e 难以模拟。现有 139 测试保障无回归即可。attach 专项验证靠用户 VSCode 实测。

## 验证步骤

### 编译验证
```powershell
cd e:\JProjects\gscript
$env:JAVA_HOME="C:\Program Files\Java\jdk-9.0.4"
mvn clean package -DskipTests
java -cp target/gscript-1.0-SNAPSHOT.jar org.gscript.TestScript debug_attach_test compile
```
预期：输出 `gclass written: ...debug_attach_test.gclass` + `source content: xxx chars`。

### 回归验证
```powershell
python tests/test_gclass.py
python tests/test_host_interaction.py
python tests/test_dap_e2e.py
python tests/test_multi_file.py
python tests/test_while_breakpoint.py
python tests/test_step_catch.py
python tests/test_cross_file_step.py
python tests/test_path_mismatch.py
```
预期：139 测试全通过。

### VSCode attach 手动验证（交付给用户）
见步骤 9 的测试案例。

## 文件改动清单（剩余）

| 文件 | 改动类型 | 说明 |
| --- | --- | --- |
| `src/main/java/org/gscript/TestScript.java` | 编辑 | runGclass 改 5 参 eval；新增 debugAgent 方法；补 import |
| `src/main/resources/debug_attach_test.script` | **新文件** | attach 测试脚本 |
| `.vscode/launch.json` | 编辑 | 追加 2 个 attach 配置 |
| `c:\Users\...\.trae-cn\memory\projects\-e-JProjects-gscript\project_memory.md` | 编辑 | 追加 attach 调试章节 |
| `c:\Users\...\.trae-cn\memory\projects\-e-JProjects-gscript\20260703\topics.md` | 编辑 | 追加 session topic |

（步骤 1–5 的 8 个文件已完成，不再改动。）
