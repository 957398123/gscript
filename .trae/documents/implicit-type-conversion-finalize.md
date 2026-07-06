# 隐式类型转换修复：测试验证与文档更新

## Context

gscript 引擎隐式类型转换对齐 JS 语义的**代码实现已全部完成**（GSValue.java 8 类运算符重写 + TypeLib.java NUMBER 委托 + implicit\_conversion\_test.script 创建）。本计划聚焦剩余的**测试验证**与**文档更新**收尾工作。

代码改动状态（已通过 Read 验证，不再修改）：

* [GSValue.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/value/GSValue.java)：`toNumber`/`toInt32` 静态方法 + `incr`/`decr`/`eq`/`gt`/`ge`/`lt`/`le`/`neg`/`plus`/`minus`/`mul`/`div`/`modulo`/`ls`/`rs`/`b_and`/`b_or`/`b_xor`/`b_not` 全部按 JS 语义重写

* [TypeLib.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/stdlib/TypeLib.java)：`NUMBER` 内部改为 `return GSValue.toNumber(v)`

* [implicit\_conversion\_test.script](file:///e:/JProjects/gscript/src/main/resources/implicit_conversion_test.script)：11 个测试段，`"5" | 2 = 7` 用例已修正

## Current State Analysis

剩余工作分三块：

1. **测试验证**：上次运行 `implicit_conversion_test` 时 `"23" | 4` 用例预期写错（23|4 实际就是 23，因为 bit 2 已置位），已改为 `"5" | 2 = 7` 但**尚未重新运行确认**。
2. **回归验证**：需确认 `type_conversion_test`、`edgecase_test`、Python `run_baseline.py` 不回归。其中 `edgecase_test` 的 `空串==0` 预期从 false 变 true（JS 正确行为，需确认）；`复杂try嵌套` 出现 "Uncaught Error: e2"，需确认是**预先存在的 bug**（与类型转换无关）而非本次回归。
3. **文档更新**：README.md 需新增"隐式类型转换（JS 语义对齐）"章节，位置在 [README.md:570](file:///e:/JProjects/gscript/README.md#L570)（"全局类型转换函数"章节末尾的 `>` 提示之后）、`## 字节码`（line 572）之前。

## Proposed Changes

### 步骤 1：构建项目

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk1.8.0_202"
mvn clean package -DskipTests
```

用 JDK 1.8 编译（项目目标字节码 1.4），确保无编译错误。

### 步骤 2：运行 implicit\_conversion\_test（核心验证）

```powershell
java -cp target/classes org.gscript.TestScript implicit_conversion_test run
```

**期望**：所有 `[OK]`，无 `[FAIL]`。重点核对：

* `"23" > 0` = true（用户报告的核心 bug）

* `"5" | 2` = 7（上次修正的用例）

* `var s="23"; s++; s` = 24（incr 转换）

* `"12" + 0 == 12` = false（拼接优先于相等比较）

如有 FAIL，分析是测试预期错误还是实现 bug，**优先修测试预期**（实现已对齐 JS）。

### 步骤 3：运行 type\_conversion\_test（全局函数回归）

```powershell
java -cp target/classes org.gscript.TestScript type_conversion_test run
```

**期望**：全部 OK。TypeLib.NUMBER 改为委托 toNumber 后，`Number("123")`/`Number("")`/`Number("123abc")` 等用例行为应不变（toNumber 逻辑与原 NUMBER 实现等价）。

### 步骤 4：运行 edgecase\_test（边界回归）

```powershell
java -cp target/classes org.gscript.TestScript edgecase_test run
```

**期望行为变化**：

| 用例            | 旧值    | 新值（JS 正确） |
| ------------- | ----- | --------- |
| `空串==0`       | false | **true**  |
| `null==0`     | false | false（不变） |
| `null==false` | false | false（不变） |

**复杂try嵌套**：上次显示 "Uncaught Error: e2"。这是**预先存在的 try/catch/finally 嵌套 re-throw 被 outer catch 漏接**的问题，与类型转换无关。验证方法：

* 用 `git stash` 暂存类型转换改动 → 重新构建 → 运行 edgecase\_test → 若仍报 "Uncaught Error: e2" 则确认是预先存在

* 或直接审视 [GSValue.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/value/GSValue.java) 与异常处理路径无关（eq/plus 等不涉及 try/catch 字节码生成）

* **不在本任务范围内修复**，仅确认非回归

### 步骤 5：运行 Python 回归测试

```powershell
cd tests; python run_baseline.py
```

**期望**：全部通过。Python 测试主要覆盖调试器/VM 交互，不直接断言类型转换语义，应无回归。如有失败，逐一分析是否与类型转换相关。

### 步骤 6：更新 README.md（文档收尾）

在 [README.md:570](file:///e:/JProjects/gscript/README.md#L570) 的 `>` 提示行之后、`## 字节码`（line 572）之前，插入新章节 `### 隐式类型转换（JS 语义对齐）`。内容覆盖：

**章节结构**：

1. 总述：算术/比较/位运算的隐式类型转换统一经 `GSValue.toNumber`（JS ToNumber 抽象操作）入口
2. ToNumber 转换规则表（bool→0/1, int/float→自身, null→0, NaN→自身, string→严格解析/NaN, object/array/function→NaN）
3. 各运算符语义要点：

   * 关系比较（`<`/`>`/`<=`/`>=`）：两边字符串走字典序，否则 toNumber 比较，NaN→false

   * 宽松相等（`==`/`!=`）：null 仅等于 null，NaN 不等任何值，number vs string→string 转 number 比较

   * 严格相等（`===`）：类型不同直接 false（int/float 互比例外）

   * 算术（`-`/`*`/`/`/`%`）：toNumber 后计算，`/` 总是浮点除法，除零→NaN

   * 加法（`+`）：任一为字符串/对象/数组/函数→拼接；否则 toNumber 数值加

   * 一元负号（`-`）：toNumber 后取负

   * 位运算（`&`/`|`/`^`/`~`/`<<`/`>>`）：toInt32（toNumber 后取整，NaN→0）

   * 自增自减（`++`/`--`）：toNumber 后 ±1
4. 典型示例代码块（参照 implicit\_conversion\_test 用例）
5. 行为变更提示：`"23" > 0` 从 false→true，`"" == 0` 从 false→true，`null + 1` 从 "null1"→1

**风格**：与现有"全局类型转换函数"章节一致——表格 + 代码块 + `>` 提示，中文说明。

### 步骤 7：更新 README 测试命令清单

[README.md:1784](file:///e:/JProjects/gscript/README.md#L1784) 已列出 `type_conversion_test`。在同一测试命令列表中补充 `implicit_conversion_test`：

```code
java -cp target/classes org.gscript.TestScript implicit_conversion_test run   # 隐式类型转换（JS 语义对齐）
```

## Assumptions & Decisions

* **实现已冻结**：GSValue.java / TypeLib.java 不再修改。本计划仅做测试验证与文档。若测试发现实现 bug，需重新评估（但根据代码审视，实现与 JS 语义一致，预期无需改动）。

* **测试预期优先**：若 implicit\_conversion\_test 出现 FAIL，优先核对是否测试预期写错（如 `"23" | 4` 那种二进制位已置位的情况），而非改实现。

* **复杂try嵌套不修**：edgecase\_test 的 complexTry "Uncaught Error: e2" 是预先存在的 try/catch/finally 嵌套异常处理 bug，与类型转换无关，不在本任务范围。

* **JDK 1.8 编译**：项目目标字节码 1.4，但编译用 JDK 1.8（环境约束），`$env:JAVA_HOME` 需显式设置。

* **README 章节级别**：用 `###`（与"全局类型转换函数"同级），父章节为 `## 内置类型与方法`（[README.md:367](file:///e:/JProjects/gscript/README.md#L367)）。新章节插入在 `### 全局类型转换函数` 末尾（line 570 的 `>` 提示后）与 `## 字节码`（line 572）之间。

## Verification Steps

1. `mvn clean package -DskipTests` 构建成功，无编译错误
2. `implicit_conversion_test` 全部 `[OK]`，重点用例（`"23" > 0`、`"5" | 2`、`s++`）通过
3. `type_conversion_test` 全部 OK（TypeLib.NUMBER 委托无回归）
4. `edgecase_test`：`空串==0` = true（预期变更），其余不变；complexTry 仍报 e2（预先存在，非回归）
5. `python run_baseline.py` 全部通过
6. README.md 新增"隐式类型转换（JS 语义对齐）"章节，内容完整，链接正确
7. README.md 测试命令清单包含 `implicit_conversion_test`

## 不在范围内

* 不修改 GSValue.java / TypeLib.java 实现（已对齐 JS 语义）

* 不修复 edgecase\_test complexTry 的 try/catch/finally 嵌套 bug（预先存在，与类型转换无关）

* 不为 toNumber 增加 hex 字符串/Infinity 支持（保持与 JS Number 语义一致，gscript 无 Infinity 类型）

* 不修改严格相等 `seq`（已正确：类型不同直接 false）

