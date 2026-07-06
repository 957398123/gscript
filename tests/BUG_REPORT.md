# gscript 引擎隐藏 Bug 报告

> 测试日期: 2026-07-06
> 测试方法: 3 个综合测试脚本（约 400+ 检查点），覆盖算术/类型转换/比较/位运算/字符串/数组/对象/函数/闭包/异常处理/递归/数值边界/相等语义

## 概述

通过系统性的综合测试，共发现 **12 个问题**，其中：
- **P0 崩溃级**: 1 个（模0异常崩溃）
- **P1 严重**: 7 个（逻辑运算/链式一元/函数提升/字符串indexOf/数组转数字/字面量溢出/模0）
- **P2 功能缺失**: 3 个（typeof/科学计数法/无符号右移）
- **设计差异**: 1 个（无undefined类型）

所有测试用例位于: `src/main/resources/comprehensive_test{,2,3}.script`

---

## Bug #1 (P1): 链式一元运算符 `!!x` 不支持

**严重程度**: P1 — 常见 JS 模式无法使用

**现象**: `!!x`、`--x`（前置）、`~~x` 等链式一元运算符会导致编译错误：
```
Error: Unexpected token '!' while parsing primary expression
```

**根因**: `Parser.parseUnaryExpression()` 在消费一个一元运算符后调用 `parsePostfixExpression()` 而非递归调用 `parseUnaryExpression()`，导致无法处理连续的一元运算符。

**位置**: `src/main/java/org/gscript/compile/Parser.java:1021`
```java
// BUG: 应该调用 parseUnaryExpression() 而非 parsePostfixExpression()
if (isUnaryOperator(peek())) {
    operator = new Operator(advance());
    Node operand = parsePostfixExpression();  // ← BUG: 不递归
    return new UnaryExpression(operator, operand);
}
```

**修复**: 将 `parsePostfixExpression()` 改为 `parseUnaryExpression()` 实现递归。

**测试**: `!!({})` → 编译错误

---

## Bug #2 (P0 崩溃): 模零运算 `10 % 0` 抛出 ArithmeticException

**严重程度**: P0 — 脚本直接崩溃，无法被 try/catch 捕获

**现象**: `10 % 0` 或 `0 % 0` 导致 Java `ArithmeticException: / by zero`，整个脚本崩溃退出。

**根因**: `GSValue.modulo()` 对 int 类型直接执行 Java `%` 运算，未检查除数是否为 0。对比 `div()` 方法有零检查，`modulo()` 缺失。

**位置**: `src/main/java/org/gscript/vm/value/GSValue.java:454`
```java
public static final GSValue modulo(GSValue v1, GSValue v2) {
    GSValue n1 = toNumber(v1);
    GSValue n2 = toNumber(v2);
    if (n1.type == 10 || n2.type == 10) { return GSNaN.NAN; }
    if (n1.type == 3 || n2.type == 3) {
        return new GSFloat(n1.toFloatValue() % n2.toFloatValue());  // float 模0返回NaN，OK
    }
    return new GSInt(n1.toIntValue() % n2.toIntValue());  // ← BUG: int模0抛ArithmeticException
}
```

**修复**: 在 int 路径增加零检查：
```java
int i2 = n2.toIntValue();
if (i2 == 0) return GSNaN.NAN;
return new GSInt(n1.toIntValue() % i2);
```

**测试**: `10 % 0` → 预期 `NaN`，实际 `ArithmeticException: / by zero`

---

## Bug #3 (设计差异): 无 `undefined` 类型

**严重程度**: P2 — 设计决策，与 JS 语义有差异

**现象**: gscript 用 `null` 同时表示 JS 的 `null` 和 `undefined`。
- `null === undefined` → `true`（JS 应为 `false`）
- 缺失参数为 `null` 而非 `undefined`
- 未初始化变量为 `null` 而非 `undefined`

**根因**: 语言设计决策，`GSValue` 类型系统中没有 undefined 类型（type=8 为 null，无 undefined）。

**位置**: `src/main/java/org/gscript/compile/gen/ByteCodeGenerator.java:1340`（注释明确说明）

---

## Bug #4 (P1): 逻辑运算符 `&&`/`||` 返回布尔值而非操作数

**严重程度**: P1 — 影响 JS 常见模式（短路返回值、默认值）

**现象**:
| 表达式 | JS 期望 | gscript 实际 |
|--------|---------|-------------|
| `1 && 2` | `2` | `true` |
| `0 && 2` | `0` | `false` |
| `0 \|\| 2` | `2` | `true` |
| `1 \|\| 2` | `1` | `true` |
| `null \|\| "x"` | `"x"` | `true` |
| `NaN \|\| 1` | `1` | `true` |

**根因**: `ByteCodeGenerator` 的 `visit(LogicalORExpression)` 和 `visit(LogicalANDExpression)` 生成代码时，始终推送 `const b true` 或 `const b false` 作为结果，而非保留原始操作数值。

**位置**: `src/main/java/org/gscript/compile/gen/ByteCodeGenerator.java:595-599, 623-627`
```java
// visit(LogicalORExpression) - BUG
emit("const b true");   // ← 应保留 left 或 right 的值
emit("jump 2");
emit("const b false");  // ← 应保留 left 或 right 的值
```

**修复**: 使用栈复制操作保留操作数：
- `||`: 评估 left → dup → 若 truthy 跳到 end → pop → 评估 right → end
- `&&`: 评估 left → dup → 若 falsy 跳到 end → pop → 评估 right → end

---

## Bug #5 (P2): `typeof` 运算符不支持

**严重程度**: P2 — 类型检查无法使用

**现象**: `typeof x` 会编译错误或被当作标识符处理。

**根因**: `Lexer` 的关键字列表中无 `typeof`，Parser 也无对应的解析逻辑。

**位置**: `src/main/java/org/gscript/compile/Lexer.java:22-26`（关键字列表）

---

## Bug #6 (P2): 科学计数法字面量不支持

**严重程度**: P2 — 大数字面量无法书写

**现象**: `1e15`、`1.5e-3` 等科学计数法会导致解析错误。

**根因**: `Lexer` 的数字解析只处理 `0-9` 和 `.`，不识别 `e`/`E` 指数部分。

**位置**: `src/main/java/org/gscript/compile/Lexer.java:98-110`

---

## Bug #7 (P1): 字符串 `indexOf` 忽略 `fromIndex` 参数

**严重程度**: P1 — 字符串搜索结果错误

**现象**: `"hello".indexOf("l", 3)` 返回 `2`（第一个 "l" 的位置），JS 应返回 `3`（位置 3 之后的 "l"）。

**根因**: `GSString.INDEX_OF` 实现只调用 `str.indexOf(needle)`，完全忽略第三个参数 `fromIndex`。

**位置**: `src/main/java/org/gscript/vm/value/GSString.java:61-69`
```java
private static final GSNativeFunction INDEX_OF = new GSNativeFunction("indexOf") {
    public GSValue call(ArrayList args) {
        String str = ((GSString) args.get(0)).value;
        if (args.size() < 2) { return new GSInt(-1); }
        String needle = ((GSValue) args.get(1)).toStringValue();
        return new GSInt(str.indexOf(needle));  // ← BUG: 忽略 fromIndex
    }
};
```

**修复**:
```java
int fromIndex = 0;
if (args.size() >= 3) {
    fromIndex = ((GSValue) args.get(2)).toIntValue();
    if (fromIndex < 0) fromIndex = 0;
}
return new GSInt(str.indexOf(needle, fromIndex));
```

---

## Bug #8 (P2): `>>>` 无符号右移不支持

**严重程度**: P2 — 位运算功能缺失

**现象**: `-1 >>> 0` 会导致编译错误。

**根因**: `Lexer` 不识别 `>>>` token，`Parser` 无对应解析。

**位置**: `src/main/java/org/gscript/compile/Lexer.java:248-264`

---

## Bug #9 (P1): `-2147483648` 字面量导致 NumberFormatException

**严重程度**: P1 — INT_MIN 无法直接书写

**现象**: 写 `-2147483648` 字面量会导致 `NumberFormatException: For input string: "2147483648"`。

**根因**: Lexer 将 `2147483648` 解析为正整数 token，`BytecodeEncoder` 用 `Integer.parseInt("2147483648")` 解析时溢出（Java int 最大值是 2147483647）。`-` 是一元运算符，在字面量解析之后才处理。

**位置**: `src/main/java/org/gscript/compile/gclass/BytecodeEncoder.java:81`

**修复**: 在 `BytecodeEncoder` 中，如果 int 解析溢出，回退为 `Long.parseLong` 再转 `float`，或在 Lexer 层处理负数字面量。

**变通**: 使用 `0 - 2147483647 - 1` 表达式。

---

## Bug #10 (P1): 函数声明提升不支持

**严重程度**: P1 — 影响代码组织灵活性

**现象**: 函数体内，在函数声明之前调用该函数会报 `TypeError: function not exist.`

```javascript
function hoistedCall() {
    return callBeforeDef();   // ← JS: 提升，可调用；gscript: 报错
    function callBeforeDef() { return "hoisted"; }
}
```

**根因**: gscript 不实现函数声明提升。JS 规范中，函数声明在作用域创建时即被注册，可在源码位置之前调用。gscript 按顺序处理，未提升。

**位置**: `src/main/java/org/gscript/compile/gen/ByteCodeGenerator.java`（函数声明处理）

---

## Bug #11 (P2 设计差异): `var` 提升初始化为 `null` 而非 `undefined`

**严重程度**: P2 — 与 Bug #3 一致的设计差异

**现象**: 在 `var x` 声明前访问 `x`，gscript 得到 `null`（JS 为 `undefined`）。

```javascript
function varHoist() {
    var before = x;   // gscript: null, JS: undefined
    var x = 1;
    return before + "," + x;  // gscript: "null,1", JS: "undefined,1"
}
```

**根因**: 与 Bug #3 一致，gscript 用 `null` 表示所有"无值"状态。

---

## Bug #12 (P1): `Number([])` 和 `Number([5])` 返回 NaN

**严重程度**: P1 — 数组转数字语义错误

**现象**:
| 表达式 | JS 期望 | gscript 实际 |
|--------|---------|-------------|
| `Number([])` | `0` | `NaN` |
| `Number([5])` | `5` | `NaN` |
| `Number([1,2])` | `NaN` | `NaN` ✓ |

**根因**: `GSValue.toNumber()` 的 `default` 分支将所有 object/array/function 统一返回 NaN，未对数组做特殊处理。JS 中数组转数字先 `toString` 再转数字（`[]` → `""` → `0`，`[5]` → `"5"` → `5`）。

**位置**: `src/main/java/org/gscript/vm/value/GSValue.java:96-97`
```java
default:  // object/array/function → NaN  ← BUG: 数组应先toString
    return GSNaN.NAN;
```

**修复**: 增加 case 7 (array) 处理：
```java
case 7: {  // array → toString → 再解析为数字
    String s = v.toStringValue().trim();
    if (s.length() == 0) return new GSInt(0);
    // 复用 string 解析逻辑
    ...
}
```

---

## 测试统计

| 测试文件 | 检查点数 | 通过 | 失败 | 崩溃 |
|---------|---------|------|------|------|
| comprehensive_test.script | ~160 | 148 | 12 | 1(模0) |
| comprehensive_test2.script | ~150 | 148 | 2 | 0 |
| comprehensive_test3.script | ~100 | 96 | 4 | 0 |
| **合计** | **~410** | **392** | **18** | **1** |

## 优先级汇总

### P0 (崩溃级 — 应立即修复)
- **Bug #2**: 模零运算 `10 % 0` 抛 ArithmeticException 崩溃

### P1 (严重 — 影响核心功能)
- **Bug #1**: 链式一元运算符 `!!x` 编译错误
- **Bug #4**: `&&`/`||` 返回布尔而非操作数（影响默认值模式）
- **Bug #7**: 字符串 `indexOf` 忽略 `fromIndex` 参数
- **Bug #9**: `-2147483648` 字面量溢出
- **Bug #10**: 函数声明提升不支持
- **Bug #12**: `Number([])`/`Number([5])` 返回 NaN

### P2 (功能缺失/设计差异)
- **Bug #3**: 无 `undefined` 类型（设计差异）
- **Bug #5**: `typeof` 运算符缺失
- **Bug #6**: 科学计数法字面量不支持
- **Bug #8**: `>>>` 无符号右移不支持
- **Bug #11**: `var` 提升为 `null` 而非 `undefined`（设计差异）

---

## 建议修复顺序

1. **Bug #2** (P0) — `GSValue.modulo()` 加零检查，5 分钟
2. **Bug #4** (P1) — 重写 `visit(LogicalORExpression/ANDExpression)` 保留操作数，30 分钟
3. **Bug #7** (P1) — `GSString.INDEX_OF` 加 fromIndex 参数，5 分钟
4. **Bug #12** (P1) — `toNumber()` 增加数组处理，15 分钟
5. **Bug #1** (P1) — `parseUnaryExpression` 改为递归调用，5 分钟
6. **Bug #9** (P1) — `BytecodeEncoder` int 溢出回退，10 分钟
7. **Bug #10** (P1) — 函数声明提升，需较大改动，1-2 小时
8. Bug #5, #6, #8 (P2) — 功能补全，各 15-30 分钟
