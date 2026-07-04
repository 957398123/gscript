# GSString 增强 + 全局类型转换函数

## Context

当前 `GSString` 仅有 5 个字符串方法（`charAt`/`indexOf`/`substring`/`toUpperCase`/`toLowerCase`），且采用**闭包模式**实现——每次 `getProperty` 都 `new GSNativeFunction` 捕获 `this.value`，与 `GSArray` 已确立的**静态共享模式**（`Array.prototype.xxx` 语义）不一致，存在内存浪费与风格分裂问题。

同时 gscript **完全没有脚本级类型转换函数**：脚本里无法把字符串 `"123"` 转成数字、无法判断 NaN、无法把任意值显式转成 String/Number/Boolean。当前只能依赖隐式转换（如 `x - 0`、`x + ""`），无法处理解析失败（应返回 NaN）和进制转换等场景。

本次改造目标：
1. **GSString 增强**：补齐 JS String.prototype 常用方法，重构为静态共享模式（与 GSArray 一致）
2. **全局类型转换函数**：注册 `parseInt`/`parseFloat`/`isNaN`/`String`/`Number`/`Boolean` 到 global 域，对标 JS

## 改造范围（用户已确认）

- ✅ GSString 增强 + 现有 5 方法重构为静态共享
- ✅ 全局类型转换函数（JS 风格）
- ❌ 不做 GSInt/GSFloat 数值方法（toString(radix)/toFixed 等）
- ❌ 不做 GSObject 对象方法（hasOwnProperty/keys/values 等）

## 关键设计决策

### 1. 静态共享模式的字段访问
`GSArray` 之所以能用静态方法，是因为 `members` 字段在父类 `GSObject` 中是 `protected`。`GSString.value` 是 `private final`，但 **Java 允许同一类的静态方法访问该类实例的 private 字段**，故静态方法中 `(GSString) args.get(0)).value` 可直接读取，**无需修改 `value` 可见性**。

### 2. OP_INVOKE 的 args[0] 约定
`GSInterpreter.java:629-662` 的 `OP_INVOKE` 分支：构造 `callArgs = [objectRef(this), arg1, ..., argN]`，原生函数（type==9）走 `nativeFunction.eval(callArgs)`。故静态方法内 `(GSString) args.get(0)` 即字符串本身，参数从 `args.get(1)` 起。与 GSArray 完全对称。

### 3. 全局函数注册方式
对标 `installTimerGlobals()`（GSInterpreter.java:1332-1338）：新增 `installTypeGlobals()`，在构造器中紧随 `installTimerGlobals()` 调用。每个全局函数是一个 `GSNativeFunction` 实例，通过 `addVariableToGlobal(name, fn)` 注册。

### 4. NaN 语义
解析失败统一返回 `GSNaN.NAN`（type=10，已有）。`isNaN()` 用 `value.type == 10` 判断。

## 实现步骤

### 步骤 1：重构 GSString.java
**文件**：[src/main/java/org/gscript/vm/value/GSString.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/value/GSString.java)

**改动**：
1. 把现有 5 个方法（`charAt`/`indexOf`/`substring`/`toUpperCase`/`toLowerCase`）从闭包模式改为 `private static final GSNativeFunction` 实例
2. 新增 12 个静态共享方法：

| 方法 | 语义 | 边界处理 |
|------|------|---------|
| `slice(start[, end])` | 区间子串，支持负索引 | 负索引 += length；start>end 返回空串 |
| `substr(start[, length])` | 从 start 取 length 个字符 | start 负 += length；length 缺省取到末尾 |
| `split([separator])` | 分割成 GSArray | 无参返回单元素数组；空串返回 [""]；separator 为空串按字符拆 |
| `replace(search, replacement)` | 替换**首次**出现 | search 为空串插在开头；找不到返回原串 |
| `trim()` | 去两端空白 | 直接用 `String.trim()`（Java 1.0+ 自带，去码点 ≤ U+0020 的字符，与 JS `\s` 略有差异但 ASCII 空白场景一致） |
| `startsWith(prefix[, position])` | 判断前缀 | position 缺省=0；越界归一化 |
| `endsWith(suffix[, endPosition])` | 判断后缀 | endPosition 缺省=length |
| `includes(substr[, position])` | 是否包含 | position 缺省=0 |
| `repeat(n)` | 重复 n 次 | n<0 抛 GSException("RangeError")；n=0 返回空串 |
| `concat(...strs)` | 拼接多个字符串 | 无参返回原串 |
| `lastIndexOf(str)` | 最后一次出现索引 | 未找到返回 -1 |
| `charCodeAt(index)` | 字符编码（GSInt） | 越界返回 GSNaN.NAN（JS 语义） |

3. `getProperty` 改为 if-else 返回静态实例（与 GSArray 风格一致），`length` 仍返回 `new GSInt(value.length())`
4. 注意 `substring` 保留原 JS 语义（start>end 交换，不支持负索引），与新增的 `slice`（支持负索引）区分

### 步骤 2：新增全局类型转换函数
**文件**：[src/main/java/org/gscript/vm/GSInterpreter.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java)

**改动**：
1. 构造器（line 84-87）在 `installTimerGlobals()` 后追加 `installTypeGlobals()`
2. 新增 `public void installTypeGlobals()` 方法（放在 `installTimerGlobals()` 旁边，line 1338 后），注册 6 个全局函数：

| 函数 | 行为 | 示例 |
|------|------|------|
| `parseInt(str[, radix])` | 解析整数，支持进制 2-36；提取前导数字部分（JS 容错）；无效返回 NaN | `parseInt("123")`→123；`parseInt("ff",16)`→255；`parseInt("abc")`→NaN |
| `parseFloat(str)` | 解析浮点；提取前导数字部分；无效返回 NaN | `parseFloat("3.14")`→3.14；`parseFloat("3.14abc")`→3.14 |
| `isNaN(value)` | 判断是否 NaN | `isNaN(NaN)`→true；`isNaN("abc")`→true；`isNaN(1)`→false |
| `String(value)` | 转 GSString（等价 toStringValue） | `String(123)`→"123"；`String(null)`→"null" |
| `Number(value)` | 严格转数字；bool→0/1；null→0；纯数字串→数值；其他→NaN | `Number("123")`→123；`Number(true)`→1；`Number("abc")`→NaN |
| `Boolean(value)` | 转 GSBool（等价 toBoolean） | `Boolean(0)`→false；`Boolean("")`→false；`Boolean("x")`→true |

**parseInt 实现要点**（JS 容错语义，非 Java Integer.parseInt 严格语义）：
- trim 前导空白
- 可选 `+`/`-` 符号
- 提取连续有效数字（按 radix 判断字符范围），遇到非数字停止
- 解析前缀，失败返回 NaN
- radix 缺省=10；radix 不在 [2,36] 返回 NaN

**parseFloat 实现要点**：
- trim 前导空白
- 可选 `+`/`-` 符号
- 提取 `[digits][.digits][e[+-]digits]` 前缀
- 用 `Float.parseFloat` 解析前缀，失败返回 NaN

**Number 实现要点**（严格语义，与 parseInt 不同）：
- bool → 0/1
- null → 0
- int/float → 原值
- 字符串：trim 后整体必须是合法数字（`Integer.parseInt` 失败则试 `Float.parseFloat`），否则 NaN
- 其他类型 → NaN

### 步骤 3：测试脚本
**新建文件**：`src/main/resources/string_methods_test.script`
- 复用 `array_methods_test.script` 的 `check(name, expected, actual)` 模式
- 9-10 个测试组覆盖所有 17 个方法（5 旧 + 12 新）+ 边界（空串、负索引、越界）
- 目标 ~40 项检查

**新建文件**：`src/main/resources/type_conversion_test.script`
- 6 个测试组覆盖 6 个全局函数
- 重点测试：进制转换、解析失败返回 NaN、Number 严格 vs parseInt 容错、Boolean falsy 值（0/""/null/NaN）
- 目标 ~30 项检查

**无需修改 TestScript.java**：现有 `run` 模式（`java -cp target/classes org.gscript.TestScript <name> run`）即可执行新脚本。

## 关键文件清单

| 文件 | 改动类型 |
|------|---------|
| [src/main/java/org/gscript/vm/value/GSString.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/value/GSString.java) | 重构 + 新增方法 |
| [src/main/java/org/gscript/vm/GSInterpreter.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java) | 新增 installTypeGlobals() + 构造器调用 |
| src/main/resources/string_methods_test.script | 新建测试脚本 |
| src/main/resources/type_conversion_test.script | 新建测试脚本 |

## 验证方案

### 1. 编译（必须 JDK 1.8 生成真 1.4 字节码）
```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk1.8.0_202"
mvn clean package -DskipTests
```

### 2. 新功能验证
```powershell
java -cp target/classes org.gscript.TestScript string_methods_test run
java -cp target/classes org.gscript.TestScript type_conversion_test run
```
预期：所有 check 输出 `[OK]`，无 `[FAIL]`。

### 3. 回归测试（确保未破坏现有功能）
运行 `/tests` 下全量 Python 测试套件（当前基线 301 passed）：
```powershell
python -m pytest tests/ -v
```
重点关注：
- `test_gclass.py`（gclass 序列化，GSString 改动可能影响）
- `test_host_interaction.py`（宿主 API，全局函数改动可能影响）
- `test_timer_debug.py`（定时器，构造器改动可能影响）

### 4. 更新记忆
完成后更新 `c:\Users\95739\.trae-cn\memory\projects\-e-JProjects-gscript\project_memory.md`，记录：
- GSString 17 个方法清单 + 静态共享模式
- 6 个全局类型转换函数 + installTypeGlobals() 入口
- parseInt 容错 vs Number 严格的语义差异
- 全量回归测试结果

## 风险与注意事项

1. **Java 1.4 编译约束**：禁止自动装箱、泛型、增强 for。`ArrayList` 而非 `ArrayList<String>`。`((GSValue) args.get(0))` 显式转型。`Boolean`/`Integer`/`Float` 包装类用 `new` 构造或 `valueOf`。
2. **`String` 作为全局函数名**：gscript 允许标识符与类型名同名（`String` 不是关键字），注册为 global 变量即可。脚本里 `String(123)` 走 OP_INVOKE 全局查找，不冲突。
3. **`trim` 语义差异**：直接用 `String.trim()`（Java 1.0+）。JS `trim` 去所有 `\s`（含 Unicode 空白），Java `trim` 去码点 ≤ U+0020 的字符。ASCII 空白场景行为一致，文档化为 Java 行为。
4. **`repeat` 负数处理**：JS 抛 RangeError，gscript 无 Error 子类，用 `throw "RangeError: Invalid count value";`（GSString）。
5. **parseInt vs Number 语义差异**：这是最易混淆的点，测试脚本必须覆盖 `parseInt("123abc")→123` vs `Number("123abc")→NaN` 的对比。
