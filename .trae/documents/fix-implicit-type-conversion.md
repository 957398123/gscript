# 修复 gscript 隐式类型转换（对齐 JS 语义）

## Context

gscript 引擎在隐式类型转换上与 JS 语义不一致，导致混合类型运算/比较结果错误。用户报告的核心问题：

```javascript
var a = "23";
a > 0    // 当前返回 false，应返回 true（JS: "23" → 23, 23 > 0 = true）
```

根因：[GSValue.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/value/GSValue.java) 中的算术/比较/位运算方法对非数值类型（type > 3）一律短路返回 `false` 或 `NaN`，未做 JS 的 ToNumber/ToPrimitive 转换。这导致一系列与 JS 不符的行为，需要统一修复。

## 问题清单（全部在 GSValue.java 静态方法中）

| # | 方法 | 当前行为 | JS 期望 | 示例 |
|---|------|---------|---------|------|
| 1 | `gt`/`lt`/`ge`/`le` | string vs number 直接返回 false | string→number 后比较 | `"23" > 0` → true |
| 2 | `eq`/`neq` | string vs number 转为 string 比较 | string→number 后比较 | `"3.0" == 3` → true；`"" == 0` → true |
| 3 | `minus`/`mul`/`div`/`modulo` | 非数值返回 NaN | ToNumber 后运算 | `"23" - 5` → 18；`null + 1` 在 plus 中错误走拼接 |
| 4 | `plus` | 非数值且非字符串走 string 拼接 | 非字符串走数值加法 | `null + 1` → 应为 1，当前 "null1" |
| 5 | `neg` | 非数值返回 NaN | ToNumber 后取负 | `-"23"` → -23 |
| 6 | `b_and`/`b_or`/`b_xor`/`b_not` | 直接 `toIntValue()`（GSString 继承 GSObject 返回 0） | ToNumber→ToInt32 | `"23" & 3` → 3，当前 0 |
| 7 | `ls`/`rs` | 非数值返回 NaN | ToNumber→ToInt32 | `1 << "2"` → 4，当前 NaN |
| 8 | `incr`/`decr` | 非数值返回 NaN | ToNumber 后 ±1 | `"23".incr()` → 24 |

## 实现方案

**单一改动文件**：[GSValue.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/value/GSValue.java)

### 步骤 1：新增 `toNumber(GSValue)` 静态方法

在 GSValue 中新增 JS ToNumber 抽象操作的实现，**复用 [TypeLib.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/stdlib/TypeLib.java) `NUMBER` 函数的解析逻辑**（bool→0/1, null→0, NaN→NaN, string 严格解析, object/array/function→NaN）。

```java
public static GSValue toNumber(GSValue v) {
    switch (v.type) {
        case 1: return new GSInt(v.toIntValue());        // bool → 0/1
        case 2:                                             // int
        case 3: return v;                                  // float
        case 8: return new GSInt(0);                       // null → 0
        case 10: return GSNaN.NAN;                         // NaN
        case 5: {                                          // string 严格解析（trim 后整体合法）
            String s = v.toStringValue().trim();
            if (s.length() == 0) return new GSInt(0);
            try { return new GSInt(Integer.parseInt(s)); }
            catch (NumberFormatException e1) {
                try { return new GSFloat(Float.parseFloat(s)); }
                catch (NumberFormatException e2) { return GSNaN.NAN; }
            }
        }
        default: return GSNaN.NAN;                         // object/array/function → NaN
    }
}
```

并让 `TypeLib.NUMBER` 内部改为 `return GSValue.toNumber(v)`，消除重复（**TypeLib 是 stdlib 包，可依赖 value 包**，无循环依赖）。

### 步骤 2：重写 `eq`（宽松相等 `==`）

按 JS Abstract Equality Comparison 算法：
1. 两边 null → true；仅一边 null → false（gscript 无 undefined）
2. 任一 NaN → false
3. 两边数值类型（含 bool，type ≤ 3）→ 数值比较
4. 一边数值(含 bool)/一边字符串 → 字符串经 `toNumber` 转，NaN→false，否则数值比较
5. 两边字符串 → 字符串比较
6. 其余（对象/数组/函数）→ 引用比较

### 步骤 3：重写 `gt`/`lt`/`ge`/`le`（关系比较）

按 JS Abstract Relational Comparison 算法：
1. 两边都是字符串 → 字典序比较（保留现有行为）
2. 否则 → 两边 `toNumber` 后数值比较；任一 NaN → 返回 false（`gt`/`ge`/`lt`/`le` 一致）

### 步骤 4：重写 `minus`/`mul`/`div`/`modulo`

两边 `toNumber`，任一 NaN → 返回 NaN；否则按原数值提升规则（int/int→int，含 float→float）运算。`div` 保留除零返回 NaN 的现有语义。

### 步骤 5：修正 `plus`

- 任一为字符串（type==5）→ 字符串拼接（**保留现有行为**，JS 语义）
- 否则两边 `toNumber` 后数值加法（修复 `null + 1`、`null + null` 等）

### 步骤 6：重写 `neg`

`toNumber` 后取负；NaN → NaN。

### 步骤 7：重写位运算 `b_and`/`b_or`/`b_xor`/`b_not`/`ls`/`rs`

两边 `toNumber` → `toIntValue()`（JS ToInt32 语义：NaN/null→0，bool→0/1，float 截断，string 解析后截断）。NaN 的 `toIntValue` 通过 toNumber 先转成 GSNaN → 需在调用前判断，NaN 时用 0。具体：新增私有辅助 `toInt32(GSValue)` = `toNumber(v)` 若 NaN 返回 0 否则 `toIntValue()`。

### 步骤 8：重写 `incr`/`decr`

`toNumber` 后 ±1；NaN → NaN。结果类型跟随 toNumber 结果（int→GSInt，float→GSFloat）。

## 测试计划

### 新增测试脚本 `src/main/resources/implicit_conversion_test.script`

参照 [type_conversion_test.script](file:///e:/JProjects/gscript/src/main/resources/type_conversion_test.script) 的 `check(name, expected, actual)` 模式，覆盖：

- **关系比较**：`"23" > 0`=true、`"23" < 5`=false、`"23" >= 23`=true、`"abc" > 0`=false、`null > -1`=true、`null < 1`=true、`null >= 0`=true、`null <= 0`=true、`"10" < "9"`=true（字典序）、`"10" < 9`=false（数值）
- **宽松相等**：`"23" == 23`=true、`"3.0" == 3`=true、`"" == 0`=true、`"  " == 0`=true、`"abc" == 0`=false、`true == "1"`=true、`false == ""`=true、`null == 0`=false、`null == false`=false、`NaN == NaN`=false
- **算术**：`"23" - 5`=18、`"23" * 2`=46、`"3.14" * 2`=6.28、`"abc" - 5`=NaN、`null + 1`=1、`null * 5`=0、`true + 1`=2、`"5" + 3`="53"（拼接）、`5 + null`=5
- **一元负号**：`-"23"`=-23、`-null`=0、`-true`=-1、`-"abc"`=NaN
- **位运算**：`"23" & 3`=3、`"23" | 4`=27、`~"23"`=-24、`1 << "2"`=4、`"10" >> 1`=5、`null & 3`=0
- **自增自减**：`var s = "23"; s++` 后 `s`=24

### 回归测试

1. 先运行现有测试建立基线：
   ```powershell
   $env:JAVA_HOME="C:\Program Files\Java\jdk1.8.0_202"
   mvn clean package -DskipTests
   java -cp target/classes org.gscript.TestScript type_conversion_test run
   java -cp target/classes org.gscript.TestScript edgecase_test run
   java -cp target/classes org.gscript.TestScript implicit_conversion_test run
   ```
2. 运行 Python 测试套件确保不回归：
   ```powershell
   cd tests; python run_baseline.py
   ```

### 预期行为变化（与现有 `edgecase_test.script` 对比）

| 用例 | 旧值 | 新值（JS 正确） |
|------|------|----------------|
| `空串==0` | false | **true** |
| `null==0` | false | false（不变） |
| `null==false` | false | false（不变） |

`edgecase_test.script` 用 `check(label, actual)` 仅打印不断言，无需改测试，但输出会变化。

## 文档更新

修改 [README.md](file:///e:/JProjects/gscript/README.md)：
- 在「全局类型转换函数」章节后新增「## 隐式类型转换（JS 语义对齐）」小节，说明算术/比较/位运算的 ToNumber 规则与典型示例
- 更新「内置类型与方法」相关描述

## 不在范围内

- 不修改 `seq`（严格相等 `===`）—— 类型不同直接返回 false，已正确
- 不修改 `b_not`/`l_not` 之外的一元运算
- 不修改 `getfield`/`putfield`/`OP_FALSE_JUMP` 等使用 `toBoolean` 的路径（已正确）
- 不为 `Number()` 增加 hex 字符串支持（保持与现有 TypeLib.NUMBER 一致，避免范围蔓延）
