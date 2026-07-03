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
"str".length              // null（GSString 暂无 length 属性）
null.foo                  // 抛 TypeError: Cannot read properties of null
(42).foo                  // null（数值字面量无属性，静默返回 null）
```

> **词法注意**：`42.foo` 会被词法器解析为浮点字面量 `42.` 加标识符 `foo`（与 JS 一致），需写成 `(42).foo` 或 `42..foo` 才能访问整数字面量的属性；`3.14.bar` 则可直接使用。

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

本项目已迁移为 **纯 Java 1.4** 实现，零外部依赖。

### 环境要求

- **Java 1.4+**（源码与目标均为 1.4，可在真实 JDK 1.4 环境编译执行）
- **Maven 3.x**（仅用于构建，无任何外部依赖）

### 关键设计

| 迁移项 | 原实现（Java 9+） | 迁移后（Java 1.4） |
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

```bash
mvn package -DskipTests
```

生成 `target/gscript-1.0-SNAPSHOT.jar`，可直接 `java -jar` 运行调试适配器。

> **注意**：JDK 9+ 编译器不支持 `-source 1.4`（最低 1.6）。在 JDK 9+ 环境验证时，需临时将 pom.xml 的 `<source>`/`<target>` 改为 `1.6`。代码本身仅使用 Java 1.4 语法与 API，在真实 JDK 1.4 环境可直接编译。

## 在 Java 环境中使用 gscript（完整操作指南）

本节给出在 Java 环境中从零开始使用 gscript 的完整流程：环境配置 → 依赖引入 → 基础语法 → 常见使用场景。

### 1. 环境配置

| 项 | 要求 | 说明 |
|----|------|------|
| JDK | 1.4+ | 源码与字节码目标均为 1.4，可在真实 JDK 1.4 编译执行 |
| Maven | 3.x | 仅用于构建，无任何外部依赖 |
| OS | 跨平台 | 纯 Java，Windows/Linux/macOS 均可 |

**JDK 9+ 开发机注意**：JDK 9 编译器最低支持 `-source 1.6`，无法直接用 1.4 编译。开发验证时需临时把 `pom.xml` 的 `<source>`/`<target>` 改为 `1.6`，构建完成后再改回 `1.4`。代码本身仅使用 Java 1.4 语法与 API，改回 1.4 后可在真实 JDK 1.4 环境直接编译。

### 2. 依赖引入

gscript **零外部依赖**（原 Gson 已替换为自研 JSON 库，`java.util.concurrent` 已替换为自研并发原语）。引入方式有两种：

**方式 A：源码构建（推荐）**

```bash
git clone <repo> gscript
cd gscript
mvn package -DskipTests
```

生成 `target/gscript-1.0-SNAPSHOT.jar`（约 190KB，纯项目 jar，无 shade 无 fat jar）。

**方式 B：直接引用 jar**

将 `gscript-1.0-SNAPSHOT.jar` 加入应用 classpath：

```bash
# 命令行
java -cp path/to/gscript-1.0-SNAPSHOT.jar org.gscript.TestScript hello run

# Maven 项目（install 到本地仓库后）
mvn install:install-file -Dfile=gscript-1.0-SNAPSHOT.jar \
    -DgroupId=org.gscript -DartifactId=gscript -Dversion=1.0-SNAPSHOT -Dpackaging=jar
```

然后在 `pom.xml` 中引用（注意：gscript 本身零依赖，无需传递依赖）：

```xml
<dependency>
  <groupId>org.gscript</groupId>
  <artifactId>gscript</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

### 3. 基础语法示例

gscript 是类 JS 语法的脚本语言，支持变量、函数、控制流、对象/数组、异常、定时器等。以下是一个覆盖主要语法的示例（保存为 `hello.script`）：

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
```

执行（详见下一节「常见使用场景」）：

```bash
java -cp target/classes org.gscript.TestScript hello run
```

更多语法细节见本文档开头的 [语法定义](#语法定义)，字面量成员访问见 [字面量上的成员访问](#字面量上的成员访问)。

### 4. 常见使用场景

#### 场景一：命令行执行脚本（CLI）

最直接的方式，编译源码 + 立即执行：

```bash
java -cp target/classes org.gscript.TestScript <name> [run|dump|compile|rungclass|dumpgclass|hosttest]
```

| 模式 | 说明 |
|------|------|
| `run`（默认） | 编译源码 + 执行 |
| `dump` | 编译源码 + 打印字节码↔源码行映射 |
| `compile` | 编译源码 + 写出 `.gclass` 文件 |
| `rungclass` | 加载 `.gclass` + 执行 |
| `dumpgclass` | 加载 `.gclass` + 打印字节码映射 + FunctionTable |
| `hosttest` | 宿主交互 API 演示（Java 调用 gscript 解释器） |

`<name>` 对应 `src/main/resources/<name>.script`（去掉扩展名）。详见下文 [TestScript 命令行模式](#testscript-命令行模式)。

#### 场景二：Java 宿主嵌入（应用集成）

在 Java 应用中直接 `new GSInterpreter()` 执行 gscript 代码、读写变量，实现 Java ↔ gscript 双向交互：

```java
GSInterpreter interpreter = new GSInterpreter();
interpreter.addVariableToGlobal("console", new Console());

interpreter.evalScript("var x = 10; function add(a, b) { return a + b; }");

GSValue sum = interpreter.evalExpression("add(x, 20)");
System.out.println(sum.toIntValue());              // 30

interpreter.setVariable("config", myJavaMap);       // 注入 Java 对象
GSValue v = interpreter.evalExpression("config.timeout");
```

完整 API 与类型映射见下文 [宿主交互 API](#宿主交互-api)。

#### 场景三：.gclass 二进制缓存

将 `.script` 编译为 `.gclass` 二进制文件（含 CRC32 校验、SourceMap、FunctionTable），用于网络传输或磁盘缓存，加载时无需重新编译：

```bash
# 编译
java -cp target/classes org.gscript.TestScript hello compile
# 加载执行
java -cp target/classes org.gscript.TestScript hello rungclass
```

`.gclass` 文件格式详见 [编译文件格式（.gclass）](#编译文件格式gclass)。

#### 场景四：定时器与事件循环

gscript 内置 JS 风格的 `setTimeout`/`setInterval`/`clearTimeout`/`clearInterval`，单线程事件循环语义，回调内断点天然工作：

```javascript
var counter = 0;
var id = setInterval(function() {
    counter = counter + 1;
    console.log("tick " + counter);
    if (counter >= 3) { clearInterval(id); }
}, 100);
```

完整 API 与事件循环语义见下文 [定时器 API](#定时器-api-settimeout--setinterval)。

## 运行


### 从源码运行

加载 `.script` 源码，编译为字节码后立即执行（内部用 `BytecodeEncoder` 编码为 `byte[][]` + 常量池）。

### 从 .gclass 运行

加载 `.gclass` 二进制文件，反序列化后直接执行 `byte[][]` 字节码，无需重新编译。
相当于加载一个匿名函数并立即执行。同文件所有函数共享同一个常量池引用。

### TestScript 命令行模式

```
java -cp target/classes org.gscript.TestScript <name> [run|dump|compile|rungclass|dumpgclass|hosttest]
```

| 模式 | 说明 |
|------|------|
| `run`（默认） | 编译源码 + 执行 |
| `dump` | 编译源码 + 打印字节码↔源码行映射 |
| `compile` | 编译源码 + 写出 `.gclass` 文件 |
| `rungclass` | 加载 `.gclass` + 执行 |
| `dumpgclass` | 加载 `.gclass` + 打印字节码映射 + FunctionTable |
| `hosttest` | 宿主交互 API 演示（Java 调用 gscript 解释器） |

## 宿主交互 API

Java 宿主通过 `GSInterpreter` 直接执行 gscript 代码、读写变量，实现 Java ↔ gscript 双向交互。
gscript → Java 方向已通过 `GSNativeFunction`（如 `Console`）实现；以下为 Java → gscript 方向的 API。

### GSInterpreter 方法

| 方法 | 说明 |
|------|------|
| `evalScript(String code)` | 在当前全局上下文中执行一段 gscript 源码（语句序列），共享 global 环境 |
| `evalExpression(String expr)` | 求值一个 gscript 表达式并返回结果（包装为 `return (expr);` 执行）；出错返回 `GSNull.NULL` |
| `getVariable(String name)` | 从全局作用域获取变量；未定义返回 `GSNull.NULL` |
| `setVariable(String name, Object value)` | 设置全局变量，自动包装 Java 对象为 GSValue |

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
GSInterpreter interpreter = new GSInterpreter();
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
interpreter.setVariable("z", 100);
interpreter.setVariable("config", Map.of("timeout", 5000, "retries", 3));
interpreter.evalScript("console.log(z); console.log(config.timeout);");

// 5. GSValue → Java 对象（递归转换）
GSValue obj = interpreter.evalExpression("{name: \"Alice\", age: 30, scores: [90, 85, 95]}");
Map<String, Object> javaObj = (Map<String, Object>) obj.toJavaObject();
// javaObj = {name="Alice", age=30, scores=[90, 85, 95]}

// 6. Java 对象 → GSValue（注入 gscript）
List<Integer> tags = Arrays.asList(1, 2, 3);
interpreter.setVariable("tags", tags);
GSValue first = interpreter.evalExpression("tags[0]");
System.out.println(first.toIntValue());  // 1
```

### 实现要点

- `evalScript` / `evalExpression` 共享 `interpreter.global` 环境——多次调用的变量/函数定义累积在 global 中
- `evalExpression` 先 `stack.clear()` 清空残留值，确保返回值是本次表达式的结果；表达式出错时捕获异常并返回 `GSNull.NULL`
- top-level `return` 合法（EBNF: `Program = { Statement }`，`ReturnStatement` 属于 `Statement`），`OP_RETURN` 将返回值留在共享栈上供 `evalExpression` 弹出
- `getVariable` 基于 `global.getVariableValue(name)`——top-level `var` 声明直接落入 global（`ProgramNode` 不 emit `pushenv`）

## 定时器 API（setTimeout / setInterval）

gscript 提供 JS 风格的定时器，采用**单线程事件循环**语义：守护线程 `gscript-timer` 只负责计时（不执行字节码），到期任务入队后由主线程串行执行回调。回调内设置的断点/单步天然工作。

### 全局函数

| 函数 | 说明 | 返回值 |
|------|------|--------|
| `setTimeout(callback, delayMs, ...args)` | 延迟 `delayMs` 毫秒后执行一次 `callback` | timer id（整数） |
| `setInterval(callback, periodMs, ...args)` | 每隔 `periodMs` 毫秒重复执行 `callback` | timer id（整数） |
| `clearTimeout(id)` | 取消尚未触发的 setTimeout | `null` |
| `clearInterval(id)` | 取消 setInterval | `null` |

- `callback` 必须是 function，否则打印 TypeError 并返回 `null`
- `delayMs` / `periodMs` 为整数毫秒；负数延迟当 0 处理，`periodMs < 1` 当 1 处理（避免忙等）
- `...args` 透传给回调（回调内从第 1 个参数起取，`this` 为 `null`）

### 事件循环

主脚本 `eval` 返回后，解释器自动进入事件循环 `runEventLoop`，pump 定时器队列直到排空（`hasPending() == false`）：

- **纯 setTimeout 脚本**：所有回调执行完后队列排空，事件循环退出，进程正常终止
- **纯 setInterval 脚本**：永不退出（需 `clearInterval` 或进程终止）
- **异常策略（类 JS）**：回调内未捕获异常打印到 stderr 后继续下一个任务；`DebugAbortException`（调试终止请求）传播出循环终止事件循环

### 调试器交互

- 回调在主线程执行（`callFunction` 复用 `callStack.push/pop` + `suspendCheck`），断点/单步/变量查看与普通函数调用一致
- `terminated` 事件时机延后到事件循环返回后（即所有定时器排空才发 terminated）
- **attach 模式 disconnect**：仅分离调试器（如同 `node --inspect`），setInterval 程序继续运行；需发送 `terminate` 请求或 kill 进程终止

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

## VSCode 调试

gscript 提供 VSCode 扩展（`gscript-debug`），支持 **Launch**（stdio，本地启动）和 **Attach**（socket，附加到已运行进程）两种调试模式，覆盖断点、单步、调用栈、变量查看、表达式求值等完整调试能力。

### 前置准备

1. **构建调试适配器 jar**

   ```bash
   mvn package -DskipTests
   ```

   生成 `target/gscript-1.0-SNAPSHOT.jar`（纯项目 jar，无外部依赖）。

2. **安装 VSCode 扩展**

   ```powershell
   # 方式 A：用项目内置脚本打包并安装
   powershell -ExecutionPolicy Bypass -File tests\build_vsix.ps1
   code --install-extension $env:TEMP\gscript-debug-0.2.0.vsix --force

   # 方式 B：开发模式（推荐开发期）
   # 将 vscode-extension 目录在 VSCode「扩展开发宿主」中打开，按 F5 调试
   ```

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

Attach 模式有两种启用方式：

#### 方式一：debug 模式（`waitForDebugger`，阻塞等待）

gscript 程序启动时即进入 debug 模式，**阻塞等待** VSCode 连接后才开始执行。适合从程序入口调试。

1. 启动调试适配器（socket 模式）：

   ```bash
   java -jar target/gscript-1.0-SNAPSHOT.jar --port=4711
   ```

2. gscript 程序以 debug 模式启动（具体取决于宿主如何调用解释器；程序会阻塞等待调试器连接）

3. VSCode 选择 attach 配置，按 F5 连接，程序开始执行

#### 方式二：运行时 attach（`attachReady`，动态附加）

gscript 程序已正常运行，VSCode **随后连接**附加调试。适合调试运行时才出现的问题（如同 `node --inspect`）。

1. gscript 程序正常运行（已加载脚本并执行）
2. 启动调试适配器（socket 模式）：
   ```bash
   java -jar target/gscript-1.0-SNAPSHOT.jar --port=4711
   ```
3. VSCode 选择 attach 配置，按 F5 附加，在当前位置挂起

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
| `stopOnEntry` | `boolean` | 连接后是否在当前位置暂停（`attachReady` 模式自动暂停；此选项用于 `waitForDebugger` 模式） |

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

#### 调试技巧

- **disconnect 行为**：attach 模式 disconnect **仅分离调试器**（如同 `node --inspect`），gscript 程序（含 `setInterval`）继续运行；需发送 `terminate` 请求或 kill 进程终止。
- **`setInterval` 程序**：attach 后可调试周期回调；detach 后程序存活，可再次 attach。
- **`stopOnEntry`**：`waitForDebugger` 模式下控制连接后是否暂停；`attachReady` 模式默认在当前位置暂停。
- **调试日志**：DapServer 主循环异常写入 `dap_debug.log`，用于分析 VSCode 实际通信的 DAP 消息（断点不命中、堆栈异常等问题排查）。

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
| 异常断点 | ❌ | 暂不支持（预留） |

### 故障排查

| 现象 | 排查方向 |
|------|----------|
| 断点不命中（空心灰点） | 检查 `localRoot`/`remoteRoot` 映射；查看 `dap_debug.log` 的 setBreakpoints 请求路径；确认 `source.path` 经 `getCanonicalPath()` 规范化后与脚本一致 |
| launch.json 属性「不允许」错误 | 确认已安装扩展 v0.2.0+（`code --list-extensions --show-versions`）；`localRoot`/`remoteRoot`/`stopOnEntry` 在 launch + attach 均允许 |
| 「未配置 jar 路径」错误 | 在 VSCode 设置 `gscript.jarPath` 或 launch.json 配置 `jarPath` 字段 |
| attach 连接失败 | 确认调试适配器已以 `--port=<port>` 启动；`host`/`port` 一致；防火墙未拦截 |
| 变量面板看不到顶层变量 | 顶层 `var` 在 Global 作用域，展开「变量」面板的 Global 节点 |
| stepIn 跨文件不挂起 | 单步逻辑需同时比较行号和文件路径（已修复，若复现查看 `dap_debug.log`） |
