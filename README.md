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

(* 可以访问属性的值 *)
AccessProperty
    = Identifier
    | "(", Expression, ")"
    | ArrayLiteral;

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
f
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

## 编译文件格式

## 运行

加载编译的字节码文件，相当于加载一个匿名函数并立马执行。