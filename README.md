## 语法定义

```ebnf
(* 使用EBNF定义语法 *)
(* 程序入口*)
Program
    = { Statement };

(* 语句定义*)
Statement
    = BlockStatement
    | EmptyStatement
    | VariableStatement
    | IfStatement
    | SwitchStatement
    | ForStatement
    | DoWhileStatement
    | WhileStatement
    | FunctionStatement
    | BreakStatement
    | ContinueStatement
    | ReturnStatement
    | ThrowStatement
    | ExceptionStatement
    | ExpressionStatement;

(* 块语句 {}包围，里面的Statement可以出现0次或者多次*)
BlockStatement
    = '{', { Statement }, '}';

(* 空语句*)
EmptyStatement
    = ";";

(* 变量声明语句 var a = 9; var a, b = 3;*)
VariableStatement
    = VariableDeclList, ";";

(* 变量声明list*)
VariableDeclList
    = "var", VariableDecl, { ",", VariableDecl };

(* 变量声明*)
VariableDecl
    = Identifier, [ "=", Expression ];

(* 标识符 标识符只能够_或者$以及字母开头*)
Identifier
    = ( "_"
    | "$"
    | [ "a" - "z"
    | "A" - "Z" ] ), { "_"
    | "$"
    | [ "a" - "z"
    | "A" - "Z"
    | "0" - "9" ] };

(* if语句 不支持一行if*)
IfStatement
    = "if", "(", Expression, ")", BlockStatement, [ "else", ( BlockStatement
    | IfStatement ) ];

(* SWITCH语句*)
SwitchStatement
    = "switch", "(", Expression, ")", "{", { "case", Expression, ":", "{" Statement } }, [ "default", ":", { Statement } ], "}";

(* FOR循环*)
ForStatement
    = "for", "(", [ VariableDeclList
    | Expression ], ";", [ Expression ], ";", [ Expression ], ")", [ ";" | '{', { Statement }, '}'];

(* do语句*)
DoWhileStatement
    = "do", BlockStatement, "while", "(", Expression, ")", ";";

(* while语句*)
WhileStatement
    = "while", "(", Expression, ")", [ ";" | BlockStatement];

(* 函数声明*)
FunctionStatement
    = "function", Identifier, "(", [ ParameterList ], ")", BlockStatement;

(* break语句*)
BreakStatement
    = "break", ";";

(* continue语句*)
ContinueStatement
    = "continue", ";";

(* return语句*)
ReturnStatement
    = "return", [ Expression ], ";";

(* throw语句*)
ThrowStatement
    = "throw", Expression, ";";

(* 异常捕获语句*)
ExceptionStatement
    = TryClause, [ CatchClause, FinallyClause
    | FinallyClause ];

(* try语句 *)
TryClause
    = "try", BlockStatement;

(* catch语句*)
CatchClause
    = "catch", "(", Identifier, ")", BlockStatement;

(* finally语句*)
FinallyClause
    = "finally", BlockStatement;

(* 参数列表*)
ParameterList
    = Identifier, { ",", Identifier };

(* 表达式语句*)
ExpressionStatement
    = Expression, ";";

(* 表达式，表达式一定会计算出一个值，并且放到栈顶  *)
(* 要么是给一个左值变量赋值 *)
Expression
    = ConditionalExpression
    | ( LeftHandSideExpression, AssignmentOperator, Expression );

(* 条件表达式*)
ConditionalExpression
    = LogicalORExpression, [ "?", Expression, ":", Expression ];

(* 逻辑或表达式*)
LogicalORExpression
    = LogicalANDExpression, { "||", LogicalANDExpression };

(* 逻辑与表达式*)
LogicalANDExpression
    = BitwiseORExpression, { "&&", BitwiseORExpression };

(* 按位或*)
BitwiseORExpression
    = BitwiseXORExpression, { "|", BitwiseXORExpression };

(* 按位异或*)
BitwiseXORExpression
    = BitwiseANDExpression, { "^", BitwiseANDExpression };

(* 按位与*)
BitwiseANDExpression
    = EqualityExpression, { "&", EqualityExpression };

(* 比较表达式*)
EqualityExpression
    = RelationalExpression, { ( "=="
    | "!="
    | "==="
    | "!==" ), RelationalExpression };

(* 关系表达式*)
RelationalExpression
    = ShiftExpression, { ( "<"
    | ">"
    | "<="
    | ">=" ), ShiftExpression };

(* 移位表达式*)
ShiftExpression
    = AdditiveExpression, { ( "<<"
    | ">>" ), AdditiveExpression };

(* 加法表达式*)
AdditiveExpression
    = MultiplicativeExpression, { ( "+"
    | "-" ), MultiplicativeExpression };

(* 乘法表达式*)
MultiplicativeExpression
    = UnaryExpression, { ( "*"
    | "/"
    | "%" ), UnaryExpression };

(* 一元表达式*)
UnaryExpression
    = ( "+"
    | "-"
    | "!"
    | "~" ), PostfixExpression
    | ( "++"
    | "--" ), LeftHandSideExpression
    | PostfixExpression;

(* 后缀表达式 *)
PostfixExpression
    = LeftHandSideExpression, ( "++"
    | "--" )
    | PrimaryExpression;

(* 主表达式*)
PrimaryExpression
    = Literal
    | ObjectLiteral
    | FunctionExpression
    | NewExpression
    | AccessProperty
    | CallExpression
    | MemberExpression;

(* 左值表达式，左值表达式是可以出现在赋值操作符左边的表达式 *)
LeftHandSideExpression
    = Identifier
    | MemberExpression;

(* 成员表达式，以属性访问结尾 *)
MemberExpression
    = AccessProperty, { MemberAccess
    | CallSuffix }, MemberAccess;

(* 调用表达式，以调用结尾*)
CallExpression
    = AccessProperty, { MemberAccess
    | CallSuffix }, CallSuffix;

(* 可以访问属性的值，含字面量（支持 "str".length、{a:1}.a、null.foo 等字面量上的成员访问）*)
AccessProperty
    = Identifier
    | "(", Expression, ")"
    | ArrayLiteral
    | Literal
    | ObjectLiteral;

(* 调用后缀 *)
CallSuffix
    = "(", [ ArgumentList ], ")";

(* 函数表达式*)
FunctionExpression
    = "function", [ Identifier ], "(", [ ParameterList ], ")", BlockStatement;

(* new表达式 *)
NewExpression
    = "new" , ( Expression );

(* 成员访问*)
MemberAccess
    = ( ".", Identifier )
    | ( "[", Expression, "]" );

(* 参数列表*)
ArgumentList
    = Expression, { ",", Expression };

(* 数组字面量*)
ArrayLiteral
    = "[", [ ElementList ], "]";

(* 元素列表*)
ElementList
    = Expression, { ",", Expression }, { "," };

(* 对象字面量*)
ObjectLiteral
    = "{", [ PropertyList ], "}";

(* 属性列表*)
PropertyList
    = Property, { ",", Property };

(* 属性定义 *)
Property
    = PropertyName, ":", Expression;

(* 属性名*)
PropertyName
    = Identifier
    | StringLiteral
    | NumericLiteral
    | "[", Expression, "]";

(* 关联符号*)
AssignmentOperator
    = "="
    | "+="
    | "-="
    | "*="
    | "/="
    | "%="
    | "&="
    | "|="
    | "^="
    | "<<="
    | ">>="
    | ">>>="
    | "**=";

(* 字面量*)
Literal
    = NumericLiteral
    | StringLiteral
    | BooleanLiteral
    | NullLiteral;

(* 字符串字面量 - 使用 terminal 表示由词法分析器处理 *)
StringLiteral
    = terminal_STRING;

(* 转义序列 - 保留作为文档说明，实际解析由词法分析器处理 *)
(* EscapeSequence: \b \t \f \n \r \" \' \u{hex} \uXXXX \xXX *)
(* 数字字面量 只支持10进制和16进制 *)
NumericLiteral
    = DecimalLiteral
    | HexIntegerLiteral
    | "NaN";

(* 十进制实数 *)
DecimalLiteral
    = DecimalIntegerLiteral, [ ".", [ DecimalDigits ] ];

(* 十进制整数 *)
DecimalIntegerLiteral
    = "0"
    | [ "1" - "9" ], { DecimalDigits };

(* 十进制数字 *)
DecimalDigits
    = [ "0" - "9" ];

(* 十六进制整数 *)
HexIntegerLiteral
    = "0", [ xX ], HexDigit;

(* 十六进制数字 *)
HexDigit
    = [ "0" - "9"
    | "a" - "f"
    | "A" - "F" ];

(* 布尔字面量*)
BooleanLiteral
    = "true"
    | "false";

(* 空字面量*)
NullLiteral
    = "null";
```

### 字面量上的成员访问

字面量（`null` / `true` / `false` / 字符串 / 数值 / 对象字面量 / 数组字面量）后可直接跟成员访问 `.foo`、索引 `[expr]` 或调用 `(args)`，与 JS 语义一致：

```javascript
{a: 1}.a                  // 1
{a: {b: 2}}.a.b           // 2
{fn: function(){return 42;}}.fn()   // 42
[1, 2, 3][0]              // 1
[[1,2],[3,4]][1][0]       // 3
"str".length              // 3（字符串长度）
null.foo                  // 抛 TypeError: Cannot read properties of null
(42).foo                  // null（数值字面量无属性，静默返回 null）
```

> **词法注意**：`42.foo` 会被词法器解析为浮点字面量 `42.` 加标识符 `foo`（与 JS 一致），需写成 `(42).foo` 或 `42..foo` 才能访问整数字面量的属性；`3.14.bar` 则可直接使用。

## 内置类型与方法

gscript 内置 10 种数据类型（bool / int / float / str / object / array / null / nan / function / native），其中 str / object / array 提供与 JS 对标的属性和方法。所有原生方法以**静态共享 `GSNativeFunction`** 形式实现（语义等价于 JS 的 `Array.prototype.xxx` / `String.prototype.xxx`），所有同类对象共用同一组函数对象，`OP_INVOKE` 调用时 `args[0]` 为对象本身（this）。

### 字符串（str）

字符串字面量 `"hello"` 或 `String(x)` 转换得到。`length` 为数值属性，另支持 17 个方法（对标 JS `String.prototype`）：

| 方法/属性 | 说明 |
|-----------|------|
| `length` | 字符串长度（数值属性） |
| `charAt(i)` / `charCodeAt(i)` | 取字符 / 取字符码（`charAt` 越界返回空串，`charCodeAt` 越界返回 NaN） |
| `indexOf(s[, from])` / `lastIndexOf(s[, from])` | 正向 / 反向查找子串，返回索引，未找到返回 -1 |
| `substring(s, e)` / `slice(s, e)` / `substr(s, len)` | 截取子串（`substring` 不支持负索引且 start>end 时交换；`slice` 支持负索引；`substr` 第二参为长度） |
| `split(sep)` | 按分隔符拆分为数组（无参返回 `[原串]`，空分隔按字符拆） |
| `replace(old, new)` | 替换**首次**出现（非正则，手写 indexOf + substring 拼接） |
| `trim()` / `toUpperCase()` / `toLowerCase()` | 去首尾空白 / 转大写 / 转小写 |
| `startsWith(s)` / `endsWith(s)` / `includes(s)` | 前缀 / 后缀 / 包含判断 |
| `repeat(n)` | 重复 n 次（n<0 抛 `RangeError: Invalid count value`） |
| `concat(s)` | 拼接字符串 |

```javascript
"hello".length;              // 5
"hello".charAt(1);           // "e"
"hello".indexOf("l");        // 2
"hello".slice(-2);           // "lo"
"a,b,c".split(",");          // ["a","b","c"]
"hello".replace("l", "L");   // "heLlo"（仅首次）
"  hi  ".trim();             // "hi"
"abc".repeat(3);             // "abcabcabc"
```

### 数组（array）

数组字面量 `[1,2,3]` 或 `new Array()`。`length` **可读可写**（写时按 JS 语义截断或扩容），另支持 11 个方法（对标 JS `Array.prototype`）：

| 方法/属性 | 说明 |
|-----------|------|
| `length` | 元素个数（**可写**：`arr.length = n` 截断到 n，或扩容补 `null`） |
| `push(...items)` / `pop()` | 尾部追加（返回新长度）/ 删除并返回尾部元素（空数组返回 `null`） |
| `shift()` / `unshift(...items)` | 头部删除并返回 / 头部插入（返回新长度） |
| `indexOf(item[, from])` | 严格相等（`seq`）查找，返回索引，未找到返回 -1；支持 `from` 负索引 |
| `join([sep])` | 用分隔符连接（默认 `,`），`null` 元素输出空串 |
| `slice([start[, end]])` | 区间浅拷贝（新数组），支持负索引 |
| `splice(start[, deleteCount[, ...items]])` | 删除 / 插入 / 替换，返回被删元素数组（JS 语义） |
| `forEach(cb)` | 遍历，对每个元素调用 `cb(element, index, array)`，返回 `null` |
| `map(cb)` | 映射，收集 `cb(element, index, array)` 返回值到新数组 |
| `filter(cb)` | 过滤，保留 `cb` 返回 truthy 的原元素到新数组 |

> **forEach/map/filter 实现机制**：这三个方法需在原生函数内回调 gscript 函数，依赖 `GSNativeFunction.call(args, interp)` 重载（方案 F）。`OP_INVOKE` type==9 调用时通过 `eval(callArgs, this)` 传入当前 interpreter，原生方法内用 `interp.callFunction(cb, cbArgs)` 回调。`cbArgs = [null(this占位), element, index, array]`。callback 在 worker 线程同步执行，抛出的异常沿调用栈传播可被外层 `catch` 捕获。

**`length` 可写语义**（JS 对标）：

```javascript
var a = [1, 2, 3, 4, 5];
a.length = 3;                // 截断，a=[1,2,3]
a.length = 5;                // 扩容，a=[1,2,3,null,null]
a.length = 0;                // 清空
```

**`splice` 语义**（ES5 `Array.prototype.splice` 规范）：

```javascript
var a = [1, 2, 3, 4, 5];
a.splice(1, 2);              // 返回 [2,3]，a=[1,4,5]（删除）
a.splice(1, 0, 9, 9);        // 返回 []，a=[1,9,9,4,5]（插入）
a.splice(1, 1, 8);           // 返回 [9]，a=[1,8,9,4,5]（替换）
a.splice(-1, 1);             // 负索引：start+=len
a.splice(2);                 // deleteCount 缺省：删到末尾
a.splice();                  // 0 实参：无操作（返回 []，原数组不变）
```

> **gscript 无 hole 概念**：JS 数组扩容产生 empty slot（hole），gscript 用 `null` 近似。`computeLength` 从索引 0 开始取最大连续索引 + 1，遇 hole 停止。

**`forEach` / `map` / `filter` 语义**（JS 对标，回调签名 `cb(element, index, array)`）：

```javascript
var arr = [1, 2, 3, 4, 5];

// forEach：遍历（返回 null）
var sum = 0;
arr.forEach(function(x) { sum = sum + x; });    // sum = 15

// map：映射到新数组（不修改原数组）
var doubled = arr.map(function(x) { return x * 2; });  // [2, 4, 6, 8, 10]

// filter：过滤到新数组（保留原元素，非 cb 返回值）
var even = arr.filter(function(x) { return x % 2 == 0; });  // [2, 4]

// 链式调用
arr.map(function(x) { return x * x; }).filter(function(x) { return x > 4; });  // [9, 16, 25]

// 回调签名 (element, index, array)
arr.forEach(function(elem, idx, array) {
    console.log(elem + " at " + idx + " of " + array.length);
});

// 异常传播：cb 抛出的异常可被外层 catch 捕获
try {
    arr.forEach(function(x) { if (x == 3) throw "found three"; });
} catch (e) {
    console.log(e);  // found three
}
```

### 对象（object）

对象字面量 `{a: 1}` 或 `new Object()`。支持 `keys()` 方法返回属性名数组：

| 方法 | 说明 |
|------|------|
| `keys()` | 返回全部属性名数组（对标 `Object.keys()`，顺序不保证） |

```javascript
var o = {a: 1, b: 2, c: 3};
o.keys();                    // 例如 ["a","b","c"]（顺序不保证）
o.keys().length;             // 3
```

> **自有属性优先**：成员中已有同名 key 时优先返回成员值。如 `{keys: 99}.keys` 返回 `99` 而非 `keys()` 方法对象，对标 JS 自有属性优先于原型方法。仅当成员不存在时才返回内置方法。

### 闭包与作用域

gscript 支持**词法作用域 + 闭包**：函数定义时捕获外层 `GSEnv` 作用域引用，调用时沿作用域链（`env.parent`）查找变量。

```javascript
function makeCounter() {
    var count = 0;
    return function() {       // 闭包捕获外层 count
        count = count + 1;
        return count;
    };
}
var c1 = makeCounter();
var c2 = makeCounter();
c1(); c1();                   // c1: 2
c2();                         // c2: 1（c1/c2 独立计数，各自的环境互不影响）
```

### this 绑定

对象方法内的 `this` 绑定到调用对象（`OP_INVOKE` 将 `objectRef` 作为 `args[0]` 即隐式 `this` 传入）：

```javascript
var obj = {
    name: "Tom",
    getName: function() { return this.name; }
};
obj.getName();                // "Tom"
```

对象字面量可直接定义函数属性并调用：`{fn: function(){return 42;}}.fn()` 返回 42。

### 异常处理

gscript 支持 `try / catch / finally / throw`，语义对标 JS。`throw` 可抛出任意 GSValue（字符串/对象/数组等），异常沿调用栈传播直到被 `catch` 捕获，`finally` 块无论是否异常都执行。

```javascript
try {
    throw "boom";
} catch (e) {
    console.log("caught: " + e);    // caught: boom
} finally {
    console.log("finally");          // finally（始终执行）
}
```

**异常信息格式**（`GSException.formatMessage()`）：未捕获异常打印多行格式，列出 throw 点 + caller 调用栈（替代旧的 `at <anonymous>:<ip>` 字节码 IP，对用户无意义）：

```
Uncaught Error: boom
  at timer.script:3 (in badCall)
  at timer.script:6 (in <anonymous>)
```

`GSException` 携带源码映射字段（`sourceLines`/`sourcePath`/`originIp`/`originSourceLine`）+ `callStack`（caller 帧列表，从内到外）。`OP_INVOKE`/`OP_CONSTRUCTOR` catch 块中 `appendCaller(frame)` 追加调用者帧，`ex.setIp(frame.getIP() - 1)` rebase 到 invoke 指令位置——保证跨函数抛出的异常能被调用者 try-catch 捕获（否则 `handleException` 会用 callee 的 ip 空间比对 caller 的 monitor 范围，导致捕获失败）。

原生函数抛出的异常（如 `"x".repeat(-1)` 抛 `RangeError: Invalid count value`）同样支持 rebase，`OP_INVOKE` type==9 分支 try-catch 同构处理。

### 全局类型转换函数

构造器默认注册到 global 域（`installTypeGlobals()`，与 `installTimerGlobals()` 并列）：

| 函数 | 说明 |
|------|------|
| `parseInt(s[, radix])` | 解析整数：提取前导数字，支持 radix 2-36，`"0x"` 前缀自动切 16 进制 |
| `parseFloat(s)` | 解析浮点：手写提取 `[符号][整数][.小数][e±指数]` 前缀 |
| `isNaN(x)` | 判断 NaN：NaN 直接判断；字符串走 `Number` 转换后判断（`isNaN("")` = `true` 是 JS 特例） |
| `String(x)` | 转 gscript 字符串 |
| `Number(x)` | 转 gscript 数值（要求整体合法，否则返回 NaN） |
| `Boolean(x)` | 转 gscript 布尔（等价 `toBoolean`：falsy = `0`/`""`/`null`/`NaN`） |

```javascript
parseInt("123abc");          // 123（提取前导数字）
parseInt("0xFF");            // 255（0x 前缀自动切 16 进制）
parseInt("3.14");            // 3（截断小数）
Number("123abc");            // NaN（要求整体合法）
Number("3.14");              // 3.14
isNaN("abc");                // true
String(42);                  // "42"
Boolean(0);                  // false
```

> **parseInt vs Number 最易混淆**：`parseInt` 容错提取前导，`Number` 严格整体合法。数值类型参数有短路优化（`parseInt(int)` 直接返回，避免 ToString 往返），但 `bool`/`null` 不短路（`parseInt(true)` = `NaN`，因 `ToString("true")` 非数字）。

### 隐式类型转换（JS 语义对齐）

算术 / 比较 / 位运算 / 自增自减在混合类型运算时，统一经 [GSValue.toNumber](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/value/GSValue.java#L70)（JS ToNumber 抽象操作）入口转换，行为对齐 JavaScript。`TypeLib.Number(x)` 内部也委托同一入口，保证 `Number(x)` 与隐式转换语义一致。

**ToNumber 转换规则**：

| 输入类型 | 结果 |
|---------|------|
| `bool` | `0` / `1`（int） |
| `int` / `float` | 自身 |
| `null` | `0`（int） |
| `NaN` | 自身 |
| `string` | trim 后整体严格解析：整数→int，浮点→float，否则→NaN（`"23"`→23，`"3.14"`→3.14，`"abc"`→NaN，`""`→0） |
| `object` / `array` / `function` | `NaN` |

**运算符语义要点**：

| 类别 | 运算符 | 语义 |
|------|--------|------|
| 关系比较 | `<` `>` `<=` `>=` | 两边都是字符串 → 字典序比较；否则两边 toNumber 后数值比较，任一 NaN → false |
| 宽松相等 | `==` `!=` | null 仅等于 null；任一 NaN → false；number vs string → string 转 number 后比较；两边字符串 → 值比较 |
| 严格相等 | `===` `!==` | 类型不同直接 false（int/float 同属数值可互比）；NaN 不等任何值 |
| 算术 | `-` `*` `/` `%` | 两边 toNumber 后计算；`/` 总是浮点除法（`7/2`=3.5）；除零 → NaN |
| 加法 | `+` | 任一为字符串/对象/数组/函数 → 字符串拼接；否则（bool/int/float/null/NaN）→ toNumber 后数值加 |
| 一元负号 | `-x` | toNumber 后取负，NaN → NaN |
| 位运算 | `&` `\|` `^` `~` `<<` `>>` | 两边 toInt32（toNumber 后取整，NaN → 0）后运算 |
| 自增自减 | `++` `--` | toNumber 后 ±1，结果类型跟随 toNumber（int→GSInt，float→GSFloat） |

```javascript
var a = "23";
a > 0;            // true（"23" → 23，23 > 0）—— 修复前为 false
a == 23;          // true（string vs number → 转数值比较）
a === 23;         // false（严格相等，类型不同）
a * 2;            // 46
"3.0" == 3;       // true（原 bug：字符串比较 "3.0" != "3"）
"" == 0;          // true（Number("")=0）
"abc" > 0;        // false（NaN 比较 → false）
null + 1;         // 1（原 bug："null1"）
null * 5;         // 0
true + 1;         // 2
"5" + 3;          // "53"（字符串拼接，保留 JS 语义）
"23" & 3;         // 3（toInt32，原 bug：0）
1 << "2";         // 4（原 bug：NaN）
var s = "23"; s++; s;   // 24
-"23";            // -23（原 bug：NaN）
```

> **行为变更**：本次修复对齐 JS 语义，以下用例结果改变：`"23" > 0` 从 `false` → `true`；`"" == 0` 从 `false` → `true`；`"3.0" == 3` 从 `false` → `true`；`null + 1` 从 `"null1"` → `1`；`"23" & 3` 从 `0` → `3`；`1 << "2"` 从 `NaN` → `4`；`-"23"` 从 `NaN` → `-23`。详见 [implicit_conversion_test.script](file:///e:/JProjects/gscript/src/main/resources/implicit_conversion_test.script)。

#### 值类 toIntValue / toFloatValue 重写表

`toIntValue()` 对应 JS ToInt32（位运算、原生方法索引参数用），`toFloatValue()` 对应 JS ToNumber（算术运算用）。二者均由 [GSValue.toNumber](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/value/GSValue.java#L71) 统一驱动，但每个值类必须各自正确重写，否则会出现 `GSString.toIntValue()` 继承 `GSObject` 默认返回 0 导致 `"23"|0` 错误返回 0 的 bug。

| 类 | type | 父类 | toIntValue() | toFloatValue() | 说明 |
|----|------|------|--------------|----------------|------|
| GSBool | 1 | GSValue | 0 / 1 | 0.0 / 1.0 | 直接返回 bool 值 |
| GSInt | 2 | GSValue | value | (float)value | 直接返回数值 |
| GSFloat | 3 | GSValue | (int)value | value | 截断取整 |
| GSObject | 4 | GSValue | 0 | Float.NaN | 默认实现：对象 ToNumber=NaN，ToInt32(NaN)=0 |
| GSString | 5 | GSObject | toNumber→int | toNumber→float | 解析字符串，NaN→0（int）/ NaN（float） |
| GSFunction | 6 | GSObject | 继承 0 | 继承 NaN | 函数 ToNumber=NaN |
| GSArray | 7 | GSObject | toNumber→int | toNumber→float | 先 toString 再按 string 规则解析 |
| GSNull | 8 | GSObject | 0（显式） | 0（显式） | null ToNumber=0 |
| GSNativeFunction | 9 | GSObject | 继承 0 | 继承 NaN | 原生函数 ToNumber=NaN |
| GSNaN | 10 | GSObject | 0（显式） | Float.NaN（显式） | NaN ToNumber=NaN，ToInt32(NaN)=0 |

> **修复历史**：`GSObject.toFloatValue()` 默认实现原为 `return 0`，导致 `GSFunction`/`GSNativeFunction`/`GSNaN` 的 `toFloatValue()` 错误返回 0 而非 NaN；`GSString`/`GSArray` 未重写 `toIntValue()`/`toFloatValue()`，导致 `"23"|0` 错误返回 0、`[5]-0` 错误返回 0。现已统一修复：`GSObject.toFloatValue()` 改为 `Float.NaN`，`GSString`/`GSArray` 显式重写两个方法走 `toNumber` 转换，`GSNaN` 显式重写返回 `Float.NaN`。验证用例见 [comprehensive_test3.script](file:///e:/JProjects/gscript/src/main/resources/comprehensive_test3.script) 第 24.9 节。

## 字节码

```code

# 入栈一个变量
# 入栈一个整数 值
# 入栈一个浮点数 值
# 入栈字符串 值
# 入栈布尔型数据 值
# ...
# ...,value
const [a|i|f|s|b] value

# 加法运算，将运算结果放到栈顶    a + b
# 减法运算，将运算结果放到栈顶    a - b
# 乘法运算，将运算结果放到栈顶    a * b
# 除法运算，将运算结果放到栈顶    a / b
# 取模运算，将运算结果放到栈顶    a % b
# 负号，将运算结果放到栈顶       -a
# 左移操作符，将运算结果放到栈顶  a << 5
# 右移操作符，将运算结果放到栈顶  a >> 5
# 
# ...,value1, [value2]
# ...,value
arith_op [plus|minus|mul|div|modulo|neg|ls|rs]

# 获取对象属性 a.b,key取expr
# ...,objectref, [expr]
# ...,value
getfield

# 设置对象属性 a.b = 9,key取expr的值
# ...,objectref, [expr], value
# ...,value
putfield

# 复制栈顶一个值
# 这个复制都是复制栈顶值的引用
# 也就是说浅拷贝
# ...,value
# ...,value,value
copy

# 复制栈顶两个值
# 这个复制都是复制栈顶值的引用
# 也就是说浅拷贝
# ...,value1,value2
# ...,value1,value2,value1,value2
copy2

# 比较2个值是否相等，并且返回比较结果  ==
# 比较2个值是否不相等，并且返回比较结果  !=
# 比较2个值是严格否相等，并且返回比较结果  ===
# 比较2个值是否严格不相等，并且返回比较结果  !==
# 比较值value1是否大于value2
# 比较值value1是否大于等于value2
# 比较值value1是否小于value2
# 比较值value1是否小于等于value2
# ...,value1,value2
# ...,value
comp [eq|neq|seq|sneq|gt|ge|lt|le]

# 在当前变量域增加变量，如果变量已存在，忽略
# ...
# ...
declare a

# 将栈顶的值减1后放回栈顶
# ...,value
# ...,value
decr

# 根据当前栈顶的值决定是否跳转，false跳转
# ...,value
# ...
false_jump offset

# 设置函数形参，第一个分量是函数形参名，第二个分量是实参索引(加入变量到当前域，0是隐式this传参)
# ...
# ...
fstore a, 1

# 定义函数，加载函数引用到栈顶
# 第一个参数是函数名称，如果是匿名函数，函数名称为null（这么设计是因为函数的名称就不能为null，匿名函数不把函数名放入作用域）
# 第一个参数是函数长度(不包括fundef本身)
# ...
# ...,functionref
fundef add 10 

# 将栈顶的值加1后放回栈顶
# ...,value
# ...,value
incr

# 函数调用 函数自己从栈上取对应的实参 2表示实参数个数
# objectref, methodref, [arg2, [arg1 ...]]
# value
invoke 2

# 无条件跳转
# ...
# ...
jump offset

# 循环中跳转，执行后销毁最近的loop域
# ...
# ...
loop_jump offset

# 块域中跳转，执行后销毁最近的block域
# ...
# ...
block_jump offset

# 加载null到栈顶
# ...
# ...,null
lda_null

# 加载NaN到栈顶
# ...
# ...,NaN
lda_nan

# 使用默认对象类型进行构造(目前只有2种Object和Array)
# ...,
# ...,objectref
new [Object|Array]

# 使用构造函数创建对象实例（如果栈顶不是一个构造函数，报错，如果构造函数返回了对象，使用该对象）
# ...,methodref, [arg1, [arg2 ...]]
# ...,value
constructor 2

# 从栈顶弹出一个值
# ...,value
# ...
pop

# 销毁作用域
# ...
# ...
popenv [loop|block]

# 创建作用域
# ...
# ...
pushenv [function|loop|block]

# 按位与运算，将运算结果放到栈顶 a & b
# 按位或运算，将运算结果放到栈顶 a | b
# 按位异或运算，将运算结果放到栈顶 a ^ b
# 按位取反运算，将运算结果放到栈顶 ~a
# 逻辑非，对布尔型数据取反 !a
# ...,value1, [value2]
# ...,value
rela_op [b_and|b_or|b_xor|b_not|l_not]

# 从函数调用返回(函数返回时一定要往栈顶放一个值)
# ...
# ...
return

# 出栈当前值，并存储到变量b
# ...,value
# ...
store b

# 交换栈顶的值
# ...,value1,value2
# ...,value2,value1
swap

# 抛出一个异常
# value为异常对象
# ...,value
# ...,
throw

# 开始异常处理
# 参数分别是 try块起始 try块结束 catch块起始 finally块起始
# catch和finally是可选的，但是至少有一个，如果值为-1，代表没有对应的处理块
# ...
# ...
try_start 5 10 -1 12

# 结束当前异常处理 try_end和
# try_end只会在try块结尾，因为catch和finally块的异常不属于当前异常处理
# ...
# ...
try_end

# 处理异常状态
# 结束当前异常处理，如果finally执行完成后没有要抛出的异常，继续执行后续代码，否则向上抛出异常
# ...
# ...
finally_check

```

## 编译文件格式（.gclass）

gscript 源码（.script）编译后序列化为二进制 `.gclass` 文件，类似 Java 的 `.class` 文件，
可用于网络传输、磁盘缓存。解释器直接加载 `.gclass` 执行，无需重新编译。

### 文件总览

```
┌─────────── Header (20 字节) ───────────┐
│ magic(4) | major(1) | minor(1) | flags(2) │
│ cp_count(2) | bytecode_length(4)          │
│ source_path_cp_index(2) | crc32(4)        │
├─────────── 常量池 ──────────────────────┤
│ cp_count 个条目，每条以 tag 开头          │
├─────────── 字节码段 ────────────────────┤
│ bytecode_length 条二进制指令             │
├─────────── Source Map（可选）───────────┤
│ 源码行号映射（原始或 RLE 压缩）          │
├─────────── Attributes（可选）───────────┤
│ 可扩展属性段（如 FunctionTable）         │
└─────────────────────────────────────────┘
```

### Header（20 字节，Big-Endian）

| 偏移 | 长度 | 字段 | 说明 |
|------|------|------|------|
| 0 | 4 | magic | `0x4753434C`（ASCII "GSCL"） |
| 4 | 1 | major_version | 主版本号（当前 1） |
| 5 | 1 | minor_version | 次版本号（当前 1） |
| 6 | 2 | flags | 标志位（见下表） |
| 8 | 2 | cp_count | 常量池条目数（索引从 1 开始，0 不用） |
| 10 | 4 | bytecode_length | 字节码指令条数 |
| 14 | 2 | source_path_cp_index | sourcePath 在常量池的索引（0 = 无） |
| 16 | 4 | crc32 | CRC32 校验值（覆盖偏移 0-15 + 20-EOF） |

### flags 标志位

| bit | 名称 | 说明 |
|-----|------|------|
| 0 | `FLAG_HAS_SOURCE_MAP` | 有 Source Map 段 |
| 1 | `FLAG_HAS_SOURCE_PATH` | 有 sourcePath（CP 索引有效） |
| 2 | `FLAG_RLE_SOURCE_MAP` | Source Map 使用 RLE 压缩编码 |
| 3 | `FLAG_HAS_ATTRIBUTES` | 有 Attributes 段 |

### 常量池

常量池索引从 1 开始（0 不用）。同一文件所有函数共享同一个常量池引用。
`const`、`declare`、`store`、`fundef`、`fstore` 等指令的操作数是常量池索引。

| tag | 类型 | 数据格式 |
|-----|------|----------|
| `0x01` | UTF8 | u2 length + UTF-8 字节 |
| `0x02` | Int | 4 字节整数 |
| `0x03` | Float | 4 字节浮点数 |
| `0x04` | Bool | 1 字节（0=false, 1=true） |

### 字节码段（二进制指令）

每条指令以 1 字节操作码开头，后跟固定长度的操作数。共 37 个操作码（0x00-0x24）。
`const` 文本指令在二进制中拆为 5 个独立操作码，值存入常量池（彻底解决字符串含空格的问题）。

#### 指令长度表

| 长度 | 操作码 | 格式 |
|------|--------|------|
| 1 字节 | `nop` `lda_null` `lda_nan` `copy` `copy2` `swap` `pop` `getfield` `putfield` `return` `throw` `try_end` `finally_check` `incr` `decr` | 无操作数 |
| 2 字节 | `arith_op` `comp` `rela_op` `pushenv` `popenv` `new` | opcode + u1 子操作码 |
| 3 字节 | `const_a/i/f/s/b` `declare` `store` `jump` `false_jump` `loop_jump` `block_jump` `invoke` `constructor` | opcode + u2 操作数 |
| 5 字节 | `fundef` `fstore` | opcode + u2 + u2 |
| 9 字节 | `try_start` | opcode + s2 × 4 |

#### 主操作码表

| 码 | 助记符 | 操作数 | 说明 |
|----|--------|--------|------|
| 0x00 | `nop` | — | 空操作 |
| 0x01 | `const_a` | u2 cp_idx | 入栈变量（变量名存 UTF8） |
| 0x02 | `const_i` | u2 cp_idx | 入栈整数（值存 Int） |
| 0x03 | `const_f` | u2 cp_idx | 入栈浮点数（值存 Float） |
| 0x04 | `const_s` | u2 cp_idx | 入栈字符串（值存 UTF8） |
| 0x05 | `const_b` | u2 cp_idx | 入栈布尔值（值存 Bool） |
| 0x06 | `lda_null` | — | 入栈 null |
| 0x07 | `lda_nan` | — | 入栈 NaN |
| 0x08 | `arith_op` | u1 sub | 算术运算 |
| 0x09 | `comp` | u1 sub | 比较运算 |
| 0x0A | `rela_op` | u1 sub | 位/逻辑运算 |
| 0x0B | `copy` | — | 复制栈顶 |
| 0x0C | `copy2` | — | 复制栈顶两个值 |
| 0x0D | `swap` | — | 交换栈顶两个值 |
| 0x0E | `pop` | — | 弹出栈顶 |
| 0x0F | `getfield` | — | 获取对象属性 |
| 0x10 | `putfield` | — | 设置对象属性 |
| 0x11 | `declare` | u2 cp_idx | 声明变量 |
| 0x12 | `store` | u2 cp_idx | 出栈存入变量 |
| 0x13 | `pushenv` | u1 sub | 创建作用域 |
| 0x14 | `popenv` | u1 sub | 销毁作用域 |
| 0x15 | `jump` | s2 offset | 无条件跳转 |
| 0x16 | `false_jump` | s2 offset | 栈顶为 false 时跳转 |
| 0x17 | `loop_jump` | s2 offset | 循环跳转（销毁 loop 域） |
| 0x18 | `block_jump` | s2 offset | 块跳转（销毁 block 域） |
| 0x19 | `fundef` | u2 name_cp, u2 body_len | 定义函数 |
| 0x1A | `fstore` | u2 name_cp, u2 arg_idx | 设置函数形参 |
| 0x1B | `invoke` | u2 arg_count | 函数调用 |
| 0x1C | `constructor` | u2 arg_count | 构造函数调用 |
| 0x1D | `return` | — | 函数返回 |
| 0x1E | `new` | u1 sub | 创建对象/数组 |
| 0x1F | `throw` | — | 抛出异常 |
| 0x20 | `try_start` | s2 × 4 offsets | 开始异常处理 |
| 0x21 | `try_end` | — | 结束 try 块 |
| 0x22 | `finally_check` | — | finally 块检查 |
| 0x23 | `incr` | — | 栈顶值加 1 |
| 0x24 | `decr` | — | 栈顶值减 1 |

#### 子操作码表

| 指令 | 子码 | 值 |
|------|------|-----|
| `arith_op` | plus/minus/mul/div/modulo/neg/ls/rs | 1-8 |
| `comp` | eq/neq/seq/sneq/gt/ge/lt/le | 1-8 |
| `rela_op` | b_and/b_or/b_xor/b_not/l_not | 1-5 |
| `pushenv` | function/loop/block | 1-3 |
| `popenv` | loop/block | 1-2 |
| `new` | Object/Array | 1-2 |

### Source Map（可选）

记录每条字节码指令对应的源码行号（1-based，0 = 未设置），与字节码段平行。
调试器据此把任意帧的 IP 映射回源码行：`sourceLines[baseOffset + ip - 1]`。

**原始格式**（`FLAG_RLE_SOURCE_MAP` 未置位）：
```
u4 lineCount
lineCount × u2 line
```

**RLE 压缩格式**（`FLAG_RLE_SOURCE_MAP` 置位）：
源码行号常有长游程（多条字节码对应同一源码行），RLE 显著减小体积。
仅压缩率 > 25% 时启用，否则用原始格式（向后兼容）。
```
u4 pairCount
pairCount × (u2 count, u2 line)   // count = 连续相同行号数
```

### Attributes 段（可选）

可扩展属性机制，位于 Source Map 之后。未知属性跳过（前向兼容）。
```
u2 attrCount
attrCount × attribute {
    u2 name_cp_index      // 属性名 UTF8（CP 索引）
    u4 length             // 属性数据字节数
    byte[length] data     // 属性数据
}
```

#### FunctionTable 属性

记录文件中所有顶级函数的元数据（名称/起始IP/体长），供调试器和工具链使用。
由 Writer 扫描 `fundef` 指令自动生成（`startIp = fundef 指令索引 + 1`）。

```
u2 funcCount
funcCount × {
    u2 name_cp_index   // 函数名 UTF8（CP 索引）
    u4 start_ip         // 函数体起始指令索引
    u4 body_len         // 函数体指令数
}
```

### CRC32 校验

CRC32 覆盖 Header 偏移 0-15 + 20-EOF（不含偏移 16-19 的 CRC32 字段自身）。
加载时重新计算并比对，篡改文件会被拒绝加载。

## 构建要求

本项目是 **纯 Java 1.4** 实现，零外部依赖。

### 环境要求

- **JDK 1.8**（用于编译，是最后一个支持 `-source 1.4 -target 1.4` 的版本）
- **Maven 3.x**（仅用于构建，无任何外部依赖）
- 产出的 class 文件 major version=48（真 1.4 字节码，可在真实 JDK 1.4 运行）

> **JDK 9+ 不能用于编译**：JDK 9 最低支持 `-source 1.6`，无法生成 1.4 字节码。JDK 1.8 还能捕捉 JDK 9 + `-source 1.6` 无法发现的自动装箱缺陷和隐藏的 1.5 API 调用（如 `String.replace(CharSequence)`、`URL.toURI()`）。

### 关键设计

| 项 | 原实现（Java 9+） | 现实现（Java 1.4） |
|--------|-------------------|---------------------|
| 语法特性 | 泛型、枚举、注解、增强 for、自动装箱、try-with-resources | 原始类型 + 显式 cast + Iterator + `new Integer()` |
| 字符串构建 | `StringBuilder` | `StringBuffer` |
| 格式化 | `String.format()` | 字符串拼接 + 自定义 `padLeft`/`padRight` |
| switch on String | Java 7+ 原生支持 | 转换为 `if-else` + `.equals()` 链 |
| Token 类型 | `enum GSTokenType` | `public static final int` 常量 |
| JSON 处理 | Gson 2.10.1（外部依赖） | 自研 `org.gscript.vm.debug.dap.json` 库（API 对齐 Gson） |
| 并发原语 | `java.util.concurrent`（AtomicInteger/LinkedBlockingQueue/PriorityQueue） | 自研 `org.gscript.util`（AtomicCounter/SimpleBlockingQueue/MinPriorityQueue） |
| 打包 | maven-shade-plugin（fat jar 含 Gson） | maven-jar-plugin（纯项目 jar，无依赖） |

### 构建命令

```powershell
# 必须用 JDK 1.8 编译（JDK 9+ 不支持 -source 1.4）
$env:JAVA_HOME="C:\Program Files\Java\jdk1.8.0_202"
mvn clean package -DskipTests
```

生成 `target/gscript-1.0-SNAPSHOT.jar`（约 190KB，纯项目 jar，无 shade 无 fat jar），可直接 `java -jar` 运行调试适配器。

## 快速开始

### 1. 基础语法示例

gscript 是类 JS 语法的脚本语言，支持变量、函数、控制流、对象/数组、异常、定时器等。以下是一个覆盖主要语法的示例（保存为 `src/main/resources/hello.script`）：

```javascript
// 变量与算术
var name = "gscript";
var x = 10, y = 20;
console.log("Hello, " + name + "!");          // Hello, gscript!
console.log("sum = " + (x + y));               // sum = 30

// 函数与递归
function fib(n) {
    if (n < 2) { return n; }
    return fib(n - 1) + fib(n - 2);
}
console.log("fib(10) = " + fib(10));           // fib(10) = 55

// 控制流：if-else / for / while / switch
for (var i = 0; i < 3; i = i + 1) {
    console.log("i=" + i);
}
switch (x) {
    case 10: { console.log("ten"); }
    case 20: { console.log("twenty"); }
    default: { console.log("other"); }
}

// 对象与数组
var point = { x: 1, y: 2 };
var list = [1, 2, 3];
console.log(point.x + "," + point.y);          // 1,2
console.log(list[0] + list[2]);                // 4

// 异常处理
try {
    throw "boom";
} catch (e) {
    console.log("caught: " + e);               // caught: boom
} finally {
    console.log("finally");
}

// 定时器（构造器默认初始化，无需注册）
var counter = 0;
var id = setInterval(function() {
    counter = counter + 1;
    console.log("tick " + counter);
    if (counter >= 3) { clearInterval(id); }
}, 100);
```

### 2. 执行脚本

```bash
# 编译源码 + 立即执行（默认 run 模式）
java -cp target/classes org.gscript.TestScript hello run
```

`<name>` 对应 `src/main/resources/<name>.script`（去掉扩展名）。更多命令行模式见下文 [TestScript 命令行模式](#testscript-命令行模式)。

### 3. Java 宿主嵌入

```java
GSInterpreter interpreter = new GSInterpreter();  // 构造器默认初始化定时器
interpreter.addVariableToGlobal("console", new Console());

interpreter.evalScript("var x = 10; function add(a, b) { return a + b; }");

GSValue sum = interpreter.evalExpression("add(x, 20)");
System.out.println(sum.toIntValue());              // 30

interpreter.setVariable("config", myJavaMap);       // 注入 Java 对象
GSValue v = interpreter.evalExpression("config.timeout");
```

完整 API 与类型映射见下文 [宿主交互 API](#宿主交互-api)。

## TestScript 命令行模式

```
java -cp target/classes org.gscript.TestScript <name> [mode]
```

`<name>` 对应 `src/main/resources/<name>.script`（去掉扩展名）。`<name>.gclass` 文件位于 `target/classes/gtxt/<name>.gclass`。

| 模式 | 说明 |
|------|------|
| `run`（默认） | 编译源码 + 立即执行（含 runEventLoop，awaitIdle 等待定时器排空） |
| `dump` | 编译源码 + 打印字节码索引↔源码行映射（调试器源码映射验证用） |
| `compile` | 编译源码 + 写出 `.gclass` 二进制文件（含 CRC32/SourceMap/FunctionTable） |
| `rungclass` | 加载 `.gclass` + 反序列化 + 执行（无需重新编译） |
| `dumpgclass` | 加载 `.gclass` + 打印字节码映射 + FunctionTable（验证反序列化正确性） |
| `hosttest` | 宿主交互 API 演示（Java 调用 gscript 解释器：evalScript/evalExpression/getVariable/setVariable） |
| `debugagent` | DebugAgent launch 模式（`waitForDebuggerAndRun`，阻塞等 VSCode 连接后由 agent 后台线程执行 gclass） |
| `debugagent-attach` | DebugAgent 运行时 attach 模式（`startAttachListener`，解释器先运行，VSCode 随后附加） |
| `debugagent-waitattach` | DebugAgent 主线程驱动 attach 模式（`waitForDebuggerAndAttach`，主线程阻塞等连接后继续执行） |
| `debugagent-eval` | enableDebugMode + startAttachListener + 多次 eval 演示（交互式调试，验证每次 eval 断第一行） |
| `batch` | 遍历 `src/main/resources` 根目录所有 `.script`，批量编译为 `.gtxt`（不执行） |

### 调试模式测试入口

`debugagent` / `debugagent-attach` / `debugagent-waitattach` / `debugagent-eval` 四个模式用于调试器测试，需先用 `compile` 生成 `.gclass`：

```bash
# 1. 编译生成 gclass（含 sourceContent，供 VSCode source 请求）
java -cp target/classes org.gscript.TestScript timer_debug compile

# 2. 启动调试代理（任选一种模式）
java -cp target/classes org.gscript.TestScript timer_debug debugagent-waitattach
```

然后在 VSCode 中按 F5 附加（attach 配置，端口 4711）。详见下文 [调试模式](#调试模式)。

## 宿主交互 API

Java 宿主通过 `GSInterpreter` 直接执行 gscript 代码、读写变量，实现 Java ↔ gscript 双向交互。
gscript → Java 方向已通过 `GSNativeFunction`（如 `Console`）实现；以下为 Java → gscript 方向的 API。

### GSInterpreter 方法

#### 执行入口

| 方法 | 说明 |
|------|------|
| `evalScript(String code)` | 在当前全局上下文执行一段 gscript 源码（语句序列），共享 global 环境（REPL 风格，无源码映射） |
| `evalExpression(String expr)` | 求值一个 gscript 表达式并返回结果（包装为 `return (expr);` 执行）；出错返回 `GSNull.NULL` |
| `evalScriptFile(String filePath)` | 从文件系统读 `.script` 编译并执行（**带源码映射**，供调试器使用） |
| `evalScriptStream(InputStream in, String sourcePath)` | 从输入流读源码编译并执行（**带源码映射**）；流不关闭，调用方负责 |
| `evalGclassFile(String filePath)` | 从 `.gclass` 二进制文件反序列化并执行 |
| `evalGclassStream(InputStream in)` | 从输入流读 `.gclass` 反序列化并执行；流不关闭 |
| `eval(byte[][] codes, Object[] cp, int[] sourceLines, String sourcePath)` | 执行二进制字节码（4 参数版，sourceContent=null） |
| `eval(byte[][] codes, Object[] cp, int[] sourceLines, String sourcePath, String sourceContent)` | 执行二进制字节码（5 参版，attach 调试模式用，sourceContent 随函数继承供 DAP source 请求） |
| `eval(String[] src, int[] sourceLines, String sourcePath)` | 执行文本字节码（内部用 `BytecodeEncoder` 编码为二进制后委托 4 参版） |
| `eval(String[] src)` | 执行文本字节码（无源码映射，非调试入口） |

#### 预编译（只编译不执行）

| 方法 | 说明 |
|------|------|
| `static compileScriptStream(InputStream in, String sourcePath)` | 从流编译为 `GSClassData`（含 sourceLines/sourceContent），供 `DebugAgent.addGclass()` 预注册 |
| `static compileScriptContent(String code, String sourcePath)` | 从源码字符串编译为 `GSClassData`（含 sourceLines/sourceContent） |

> `compileScriptStream` / `compileScriptContent` 是 `static` 方法，不需要解释器实例，纯粹走编译流水线（Lexer → Parser → ByteCodeGenerator → BytecodeEncoder），返回的 `GSClassData` 含完整 sourceLines + sourceContent + sourcePath，可直接传给 `DebugAgent.addGclass()` 或 `eval(byte[][], Object[], int[], String, String)`。是**多文件 attach 调试**的关键入口。

#### 变量读写

| 方法 | 说明 |
|------|------|
| `getVariable(String name)` | 从全局作用域获取变量；未定义返回 `GSNull.NULL` |
| `setVariable(String name, Object value)` | 设置全局变量，自动包装 Java 对象为 GSValue（支持 Integer/Float/Double/String/Boolean/Map/List/null/GSValue） |
| `addVariableToGlobal(String name, GSValue value)` | 直接添加 GSValue 到全局域（不自动包装） |
| `callFunction(GSFunction fn, ArrayList args)` | 调用 gscript 函数（native 回调 gscript 的唯一入口，定时器回调用） |

#### 调试模式 API

| 方法 | 说明 |
|------|------|
| `enableDebugMode(DebugAgent agent)` | **主入口**：一步完成 `agent.setInterpreter(this)`（内部回调 `setDebugAgent`）+ `setDebugMode(true)`。替代旧的 `setInterpreter` + `setPauseOnAttach` |
| `setDebugMode(boolean mode)` | 启用/关闭调试模式。启用后所有顶层 eval 入口会自动注册 source 并请求 entry stop（若 controller 已就位） |
| `isDebugMode()` | 查询调试模式是否启用 |
| `setDebugController(DebugController dc)` | 设置调试控制器（由 DapServer 创建并共享，宿主一般不直接调用） |
| `getDebugController()` | 获取调试控制器，非调试模式返回 null |
| `setDebugAgent(DebugAgent agent)` | 设置调试代理（通常由 `DebugAgent.setInterpreter` 回调调用） |
| `getDebugAgent()` | 查询调试代理 |
| `debugLaunch(GSClassData data, int port)` | launch 调试便捷封装：`enableDebugMode` + `addGclass` + `waitForDebuggerAndRun`（阻塞到 disconnect） |
| `debugAttach(GSClassData data, int port)` | attach 调试便捷封装：`enableDebugMode` + `addGclass` + `startAttachListener` + `eval` + `runEventLoop` |
| `getCallStackSnapshot()` | 获取调用栈的线程安全快照（synchronized，调试器挂起期间 DAP 线程读取用） |

#### 定时器 API

| 方法 | 说明 |
|------|------|
| `scheduleTimeout(GSFunction cb, long delay, ArrayList args)` | 调度一次性定时器（setTimeout），返回 timer id |
| `scheduleInterval(GSFunction cb, long period, ArrayList args)` | 调度周期性定时器（setInterval），返回 timer id |
| `cancelTimer(int id)` | 取消定时器（clearTimeout/clearInterval 共用） |
| `runEventLoop()` | awaitIdle 语义：等待 worker 排空 taskQueue + 无 pending 定时器（10ms 轮询，无定时器任务时立即返回） |
| `shutdown()` | 停止 worker 线程 + 关闭 TimerScheduler + 排空 taskQueue（幂等，长运行宿主释放资源时调用；CLI 场景无需调用） |
| `installTimerGlobals()` | 注册 setTimeout/setInterval/clearTimeout/clearInterval 到 global 域（构造器已默认调用，宿主一般无需显式调用） |

### GSValue 类型转换方法

| 方法 | 说明 |
|------|------|
| `Object toJavaObject()` | 将 GSValue 转为 Java 原生对象（递归转换对象/数组） |
| `static GSValue fromJavaObject(Object)` | 将 Java 对象转为 GSValue（支持 Integer/Float/Double/String/Boolean/Map/List/null） |

### 类型映射

| gscript (GSValue.type) | Java 类型 |
|------------------------|-----------|
| bool (1) | `Boolean` |
| int (2) | `Integer` |
| float (3) | `Float` |
| object (4) | `LinkedHashMap<String, Object>` |
| str (5) | `String` |
| array (7) | `ArrayList<Object>` |
| null (8) | `null` |
| nan (10) | `Float.NaN` |
| function (6) / native (9) | 原样返回 GSValue 引用 |

### 使用示例

```java
GSInterpreter interpreter = new GSInterpreter();  // 构造器默认初始化定时器
interpreter.addVariableToGlobal("console", new Console());

// 1. 执行一段 gscript 代码
interpreter.evalScript("var x = 10; var y = 20; function add(a, b) { return a + b; }");

// 2. 获取变量
GSValue x = interpreter.getVariable("x");
System.out.println(x.toIntValue());  // 10

// 3. 执行表达式获取结果
GSValue sum = interpreter.evalExpression("add(x, y)");
System.out.println(sum.toIntValue());  // 30

// 4. 设置变量（自动包装 Java 值为 GSValue）
interpreter.setVariable("z", new Integer(100));
Map config = new LinkedHashMap();
config.put("timeout", new Integer(5000));
config.put("retries", new Integer(3));
interpreter.setVariable("config", config);
interpreter.evalScript("console.log(z); console.log(config.timeout);");

// 5. GSValue → Java 对象（递归转换）
GSValue obj = interpreter.evalExpression("{name: \"Alice\", age: 30, scores: [90, 85, 95]}");
Map javaObj = (Map) obj.toJavaObject();
// javaObj = {name="Alice", age=30, scores=[90, 85, 95]}

// 6. Java 对象 → GSValue（注入 gscript）
List tags = new ArrayList(Arrays.asList(new Integer[]{new Integer(1), new Integer(2), new Integer(3)}));
interpreter.setVariable("tags", tags);
GSValue first = interpreter.evalExpression("tags[0]");
System.out.println(first.toIntValue());  // 1
```

### 实现要点

- `evalScript` / `evalExpression` 共享 `interpreter.global` 环境——多次调用的变量/函数定义累积在 global 中
- `evalExpression` 先 `stack.clear()` 清空残留值，确保返回值是本次表达式的结果；表达式出错时捕获异常并返回 `GSNull.NULL`
- top-level `return` 合法（EBNF: `Program = { Statement }`，`ReturnStatement` 属于 `Statement`），`OP_RETURN` 将返回值留在共享栈上供 `evalExpression` 弹出
- `getVariable` 基于 `global.getVariableValue(name)`——top-level `var` 声明直接落入 global（`ProgramNode` 不 emit `pushenv`）

## 定时器机制（EventLoop）

gscript 内置 JS 风格的 `setTimeout`/`setInterval`/`clearTimeout`/`clearInterval`，采用 **worker 线程独占执行 + 统一任务队列** 语义（V8 Isolate 模型）：守护线程 `gscript-timer` 只负责计时（不执行字节码），到期任务经 `TaskDispatcher` 投递到 worker 线程的统一任务队列；外部线程（主线程/业务线程）的 `eval`/`callFunction` 调用也包装为 `EvalTask` 提交到同一队列，由 worker 串行执行，调用方阻塞等结果（线性化语义）。回调内设置的断点/单步天然工作。

### 三层设计（核心机制默认就绪）

定时器机制采用三层解耦设计，**核心机制在解释器构造时就绪**，不依赖入口函数注册：

| 层次 | 说明 | API |
|------|------|-----|
| ① 核心机制 | `TimerScheduler`（计时守护线程 + `TaskDispatcher` 投递）+ `gscript-worker`（worker 线程 + taskQueue），构造时默认就绪 | `ensureTimerScheduler()` + `startWorker()`（构造器调用） |
| ② 入口函数 | `setTimeout`/`setInterval`/`clearTimeout`/`clearInterval` 注册到 global 域，gscript 脚本用 | `installTimerGlobals()`（构造器调用） |
| ③ 宿主直调 API | `scheduleTimeout`/`scheduleInterval`/`cancelTimer`/`shutdown`，公开方法供宿主直接调度 | `GSInterpreter.scheduleTimeout` 等 |

**解耦关系**：`ensureTimerScheduler()` 在 `installTimerGlobals()` 之前调用，核心机制不依赖入口函数注册。`TimerScheduler` 通过 `TaskDispatcher` 接口与 `GSInterpreter` 解耦，不直接持有解释器引用——到期任务经 `dispatch(task)` 投递到 worker 的 taskQueue。即使宿主从 global 移除 `setTimeout`，`TimerScheduler` 仍存在，宿主可通过 `scheduleTimeout` 直调。

```java
public GSInterpreter() {
    ensureTimerScheduler();      // 构造时就绪 TimerScheduler（this 作为 TaskDispatcher）
    installTimerGlobals();       // 默认注册 setTimeout/setInterval 等入口函数到 global 域
    installTypeGlobals();        // 默认注册 parseInt/parseFloat/isNaN/String/Number/Boolean
    startWorker();               // 启动 worker 线程（独占执行权，常驻守护）
}
```

> **无需显式调用 `installTimerGlobals()`**：构造器已默认调用。所有 `new GSInterpreter()` 后定时器机制 + worker 线程立即可用。若宿主想自定义入口函数（如重命名），可从 global 移除默认函数后用 `scheduleTimeout` 自行封装。

### 线程模型（worker 独占执行权）

解释器内部维护一个常驻 worker 线程 `gscript-worker`（守护线程），**独占解释器执行权**——所有字节码执行（`stack`/`callStack` 操作）都在 worker 线程内完成，保证单线程串行，与调试器（`THREAD_ID=1`）兼容。

| 线程 | 职责 | 跑字节码? |
|------|------|----------|
| `gscript-worker`（常驻守护） | 独占解释器执行权：消费 taskQueue，执行所有 eval/callFunction/定时器回调 | ✅ 全部 |
| `gscript-timer`（守护） | 仅计时，到期后通过 `TaskDispatcher.dispatch(task)` 投递到 taskQueue | ❌ |
| 外部业务线程（主线程等） | 调用 eval/callFunction → 包装 EvalTask 提交 → 阻塞 await 等结果 | ❌ |

**双路径（避免递归死锁）**：所有 eval 入口（`eval`/`evalScript`/`evalExpression`）和 `callFunction` 都用 `Thread.currentThread() == workerThread` 判断：

- **worker 自己调用**（定时器回调内 callFunction、eval 内部 OP_INVOKE 等）→ 直接执行 `xxxDirect`，无队列开销
- **外部线程调用** → 包装 `EvalTask` 提交到 taskQueue + `submitAndAwait` 阻塞等结果

**EvalTask 任务体系**：

| 任务类型 | 包装目标 | 用途 |
|----------|----------|------|
| `RunnableEvalTask` | void 任务（`TaskRunnable`） | `eval`/`evalScript` 全家桶 |
| `CallableEvalTask` | 返回 GSValue 任务（`TaskCallable`） | `callFunction`/`evalExpression` |
| `TimerEvalTask` | 定时器回调（`TimerScheduler.TimerTask`） | 定时器到期回调，`run()` 调 `callFunctionDirect` |

worker 主循环等待策略：无定时器时 `taskQueue.take()` 无限阻塞等外部任务；有定时器时 `taskQueue.poll(delay)` 等 `delay` 毫秒，超时表示定时器到点（`TimerScheduler` 已 dispatch 到 taskQueue），下一轮立即取到。

> **调用点零改动**：所有 `runEventLoop()` 调用点（TestScript、DebugAgent launch、DapServer launch）语义从「主线程 pump readyQueue」升级为「awaitIdle 等待 worker 排空」，无需修改。`eval`/`callFunction` 自动经双路径路由到 worker。

### 全局函数（gscript 脚本用）

| 函数 | 说明 | 返回值 |
|------|------|--------|
| `setTimeout(callback, delayMs, ...args)` | 延迟 `delayMs` 毫秒后执行一次 `callback` | timer id（整数） |
| `setInterval(callback, periodMs, ...args)` | 每隔 `periodMs` 毫秒重复执行 `callback` | timer id（整数） |
| `clearTimeout(id)` | 取消尚未触发的 setTimeout | `null` |
| `clearInterval(id)` | 取消 setInterval | `null` |

- `callback` 必须是 function，否则打印 TypeError 并返回 `null`
- `delayMs` / `periodMs` 为整数毫秒；负数延迟当 0 处理，`periodMs < 1` 当 1 处理（避免忙等）
- `...args` 透传给回调（回调内从第 1 个参数起取，`this` 为 `null`）

### 事件循环（awaitIdle 语义）

`runEventLoop()` 语义已升级为 **awaitIdle**：外部线程等待 worker 排空 taskQueue + 无 pending 定时器。不再直接 pump 队列——定时器回调由 worker 后台执行，本方法仅用于宿主需同步等待回调完成的场景。

- **退出条件**（10ms 轮询）：`!workerBusy && taskQueue.isEmpty() && !timerScheduler.hasPending()`，三者同时满足才返回
- **worker 自己调用**：直接返回（死锁保护——worker 内部不能 await 自己排空）
- **纯 setTimeout 脚本**：所有回调执行完后 taskQueue 排空 + hasPending=false → 返回，进程正常终止
- **纯 setInterval 脚本**：hasPending 永远 true → 永不返回（需 `clearInterval` 或 DAP `terminate` 触发 `DebugAbortException` → worker 停止 → `workerStopped=true` → 本方法返回）
- **异常策略（类 JS）**：回调内未捕获异常（`GSException`）打印到 stderr 后 worker 继续下一个任务；`DebugAbortException`（调试终止请求）使 worker 停止 + 排空 taskQueue
- **workerStopped**：shutdown 或定时器回调抛 `DebugAbortException` 后置 true，`runEventLoop` 直接返回

> **何时调用 `runEventLoop`**：仅在脚本使用了定时器且需同步等待回调完成时调用（如 CLI 工具、launch 模式发 terminated 前）。宿主自行管理线程的场景（如 `debugagent-eval` 模式）可不调用——eval 提交给 worker 后立即返回，定时器回调由 worker 后台执行，主线程不被阻塞。

### 调试器交互

- 回调在 worker 线程执行（`callFunctionDirect` 复用 `callStack.push/pop` + `suspendCheck`），断点/单步/变量查看与普通函数调用一致
- 调试器挂起期间 worker 阻塞在 `suspendCheck`，DAP 线程通过 `getCallStackSnapshot()`（synchronized）安全读取调用栈。`THREAD_ID=1` 为逻辑 ID，worker 是唯一执行线程
- `terminated` 事件时机延后到事件循环返回后（即所有定时器排空才发 terminated）
- **attach 模式 disconnect**：仅分离调试器（如同 `node --inspect`），setInterval 程序继续运行；需发送 `terminate` 请求或 kill 进程终止
- **disconnect 后重新 attach**：`startAttachListener` 的 accept 线程以 `while(true)` 循环 accept，disconnect 后 `dapServer.run()` 返回，线程回到 accept 等待新连接。VSCode 可重新 attach（每次创建新 DapServer 实例，状态自然重置），controller 重新注入 + `pauseOnAttach` 触发挂起。适用于"天劫辅助软件"等需反复调试同一运行中进程的场景

### 示例

```javascript
// setTimeout：延迟后执行一次
setTimeout(function() {
    console.log("hello after 500ms");
}, 500);

// setInterval：周期执行 + clearInterval 终止
var counter = 0;
var id = setInterval(function() {
    counter = counter + 1;
    console.log("tick " + counter);
    if (counter >= 3) {
        clearInterval(id);
    }
}, 100);

// 透传参数
setTimeout(function(a, b) {
    console.log("sum=" + (a + b));
}, 0, 10, 20);  // 输出 sum=30
```

## 调试模式

gscript 调试器支持 VSCode 插件调试，采用 DAP（Debug Adapter Protocol）协议通过 TCP socket 通信。

### enableDebugMode 主入口

`GSInterpreter.enableDebugMode(DebugAgent agent)` 是所有调试场景的统一入口，一步完成：
1. `agent.setInterpreter(this)`（内部回调 `setDebugAgent`，建立双向引用）
2. `setDebugMode(true)`（启用调试模式）

```java
GSInterpreter interp = new GSInterpreter();  // 构造器默认初始化定时器
interp.addVariableToGlobal("console", new Console());

DebugAgent agent = new DebugAgent(4711);
interp.enableDebugMode(agent);  // 主入口：setInterpreter + setDebugMode(true)
```

### debugMode 与 debugController 解耦

- `debugMode=true` 表示"愿意被调试"，所有顶层 eval 入口会调 `prepareDebugEntry` 注册 source + 请求 entry stop
- `debugController=null` 表示"VSCode 未连接"，`prepareDebugEntry` 跳过 `requestEntryStop`，eval 正常执行（JS "DevTools 未连接" 语义，不阻塞）

两者独立：`debugMode=true` 但 `controller=null` 时 eval 正常执行。VSCode 连接后由 DapServer 创建 controller 并通过 `agent.setController()` 共享（volatile 字段），下次 `suspendCheck` 时按需挂起。

### entryStopRequested 机制

`DebugController.entryStopRequested` 是独立的一次性标志（volatile，与 `stopOnEntry`/`pauseRequested` 正交）：

- `requestEntryStop()` 设置标志 + 清除 `pauseRequested`
- `suspendCheck` 检查顺序：`stopOnEntry` → **`entryStopRequested`** → `pauseRequested`
- 触发后 stopped 事件的 `reason="entry"`

`prepareDebugEntry` 在所有顶层 eval 入口（5-arg eval / evalScript / evalExpression / evalScriptFile）调用，确保首次 `suspendCheck` 命中 entry stop。

### pause on exceptions 机制

`DebugController.checkException(frame, depth, exception)` 在解释器 `catch(GSException)` 块中调用，实现异常断点挂起：

- `DapServer.handleSetExceptionBreakpoints` 据 DAP `filters`（如 `["uncaught"]`）设置 `debugController.pauseOnException`
- 异常抛出时 `checkException` 检查：若 `pauseOnException` 启用且异常未被 catch，挂起 worker（reason=`exception`），stackTrace 从 `interpreter.getCallStackSnapshot()` 读取
- **「只挂起一次」语义**：`GSException.paused` 标志（运行时字段，不参与 gclass 序列化）。`checkException` 首次挂起时置 `paused=true`，后续跨帧传播路径上的 `checkException` 调用见此标志即跳过——避免同一异常在每帧 catch 块反复触发 `stopped(exception)`，用户只需 continue 一次

> **时序注意**：`debugagent-eval` 模式（`startAttachListener` 非阻塞 + 主线程立即 eval）下，若 VSCode 未及时连接，`debugController` 为 null 时异常已抛出，`checkException` 不被调用。**测试 pause on exceptions 必须用 `debugagent-waitattach` 模式**（阻塞等 configurationDone，确保 controller 就位后再 eval）。详见 `test_pause_exception.py`。

### eval sourcePath 生成

debug 模式下，`evalScript` / `evalExpression` 用 `generateEvalPath()` 生成唯一 sourcePath（`"eval-" + counter + ".script"`，AtomicCounter 自增），VSCode 可通过 source 请求获取源码内容。`evalExpression` 的源码形式为 `"return (1 + 2);"`。

### DebugAgent 三种调试模式

宿主程序接入 VSCode 调试有三种模式，按"谁驱动脚本执行"选择：

| 模式 | 方法 | 阻塞调用线程 | 谁执行脚本 | 适用场景 |
|------|------|-------------|-----------|---------|
| launch | `waitForDebuggerAndRun()` | 是（到 disconnect） | agent 后台 `gscript-interpreter` 线程 | agent 负责执行脚本，宿主只注册 gclass |
| 运行时 attach | `startAttachListener()` | 否（立即返回） | 宿主主线程（已运行） | 程序已运行，VSCode 随后附加 |
| **主线程驱动 attach** | `waitForDebuggerAndAttach()` | 是（到 configurationDone） | 宿主主线程（连接后开始） | **主线程是脚本驱动者，需先连接调试器再执行** |

三种模式都用 `"request": "attach"` launch.json 配置（`localRoot`/`remoteRoot` 路径映射），区别仅在宿主调用哪个 API。

#### 模式 1：launch（waitForDebuggerAndRun）

agent 后台线程执行 gclass，调用线程阻塞到 VSCode disconnect。

```java
GSClassData data = GSInterpreter.compileScriptStream(in, "myscript.script");

GSInterpreter interp = new GSInterpreter();
interp.addVariableToGlobal("console", new Console());

DebugAgent agent = new DebugAgent(4711);
interp.enableDebugMode(agent);
agent.addGclass(data);
agent.waitForDebuggerAndRun();  // 阻塞到 disconnect，脚本在子线程执行
```

#### 模式 2：运行时 attach（startAttachListener）

立即返回，解释器在主线程全速运行，VSCode 随后附加。

```java
DebugAgent agent = new DebugAgent(4711);
interp.enableDebugMode(agent);
agent.addGclass(data);
agent.startAttachListener();  // 后台监听，立即返回

// 主线程执行脚本（VSCode 连接后 controller 注入，下次 suspendCheck 挂起）
interp.eval(data.src, data.constantPool, data.sourceLines,
        data.sourcePath, data.sourceContent);
interp.runEventLoop();
agent.notifyScriptCompleted();  // 通知 DapServer 发 terminated 事件
agent.stop();
```

#### 模式 3：主线程驱动 attach（waitForDebuggerAndAttach）

**专为宿主 `static` 块加载脚本场景设计**：主线程阻塞等 VSCode 连接，连接后 controller 已注入并请求 entry stop，方法返回让主线程继续执行——首次 `eval` 即挂起。

```java
static {
    GSInterpreter interp = new GSInterpreter();
    interp.addVariableToGlobal("console", new Console());

    DebugAgent agent = new DebugAgent(4711);
    interp.enableDebugMode(agent);

    // 预编译 + 注册（sourceContent 供 VSCode source 请求）
    String[] scripts = {"vars.script", "util.script", "battle.script"};
    List datas = new ArrayList();
    for (int i = 0; i < scripts.length; i++) {
        InputStream is = MyApp.class.getResourceAsStream("/scripts/" + scripts[i]);
        GSClassData data = GSInterpreter.compileScriptStream(is, scripts[i]);
        agent.addGclass(data);
        datas.add(data);
    }

    // 阻塞等 VSCode 连接，连接后返回（controller 已注入 + entryStopRequested）
    agent.waitForDebuggerAndAttach();

    // 主线程依次执行脚本（首次 eval 命中 entryStopRequested → 挂起，reason=entry）
    for (int i = 0; i < datas.size(); i++) {
        GSClassData d = (GSClassData) datas.get(i);
        interp.eval(d.src, d.constantPool, d.sourceLines, d.sourcePath, d.sourceContent);
    }
    interp.runEventLoop();
    agent.notifyScriptCompleted();  // 通知 DapServer 发 terminated 事件
    agent.stop();
}
```

**与模式 2 的区别**：模式 2 立即返回，连接前脚本已开始执行，无法调试初始加载；模式 3 阻塞到连接后才放行，保证初始加载即可调试。

### notifyScriptCompleted（重要）

`startAttachListener` / `waitForDebuggerAndAttach` 模式下，脚本执行完毕后 DapServer **不会自动**发 terminated 事件（仅 launch 模式才会）。宿主必须在 eval 结束后调用 `agent.notifyScriptCompleted()` 让 DapServer 发 terminated，否则 VSCode 永远等不到会话结束。`agent.stop()` 只关闭 server socket + terminate controller，不发 terminated。

### 多文件 attach 调试

多文件调试用 `compileScriptStream` / `compileScriptContent` **预编译**为 `GSClassData`（不执行），再注册到 `DebugAgent.addGclass()` 或自行 `eval`：

```java
GSInterpreter interp = new GSInterpreter();
interp.addVariableToGlobal("console", new Console());

DebugAgent agent = new DebugAgent(4711);
interp.enableDebugMode(agent);

// 多个 .script 流（sourcePath 必须唯一，作为 DAP source 标识）
String[] paths = {"a.script", "b.script", "c.script"};
InputStream[] streams = {...};
List datas = new ArrayList();
for (int i = 0; i < streams.length; i++) {
    GSClassData data = GSInterpreter.compileScriptStream(streams[i], paths[i]);
    agent.addGclass(data);          // 注册供 VSCode source 请求返回源码
    datas.add(data);
}

agent.startAttachListener();        // 后台监听，立即返回
try {
    // 主线程依次 eval（VSCode 连上后 controller 注入，下次 suspendCheck 挂起）
    for (int i = 0; i < datas.size(); i++) {
        GSClassData d = (GSClassData) datas.get(i);
        interp.eval(d.src, d.constantPool, d.sourceLines, d.sourcePath, d.sourceContent);
    }
    interp.runEventLoop();          // 定时器回调
    agent.notifyScriptCompleted();
} finally {
    agent.stop();
}
```

**多文件语义**：所有文件共享同一 `interpreter.global` 环境，后加载文件定义的同名函数覆盖前文件（global 域变量被覆盖赋值），与 DapServer launch 模式的多文件行为一致。每个文件 sourcePath 必须唯一（DAP source 请求用它作 key）。

## VSCode 调试

gscript 提供 VSCode 扩展（`gscript-debug` v0.2.1），支持 **Launch**（stdio，本地启动）和 **Attach**（socket，附加到已运行进程）两种调试模式，覆盖断点、单步、调用栈、变量查看、表达式求值等完整调试能力。

### 前置准备

1. **构建调试适配器 jar**

   ```powershell
   $env:JAVA_HOME="C:\Program Files\Java\jdk1.8.0_202"
   mvn clean package -DskipTests
   ```

   生成 `target/gscript-1.0-SNAPSHOT.jar`（纯项目 jar，无外部依赖）。

2. **安装 VSCode 扩展**

   ```powershell
   # 用项目内置脚本打包并安装
   powershell -ExecutionPolicy Bypass -File tests\build_vsix.ps1
   code --install-extension vscode-extension\gscript-debug-0.2.1.vsix --force
   ```

   > **VSCode 插件 schema 重要教训**：VSCode 对 launch.json 属性校验基于**已安装扩展**的 package.json schema，而非工作区源码。即使源码 package.json 已定义某属性，若已安装 VSIX 是旧版，VSCode 仍按旧 schema 报错。修复必须：①改源码 package.json schema；②bump 版本；③重新打包 VSIX；④`code --install-extension --force` 安装。

3. **配置 jar 路径**

   在 VSCode `settings.json` 中配置：

   ```json
   {
     "gscript.jarPath": "e:/JProjects/gscript/target/gscript-1.0-SNAPSHOT.jar",
     "gscript.javaPath": "java"
   }
   ```

   或在单条 `launch.json` 配置中用 `jarPath` 字段覆盖。

### Launch 模式（本地调试，最常用）

VSCode 启动调试适配器子进程（`java -jar <jarPath> --stdio`），通过 stdio 通信。源码在本地，断点路径直接匹配。

#### 配置

在 `.vscode/launch.json` 中：

```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "type": "gscript",
      "request": "launch",
      "name": "调试当前文件",
      "files": ["${file}"],
      "stopOnEntry": false,
      "jarPath": "${workspaceFolder}/target/gscript-1.0-SNAPSHOT.jar"
    },
    {
      "type": "gscript",
      "request": "launch",
      "name": "多文件调试 (multi_a + multi_b)",
      "files": [
        "${workspaceFolder}/src/main/resources/multi_a.script",
        "${workspaceFolder}/src/main/resources/multi_b.script"
      ],
      "stopOnEntry": false
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `files` | `string[]` | 按顺序加载的脚本路径数组（共享 global 域，后加载文件覆盖前文件同名函数）。**推荐用法** |
| `program` | `string` | 单文件路径（与 `files` 二选一，`files` 优先；兼容旧用法） |
| `stopOnEntry` | `boolean` | 是否在脚本入口处暂停（执行首条指令前挂起），默认 `false` |
| `localRoot` / `remoteRoot` | `string` | 路径映射（launch 模式一般无需配置，保留以兼容带映射的启动场景） |
| `jarPath` | `string` | 调试适配器 jar 路径（覆盖 `gscript.jarPath` 设置） |
| `javaPath` | `string` | java 可执行路径（覆盖 `gscript.javaPath` 设置） |

#### 操作流程

1. 打开一个 gscript 脚本（`.gs` / `.gscript` / `.script`）
2. 在行号左侧点击设置**断点**（红点）
3. 选择「调试当前文件」配置，按 **F5** 启动
4. 命中断点后：
   - **F5** 继续 / **F10** 单步步过 / **F11** 单步步入 / **Shift+F11** 单步步出
   - 「调用栈」面板查看函数调用链
   - 「变量」面板查看 Locals（沿作用域链）+ Global（顶层 `var` 在 Global 作用域，需展开 Global）
   - 「调试控制台」输入表达式求值（支持 `a.b.c` 形式的标识符与属性访问）
5. **Shift+F5** 停止调试（launch 模式 disconnect = terminate，进程退出）

#### 调试技巧

- **多文件调试**：`files` 数组按顺序加载，后加载文件覆盖前文件同名函数。断点可设在任一文件。
- **`stopOnEntry`**：设为 `true` 可在首条指令前挂起，便于从程序入口单步跟踪。
- **`console.log` 输出**：Launch 模式下 stdout 被 DAP 协议占用，`console.log` 通过 DAP "output" 事件显示在「调试控制台」。
- **路径不命中**：VSCode 的 `${workspaceFolder}` 解析路径与断点 `source.path` 可能混合斜杠/大小写不一致。gscript 内部用 `File.getCanonicalPath()` 规范化路径，若仍不命中，检查 `dap_debug.log`（见下文）。
- **顶层变量**：顶层 `var` 声明落入 Global 作用域，不在 Locals 中——查看时需展开「变量」面板的 Global 节点。

### Attach 模式（附加到已运行进程）

调试适配器以 socket 模式独立运行（`java -jar <jar> --port=4711`），VSCode 通过 `host`/`port` 连接。适用于：调试适配器需在 IDE 之外单独运行（容器内、远程机器、或 gscript 进程已启动后动态附加）。

Attach 模式有三种启用方式（对应 [DebugAgent 三种调试模式](#debugagent-三种调试模式)）：

#### 方式一：launch（`waitForDebuggerAndRun`，阻塞等待）

gscript 程序启动时即进入 debug 模式，**阻塞等待** VSCode 连接后由 agent 后台线程执行 gclass。适合从程序入口调试。

1. 编译 gclass：`java -cp target/classes org.gscript.TestScript <name> compile`
2. 启动：`java -cp target/classes org.gscript.TestScript <name> debugagent`（阻塞等连接）
3. VSCode 选择 attach 配置，按 F5 连接，程序开始执行

#### 方式二：运行时 attach（`startAttachListener`，动态附加）

gscript 程序已正常运行，VSCode **随后连接**附加调试。适合调试运行时才出现的问题（如同 `node --inspect`）。

1. 编译 gclass：`java -cp target/classes org.gscript.TestScript <name> compile`
2. 启动：`java -cp target/classes org.gscript.TestScript <name> debugagent-attach`（解释器先运行）
3. VSCode 选择 attach 配置，按 F5 附加，在当前位置挂起

#### 方式三：主线程驱动 attach（`waitForDebuggerAndAttach`）

主线程阻塞等 VSCode 连接，连接后 controller 注入 + entryStopRequested，主线程继续执行——首次 eval 即挂起（reason=entry）。专为宿主 `static` 块加载脚本场景设计。

1. 编译 gclass：`java -cp target/classes org.gscript.TestScript <name> compile`
2. 启动：`java -cp target/classes org.gscript.TestScript <name> debugagent-waitattach`（阻塞等连接）
3. VSCode 选择 attach 配置，按 F5 连接，主线程开始执行并断在首次 eval

#### 配置

```json
{
  "version": "0.2.0",
  "configurations": [
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
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `port` | `number` | **必填**。调试适配器监听的 socket 端口 |
| `host` | `string` | 调试适配器主机地址，默认 `localhost` |
| `localRoot` | `string` | 本地源码根目录（VSCode 端），用于映射断点路径到远程 sourcePath |
| `remoteRoot` | `string` | 远程源码根目录前缀（gclass sourcePath 的前缀），默认空串表示 sourcePath 为相对路径 |
| `stopOnEntry` | `boolean` | 连接后是否在当前位置暂停（`startAttachListener` 模式自动暂停；此选项用于 `waitForDebugger` 模式） |

#### 路径映射（`localRoot` / `remoteRoot`）

Attach 模式下源码可能来自 gclass 携带的 `sourcePath`（远程/相对路径），需映射到本地源码文件才能命中断点：

- `remoteRoot` = gclass sourcePath 的前缀部分（剥离该前缀得到相对路径）
- `localRoot` = 本地源码根目录（拼接相对路径得到本地绝对路径）

示例：gclass 的 sourcePath 为 `/app/src/timer.script`，本地源码在 `e:/JProjects/gscript/src/main/resources/`：
- `remoteRoot` = `/app/src/`
- `localRoot` = `${workspaceFolder}/src/main/resources/`

若 gclass 的 sourcePath 已是相对路径（如 `timer.script`），`remoteRoot` 留空即可。

#### 源码查看（source 请求）

Attach 模式下若 gclass 携带 `SourceContent` 属性，VSCode 会通过 DAP source 请求获取源码内容并显示。stackTrace 响应中 `source.sourceReference > 0` 时触发该请求。

> **DAP Source 字段名**：DAP 协议 Source 对象的源码引用字段名是 `sourceReference`（**非** `reference`）。误写成 `reference` 会导致 VSCode 认 source 字段不存在，source 请求返回 "source not available"。

> **sourceReference 稳定映射**：同一 `sourcePath` 在整个 attach 会话期间永远返回同一 `sourceReference`（`sourcePathToRef` 反向映射复用，不随 stackTrace 重新分配）。源码内容（`agent.getSourceContents()`）在 attach 期间不变，故 ref→path 映射也应稳定。早期实现每次 stackTrace 都 `sourceRefs.clear()` + `nextSourceRef++` 重新分配，导致 VSCode 持有的旧 ref 失效——VSCode 在单次 stopped 内可能连发多次 stackTrace（UI 刷新），或跨 stopped 缓存旧 ref，用旧 ref 发 source 请求时 DapServer 查不到 path，返回 "source not available"。稳定映射后此问题消除。

#### 已加载源码列表（loadedSources 请求 + loadedSource 事件）

Attach 模式下，远程源码只有出现在 callStack 中时才会被 stackTrace 报告，VSCode 才能通过 source 请求获取。文件不在 callStack 时（如已 continue 跳过），用户无法重新打开源码下断点。

`loadedSources` 请求（`supportsLoadedSourcesRequest=true`）解决此问题：返回 `agent.getSourceContents()` 中所有已 eval 的源文件列表（含不在 callStack 的）。VSCode 据此在 **CALL STACK 视图底部**显示"**LOADED SCRIPTS**"折叠节点，用户展开即可看到所有已加载的远程文件，点击任意文件 → VSCode 发 source 请求获取源码 → 可直接下断点。

`sourceReference` 复用 `sourcePathToRef` 稳定映射，与 stackTrace 同一套映射，保证用户从 LOADED SCRIPTS 打开的文件与 callStack 帧的 source 是同一 sourceReference。

> **LOADED SCRIPTS 不显示问题**：VSCode 在 `configurationDone` 之前就发送 `loadedSources` 请求，此时 `getSourceContents()` 可能为空（脚本尚未 eval），返回空列表后 VSCode 不主动刷新——导致 LOADED SCRIPTS 节点不显示已加载文件，需手动 toggle 视图设置才出现。
>
> 修复采用**推拉双保险**机制：
> - **拉模式（loadedSources 请求）**：VSCode 主动请求全量列表，DapServer 返回 `getSourceContents()` 所有源文件。
> - **推模式（loadedSource 事件）**：首次 `onSuspended` 时（脚本已 eval），DapServer 主动发送 `loadedSource` 事件（`reason="new"`）逐个通知 VSCode 新加载的源文件，VSCode 收到后立即加入 LOADED SCRIPTS 视图。
> - **process 事件**：首次 `onSuspended` 时还发送 `process` 事件（`startMethod="attach"`），VSCode 收到后重新请求 `loadedSources` 作为全量刷新。
>
> `announcedSources` 集合跟踪已通知的源文件路径，避免重复发送 `loadedSource` 事件。每次 attach 会话（DapServer 实例）独立维护，disconnect 后重建自然清空。

### 调试功能矩阵

| 功能 | 支持情况 | 说明 |
|------|---------|------|
| 行断点 | ✅ | 点击行号左侧设置 |
| 单步步过 / 步入 / 步出 | ✅ | F10 / F11 / Shift+F11 |
| 继续 / 暂停 | ✅ | F5 / Ctrl+Pause |
| 调用栈 | ✅ | 显示函数调用链（跨文件） |
| 变量查看 | ✅ | Locals（沿作用域链）+ Global |
| 对象展开 | ✅ | 点击变量前的展开箭头（递归） |
| 表达式求值 | ✅（轻量） | 支持 `a.b.c` 标识符与属性访问；不支持算术/函数调用/字面量 |
| `stopOnEntry` | ✅ | launch + attach 均支持 |
| 路径映射 | ✅ | attach 模式 `localRoot`/`remoteRoot` |
| 条件断点 | ❌ | 暂不支持 |
| 异常断点（pause on exceptions） | ✅ | DAP `setExceptionBreakpoints` filters 控制；未捕获异常首次抛出时挂起（reason=`exception`），`GSException.paused` 标志保证"只挂起一次"，跨帧传播不重复触发 |
| 已加载源码（LOADED SCRIPTS） | ✅ | DAP `loadedSources` 请求（`supportsLoadedSourcesRequest=true`）+ `loadedSource` 事件（推模式，`reason="new"`）+ `process` 事件（触发全量刷新）。attach 模式下 VSCode 在 CALL STACK 视图底部显示"LOADED SCRIPTS"节点，列出所有已 eval 的远程源码文件。首次挂起时主动推送，解决 VSCode 缓存空列表不刷新的问题。用户可随时打开任意已加载文件下断点，无需从别的文件步进进入 |
| disconnect 后重新 attach | ✅ | `startAttachListener` accept 线程 `while(true)` 循环 accept，disconnect 后不退出，VSCode 可重新连接。每次连接创建新 DapServer 实例（状态重置），controller 重新注入 + `pauseOnAttach` 挂起 |

### 故障排查

| 现象 | 排查方向 |
|------|----------|
| 断点不命中（空心灰点） | 检查 `localRoot`/`remoteRoot` 映射；查看 `dap_debug.log` 的 setBreakpoints 请求路径；确认 `source.path` 经 `getCanonicalPath()` 规范化后与脚本一致 |
| launch.json 属性「不允许」错误 | 确认已安装扩展 v0.2.1+（`code --list-extensions --show-versions`）；旧版 VSIX 需重新打包安装 |
| 「未配置 jar 路径」错误 | 在 VSCode 设置 `gscript.jarPath` 或 launch.json 配置 `jarPath` 字段 |
| attach 连接失败 | 确认调试适配器已以 `--port=<port>` 启动；`host`/`port` 一致；防火墙未拦截 |
| 变量面板看不到顶层变量 | 顶层 `var` 在 Global 作用域，展开「变量」面板的 Global 节点 |
| stepIn 跨文件不挂起 | 单步逻辑需同时比较行号和文件路径（已修复，若复现查看 `dap_debug.log`） |
| VSCode 收不到 terminated 事件 | `startAttachListener`/`waitForDebuggerAndAttach` 模式下需显式调用 `agent.notifyScriptCompleted()`（launch 模式自动发） |
| source 请求返回 "source not available" | 确认 gclass 含 sourceContent（`compile` 模式生成）；确认 DAP Source 字段名是 `sourceReference`（非 `reference`）；若偶发（VSCode 多次 stackTrace 或缓存旧 ref 触发），检查 `sourceRefs` 是否被 `clear()`——已改为 `sourcePathToRef` 稳定映射，不再 clear |
| 远程源码跳过后难以重新下断点 | attach 模式下文件不在 callStack 时 stackTrace 不返回它，VSCode 无法重新打开源码。展开 CALL STACK 视图底部的"LOADED SCRIPTS"节点（`loadedSources` 请求），列出所有已 eval 的远程文件，点击即可打开下断点 |
| LOADED SCRIPTS 节点不显示文件 | VSCode 在 `configurationDone` 前发 `loadedSources` 请求，此时 `getSourceContents()` 可能为空（脚本尚未 eval），返回空列表后 VSCode 不主动刷新。已修复：首次 `onSuspended` 时发送 `loadedSource` 事件（推模式）+ `process` 事件（触发全量刷新），VSCode 收到后立即显示已加载文件 |
| disconnect 后无法重新 attach | `startAttachListener` 的 accept 线程原实现 `dapServer.run()` 返回后即退出，无人 accept 新连接。已修复：accept 线程以 `while(true)` 循环 accept，disconnect 后回到等待状态，VSCode 可重新 attach。每次连接创建新 DapServer 实例（状态重置），controller 重新注入 + `pauseOnAttach` 挂起 |

## 测试

测试脚本位于 `tests/` 目录，使用 Python 编写，通过 socket DAP 客户端验证调试协议。

### 公共模块

- `tests/dap_client.py`：`SocketDapClient`（修复版 `wait_event`，不匹配事件放回队列）+ `ensure_gclass` + `start_java` + `check` + `wait_stderr_ready`。供 `test_debug_mode_*.py` 系列复用。

### 测试入口

`TestScript <name> debugagent-eval` 启动 `enableDebugMode` + `startAttachListener` + 3 次 eval（gclass/evalScript/evalExpression，间隔 1 秒）+ `notifyScriptCompleted`，供 `test_debug_mode_basic`/`multi_eval`/`eval_expression`/`unconnected` 使用。

### 全量测试

```powershell
cd tests
python run_baseline.py
```

当前测试覆盖（301 passed, 0 failed）：

| 测试文件 | 用例数 | 覆盖内容 |
|----------|--------|----------|
| test_timer.py | 15 | 定时器基础语义 |
| test_host_interaction.py | 12 | 宿主交互 API |
| test_gclass.py | 65 | gclass 序列化/反序列化 |
| test_dap_e2e.py | 17 | DAP 端到端 |
| test_while_breakpoint.py | 9 | while 循环断点 |
| test_multi_file.py | 19 | 多文件调试 |
| test_cross_file_step.py | 7 | 跨文件单步 |
| test_step_catch.py | 7 | 单步 catch |
| test_path_mismatch.py | 3 | 路径不匹配 |
| test_timer_debug.py | 25 | 定时器调试 |
| test_wait_attach.py | 18 | waitForDebuggerAndAttach |
| test_debug_mode_basic.py | 14 | enableDebugMode 基础 |
| test_debug_mode_multi_eval.py | 10 | 多次 eval |
| test_debug_mode_eval_expression.py | 8 | evalExpression 调试 |
| test_debug_mode_unconnected.py | 6 | VSCode 未连不阻塞 |
| test_debug_mode_wait_attach.py | 10 | waitAttach 模式 |
| test_pause_exception.py | 16 | pause on exceptions（异常断点 + 只挂起一次语义） |
| test_callfn_breakpoint.py | 17 | 宿主 callFunction 触发函数体内断点（虚拟源 sourceReference>0） |
| test_attach_smoke.py | 23 | attach 冒烟（模式1 waitForDebugger 10 + 模式2 attachReady 13） |

### 脚本测试

内置类型与方法的回归测试脚本位于 `src/main/resources/`，命名约定 `<name>.test.script`，通过 `TestScript <name> run` 执行：

```bash
java -cp target/classes org.gscript.TestScript array_methods_test run        # 数组基础方法 7 个
java -cp target/classes org.gscript.TestScript array_splice.test run         # splice（删除/插入/替换/负索引/缺省/清空）
java -cp target/classes org.gscript.TestScript array_length_set.test run     # length 可写（截断/扩容/清空/负值归零）
java -cp target/classes org.gscript.TestScript array_foreach_map_filter.test run  # forEach/map/filter（回调签名/链式/异常传播/闭包）
java -cp target/classes org.gscript.TestScript object_keys.test run          # keys()（属性名/自有属性优先/数组 keys）
java -cp target/classes org.gscript.TestScript string_methods_test run       # 字符串 17 方法
java -cp target/classes org.gscript.TestScript type_conversion_test run      # 全局类型转换函数（parseInt/Number/...）
java -cp target/classes org.gscript.TestScript implicit_conversion_test run  # 隐式类型转换（JS 语义对齐：算术/比较/位运算 ToNumber）
```
