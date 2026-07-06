# 天劫辅助工具 — gscript 引擎改动需求

## Context

天劫辅助工具（`E:/IdeaProjects/S60-new`）正在进行 gscript 脚本层优化（决策树+状态机重构 + 统一定时器封装 + 子任务对象化）。优化过程中发现引擎有 4 项功能缺失，需 gscript 引擎侧新增。

本文档**独立于天劫项目**，供 gscript 引擎开发者直接参考实现。完整背景见天劫侧计划文件 [gscript脚本优化计划_决策树状态机与定时器.md](file:///e:/IdeaProjects/S60-new/.trae/documents/gscript脚本优化计划_决策树状态机与定时器.md) 第九章。

## 改动清单总览

| # | 功能 | 类型 | 优先级 | 修改文件 | 引擎基础设施 |
|---|------|------|--------|----------|------------|
| 1 | `GSArray.splice(start, deleteCount, ...items)` | 纯新增原生方法 | **必需** | [GSArray.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/value/GSArray.java) | 无需 |
| 2 | `GSArray.length` 可写 | 重写 setProperty | **必需** | GSArray.java | 无需 |
| 3 | `GSObject.keys()` | 纯新增原生方法 | 可选建议 | [GSObject.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/value/GSObject.java) | 无需 |
| 4 | `GSArray.forEach/map/filter` | 需回调 gscript 函数 | 可选建议 | GSArray.java | **需新增 thread-local interpreter 访问机制**（见 §4） |

> **关键差异**：1/2/3 是纯数据操作，`GSNativeFunction.call(args)` 内即可完成；4 需在原生函数内回调用户传入的 gscript 函数，受当前引擎"原生函数无 interpreter 引用"约束，需开发者决策实现方式。

## 已确认无需改动（核实结论）

| 能力 | 结论 | 证据 |
|------|------|------|
| 闭包 | ✅ 已支持 | [semantics_test.script:188-201](file:///E:/JProjects/gscript/src/main/resources/semantics_test.script#L188) `makeCounter` 闭包计数器，c1/c2 独立计数正确 |
| 对象字面量 + 函数属性 | ✅ 已支持 | `{fn: function(){return 42;}}.fn()` 返回 42 |
| `this` 绑定（对象方法内） | ✅ 已支持 | semantics_test.script:204-210 `obj3.getName()` 返回 "Tom" |
| 字符串方法（18 个） | ✅ 已齐全 | slice/substr/split/replace/trim/startsWith/endsWith/includes/repeat/concat/lastIndexOf/charCodeAt/... |
| switch / try-catch / throw | ✅ 已支持 | semantics_test.script 各测试段 |
| `typeof` / `delete` / `for-in` | ❌ 未实现 | **天劫优化不依赖**（用 `!= null` 判空、`splice` 删数组、`obj[key+""]` 索引访问替代），无需新增 |

---

## 1. GSArray.splice — 必需

### 现状

[GSArray.java:221-233](file:///E:/JProjects/gscript/src/main/java/org/gscript/vm/value/GSArray.java#L221) `getProperty` 仅注册 `push/pop/shift/unshift/indexOf/join/slice/length`，无 `splice`。天劫 timer_util 的 `clearTimerEx`/`_timer_tick` 需用 `splice(i,1)` 真正删除定时器项，避免 `cancelled` 标记累积。

### 请示实现

标准 JS 语义。新增静态共享 `GSNativeFunction SPLICE`，注册到 `getProperty`。复用现有 `computeLength(HashMap)` 静态方法（[GSArray.java:177](file:///E:/JProjects/gscript/src/main/java/org/gscript/vm/value/GSArray.java#L177)）。

> **deleteCount 缺省语义**：JS 中 `arr.splice(start)`（不传 deleteCount）表示删到末尾。实现需区分"未传 deleteCount"与"传了 deleteCount"。

**文件**：`src/main/java/org/gscript/vm/value/GSArray.java`

```java
/** splice(start, deleteCount, ...items): 删除/插入/替换，返回被删元素数组（JS 语义） */
private static final GSNativeFunction SPLICE = new GSNativeFunction("splice") {
    public GSValue call(ArrayList args) {
        GSArray arr = (GSArray) args.get(0);
        int len = computeLength(arr.members);

        // start 归一化
        int start = 0;
        if (args.size() >= 2) {
            start = ((GSValue) args.get(1)).toIntValue();
            if (start < 0) { start += len; if (start < 0) start = 0; }
            if (start > len) start = len;
        }

        // deleteCount：未传则删到末尾（JS 语义）
        int deleteCount;
        if (args.size() >= 3) {
            deleteCount = ((GSValue) args.get(2)).toIntValue();
            if (deleteCount < 0) deleteCount = 0;
            if (deleteCount > len - start) deleteCount = len - start;
        } else {
            deleteCount = len - start;
        }

        int insertCount = args.size() - 3;  // args[3..] 为插入项

        // 1. 收集被删元素 → removed
        GSArray removed = new GSArray();
        for (int i = 0; i < deleteCount; i++) {
            GSValue v = (GSValue) arr.members.get(Integer.toString(start + i));
            if (v != null) removed.members.put(Integer.toString(i), v);
        }

        // 2. 读出尾部元素（start+deleteCount 之后的部分）
        int tailCount = len - start - deleteCount;
        GSValue[] tail = new GSValue[tailCount];
        for (int i = 0; i < tailCount; i++) {
            tail[i] = (GSValue) arr.members.get(Integer.toString(start + deleteCount + i));
        }

        // 3. 删除从 start 到原末尾的所有 key
        for (int i = start; i < len; i++) {
            arr.members.remove(Integer.toString(i));
        }

        // 4. 写入新插入项
        for (int i = 0; i < insertCount; i++) {
            arr.members.put(Integer.toString(start + i), (GSValue) args.get(3 + i));
        }

        // 5. 写回尾部
        for (int i = 0; i < tailCount; i++) {
            if (tail[i] != null) {
                arr.members.put(Integer.toString(start + insertCount + i), tail[i]);
            }
        }
        return removed;
    }
};
```

`getProperty` 中追加（[GSArray.java:221](file:///E:/JProjects/gscript/src/main/java/org/gscript/vm/value/GSArray.java#L221)）：

```java
if ("splice".equals(name)) return SPLICE;
```

### 测试用例

```javascript
// array_splice.test.script
function check(label, actual, expected) {
    var ok = actual == expected;
    console.log((ok ? "PASS" : "FAIL") + " " + label + " got=" + actual + " want=" + expected);
}

// 1. 删除
var a = [1, 2, 3, 4, 5];
var r = a.splice(1, 2);           // r=[2,3], a=[1,4,5]
check("splice删除-返回长度", r.length, 2);
check("splice删除-r[0]", r[0], 2);
check("splice删除-a长度", a.length, 3);
check("splice删除-a[1]", a[1], 4);

// 2. 插入（deleteCount=0）
var b = [1, 2, 3];
b.splice(1, 0, 9, 9);             // b=[1,9,9,2,3]
check("splice插入-长度", b.length, 5);
check("splice插入-b[1]", b[1], 9);
check("splice插入-b[3]", b[3], 2);

// 3. 替换（deleteCount=insertCount）
var c = [1, 2, 3];
c.splice(1, 1, 8);                // c=[1,8,3]
check("splice替换-c[1]", c[1], 8);
check("splice替换-长度", c.length, 3);

// 4. 负索引
var d = [1, 2, 3];
d.splice(-1, 1);                  // d=[1,2]
check("splice负索引-长度", d.length, 2);

// 5. deleteCount 缺省（删到末尾）
var e = [1, 2, 3, 4];
e.splice(2);                      // e=[1,2]
check("splice缺省deleteCount-长度", e.length, 2);

// 6. 清空
var f = [1, 2, 3];
f.splice(0);                      // f=[]
check("splice清空-长度", f.length, 0);
```

---

## 2. GSArray.length 可写 — 必需

### 现状

[GSArray.java:222](file:///E:/JProjects/gscript/src/main/java/org/gscript/vm/value/GSArray.java#L222) `length` 仅 `getProperty` 返回 `new GSInt(computeLength(members))`，无 `setProperty` 拦截。`GSObject.setProperty`（[GSObject.java:52](file:///E:/JProjects/gscript/src/main/java/org/gscript/vm/value/GSObject.java#L52)）直接 `members.put(name, value)`，导致 `arr.length = 0` 会把 `"length"` 当普通属性写入，不影响数组实际内容。

天劫 timer_util 的 `clearAllTimers` 需用 `TIMER_REGISTRY.length = 0` 批量清空（比 `TIMER_REGISTRY = []` 赋新数组更安全，避免外部引用失效）。

### 请示实现

在 GSArray 中**重写 `setProperty`**，拦截 `"length"` 赋值做截断/扩容，其余属性走 `super.setProperty`。

**文件**：`src/main/java/org/gscript/vm/value/GSArray.java`

```java
/**
 * 重写 setProperty：拦截 "length" 赋值，按 JS 语义截断或扩容数组。
 * 其余属性（数字索引等）走父类直接 put。
 */
public void setProperty(String name, GSValue value) {
    if ("length".equals(name)) {
        int newLen = value.toIntValue();
        if (newLen < 0) newLen = 0;
        int curLen = computeLength(members);
        if (newLen < curLen) {
            // 截断：删除 newLen ~ curLen-1 的元素
            for (int i = newLen; i < curLen; i++) {
                members.remove(Integer.toString(i));
            }
        } else if (newLen > curLen) {
            // 扩容：补 GSNull（JS 语义为 empty slot/hole，gscript 无 hole 概念，用 GSNull 近似）
            for (int i = curLen; i < newLen; i++) {
                members.put(Integer.toString(i), GSNull.NULL);
            }
        }
        return;
    }
    super.setProperty(name, value);
}
```

> **兼容性说明**：现有数组元素赋值 `arr[i] = x` 走 `OP_PUTFIELD` → `setProperty("i", x)`，name 为数字字符串，不被 `"length".equals(name)` 命中，正常走 `super.setProperty`。无回归风险。

### 测试用例

```javascript
// array_length_set.test.script
function check(label, actual, expected) {
    var ok = actual == expected;
    console.log((ok ? "PASS" : "FAIL") + " " + label + " got=" + actual + " want=" + expected);
}

// 1. 截断
var a = [1, 2, 3, 4, 5];
a.length = 3;
check("length截断-长度", a.length, 3);
check("length截断-a[2]", a[2], 3);
check("length截断-a[3]已删", a[3], null);

// 2. 扩容补 null
var b = [1, 2];
b.length = 4;
check("length扩容-长度", b.length, 4);
check("length扩容-b[0]", b[0], 1);
check("length扩容-b[2]为null", b[2], null);

// 3. 清空
var c = [1, 2, 3];
c.length = 0;
check("length清空-长度", c.length, 0);
check("length清空-c[0]为null", c[0], null);

// 4. 设为同长（无操作）
var d = [1, 2, 3];
d.length = 3;
check("length同长-长度", d.length, 3);
check("length同长-d[0]", d[0], 1);
```

---

## 3. GSObject.keys() — 可选建议

### 现状

[GSObject.java:37-44](file:///E:/JProjects/gscript/src/main/java/org/gscript/vm/value/GSObject.java#L37) `getProperty` 仅返回成员值，无 `keys()` 方法。天劫调试/日志场景需遍历对象属性（打印 `STATE_HANDLERS` 已注册状态、`TIMER_REGISTRY` 活动项）。

### 请示实现

新增静态共享 `GSNativeFunction KEYS`，返回属性名数组。**注意 own-property 优先**：成员中已有同名 key 时优先返回成员值（对标 JS 自有属性优先于原型方法）。

**文件**：`src/main/java/org/gscript/vm/value/GSObject.java`

需新增 import：`import java.util.Iterator;`

```java
/** keys(): 返回对象全部属性名数组（调试/遍历用，顺序不保证） */
private static final GSNativeFunction KEYS = new GSNativeFunction("keys") {
    public GSValue call(ArrayList args) {
        GSObject obj = (GSObject) args.get(0);
        GSArray result = new GSArray();
        int idx = 0;
        Iterator it = obj.members.keySet().iterator();
        while (it.hasNext()) {
            result.members.put(Integer.toString(idx), new GSString((String) it.next()));
            idx++;
        }
        return result;
    }
};
```

`getProperty` 改为**成员优先**（[GSObject.java:37](file:///E:/JProjects/gscript/src/main/java/org/gscript/vm/value/GSObject.java#L37)）：

```java
public GSValue getProperty(String name) {
    GSValue value = (GSValue) members.get(name);
    if (value != null) return value;       // 自有属性优先
    if ("keys".equals(name)) return KEYS;  // 缺省才返回 keys 方法
    return GSNull.NULL;
}
```

> **顺序说明**：`HashMap.keySet()` 顺序不保证。若需有序可改用 `LinkedHashMap`（但 GSObject 当前用 `HashMap`，改动面大）。天劫场景对顺序无要求。
>
> **成员优先理由**：GSObject 成员名任意，可能存在名为 `"keys"` 的业务属性。成员优先保证业务属性不被 `keys()` 方法遮蔽（对标 JS 自有属性 > 原型方法）。这与 GSArray 不同——数组元素是数字 key，不会与方法名冲突，故 GSArray 检查方法名优先无妨。

### 测试用例

```javascript
// object_keys.test.script
function check(label, actual, expected) {
    var ok = actual == expected;
    console.log((ok ? "PASS" : "FAIL") + " " + label + " got=" + actual + " want=" + expected);
}

var o = {a: 1, b: 2, c: 3};
var k = o.keys();
check("keys-长度", k.length, 3);
check("keys-含a", k.indexOf("a") >= 0, true);
check("keys-含b", k.indexOf("b") >= 0, true);
check("keys-含c", k.indexOf("c") >= 0, true);

// 自有属性 "keys" 优先于 keys() 方法
var o2 = {keys: 99};
check("keys-自有属性优先", o2.keys, 99);
```

---

## 4. GSArray.forEach/map/filter — 可选建议（需决策）

### 需求

天劫脚本用 `for` 循环遍历数组可行但冗长，`arr.forEach(cb)` / `arr.map(cb)` / `arr.filter(cb)` 可提升可读性。

### ⚠️ 实现约束（关键）

`GSNativeFunction.call(ArrayList args)`（[GSNativeFunction.java:37](file:///E:/JProjects/gscript/src/main/java/org/gscript/vm/value/GSNativeFunction.java#L37)）**只收到 args，没有 interpreter 引用**。现有原生函数（push/charAt/...）都是纯数据操作，无需回调 gscript 函数。

而 forEach/map/filter 需在原生函数内**回调用户传入的 gscript 函数**。调用 gscript 函数必须经 `GSInterpreter.callFunction(GSFunction fn, ArrayList args)`（[GSInterpreter.java:1368](file:///E:/JProjects/gscript/src/main/java/org/gscript/vm/vm/GSInterpreter.java#L1368)）——GSFunction 对象自身无 `call` 方法（[GSFunction.java](file:///E:/JProjects/gscript/src/main/java/org/gscript/vm/value/GSFunction.java) 只有作用域操作，无独立调用入口）。

经核实，引擎**当前没有** thread-local 或 static 方式让原生函数获取当前 interpreter（`GSInterpreter` 无 `current()`/`getInstance()`/`ThreadLocal` 字段，仅 `Thread.currentThread() == workerThread` 内部判断）。

**结论**：1/2/3 的"加个静态 GSNativeFunction"模式**不适用于** forEach/map/filter。需开发者从以下两方案选择。

### 方案 A：新增 thread-local interpreter 访问机制 + 原生实现

在 GSInterpreter 增加 thread-local 当前实例，worker 线程执行时设置，原生函数通过 `GSInterpreter.current()` 取回并调用 `callFunction`。

**改动点**：

```java
// GSInterpreter.java 新增
private static final ThreadLocal currentInterpreter = new ThreadLocal();

/** 返回当前 worker 线程绑定的 interpreter（原生函数回调 gscript 用） */
public static GSInterpreter current() {
    return (GSInterpreter) currentInterpreter.get();
}
```

设置时机（二选一，推荐第 1 种）：
1. **worker 线程启动时设置一次**（GSInterpreter 与 workerThread 1:1 映射，native 函数只在 worker 执行）——在 worker Runnable 的 `run()` 首行 `currentInterpreter.set(GSInterpreter.this)`
2. 每次 `callFunctionDirect` / `evalDirect` 入口 set —— 更精确但开销略大

**forEach 原生实现**（map/filter 同理）：

```java
private static final GSNativeFunction FOR_EACH = new GSNativeFunction("forEach") {
    public GSValue call(ArrayList args) {
        GSArray arr = (GSArray) args.get(0);
        if (args.size() < 2) return GSNull.NULL;
        GSFunction cb = (GSFunction) args.get(1);
        GSInterpreter interp = GSInterpreter.current();   // 依赖方案 A 的 thread-local
        int len = computeLength(arr.members);
        ArrayList cbArgs = new ArrayList();
        for (int i = 0; i < len; i++) {
            GSValue elem = (GSValue) arr.members.get(Integer.toString(i));
            cbArgs.clear();
            cbArgs.add(null);                              // this 占位（OP_INVOKE 约定）
            cbArgs.add(elem != null ? elem : GSNull.NULL); // element
            cbArgs.add(new GSInt(i));                      // index
            cbArgs.add(arr);                               // array
            interp.callFunction(cb, cbArgs);               // worker 内走 callFunctionDirect
        }
        return GSNull.NULL;
    }
};

// map: 收集 cb 返回值到新数组
// filter: cb 返回 truthy 的元素收集到新数组
```

- **优点**：支持 `arr.forEach(cb)` 方法调用语法
- **缺点**：新增引擎基础设施（thread-local），改动面比 1/2/3 大；需评估对调试器/多 interpreter 实例的影响

### 方案 B：脚本层实现（零引擎改动）

在天劫侧新增 util 脚本，用 gscript 已有能力（闭包 + for 循环 + 函数参数）实现，**无需改引擎**。代价是函数式调用 `forEach(arr, cb)` 而非方法式 `arr.forEach(cb)`。

```javascript
// array_util.script（天劫侧新增，不进引擎）
function forEach(arr, cb) {
    for (var i = 0; i < arr.length; ++i) {
        cb(arr[i], i, arr);
    }
}
function map(arr, cb) {
    var r = [];
    for (var i = 0; i < arr.length; ++i) {
        r.push(cb(arr[i], i, arr));
    }
    return r;
}
function filter(arr, cb) {
    var r = [];
    for (var i = 0; i < arr.length; ++i) {
        if (cb(arr[i], i, arr)) r.push(arr[i]);
    }
    return r;
}
```

- **优点**：零引擎改动，立即可用，gscript 闭包已验证支持
- **缺点**：无方法语法；天劫脚本需用 `forEach(arr, cb)` 而非 `arr.forEach(cb)`

### 建议

鉴于：
- 1/2/3（splice/length/keys）已覆盖天劫优化的**全部硬需求**
- forEach/map/filter 在天劫计划中标注为"可选，仅提升可读性"，for 循环绕过方案可行
- 方案 A 引入引擎基础设施改动，收益与风险不对等

**推荐**：本轮引擎改动只做 1/2/3 三项；forEach/map/filter 走方案 B（天劫侧脚本实现）。若后续有更多原生方法需回调 gscript（如 `sort`/`find`/`reduce`），再统一引入 thread-local 机制（方案 A）一次性解决。

**请开发者决策**：4 采用方案 A（引入 thread-local + 原生实现）、方案 B（天劫脚本层实现，引擎不动）、还是本轮完全不做 4。

---

## 兼容性

1/2/3 均为**纯新增**，不修改已有语义：

| 改动 | 影响现有脚本 |
|------|------------|
| splice | 新增数组方法，不覆盖 push/pop 等 |
| length 可写 | 新增 setProperty 拦截，不影响现有 getProperty 只读访问与元素赋值 |
| keys() | 新增对象方法，自有属性优先保证不遮蔽业务属性 |

现有天劫脚本与 gscript 测试套件无需任何修改即可在新引擎上运行。

---

## 测试用例汇总

引擎改动后建议在 `src/main/resources/` 下新增以下测试脚本，纳入现有测试流程：

| 测试文件 | 覆盖 | 预期 |
|---------|------|------|
| `array_splice.test.script` | splice 删除/插入/替换/负索引/deleteCount缺省/清空 | 见 §1 |
| `array_length_set.test.script` | length 截断/扩容/清空/同长 | 见 §2 |
| `object_keys.test.script` | keys() 返回属性名/自有属性优先 | 见 §3 |
| `array_foreach_map_filter.test.script` | （仅当 4 选方案 A 时）回调签名与返回值 | 见 §4 方案 A |

测试通过后重新发布 `E:/JProjects/gscript/target/gscript-1.0-SNAPSHOT.jar`，天劫项目更新依赖后即可进入脚本优化阶段 2 实施。

---

## 参考索引

- 天劫侧完整计划：[gscript脚本优化计划_决策树状态机与定时器.md](file:///e:/IdeaProjects/S60-new/.trae/documents/gscript脚本优化计划_决策树状态机与定时器.md)（第九章为本文档的来源）
- 引擎静态共享模式先例：[gsstring-enhancement-and-type-conversion.md](file:///e:/JProjects/gscript/.trae/documents/gsstring-enhancement-and-type-conversion.md)（GSString 18 方法重构，同为 `private static final GSNativeFunction` 模式）
- OP_INVOKE args[0]=this 约定：[GSInterpreter.java:661-663](file:///E:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java#L661)
- GSNativeFunction 抽象：[GSNativeFunction.java](file:///E:/JProjects/gscript/src/main/java/org/gscript/vm/value/GSNativeFunction.java)
- callFunction 公开 API：[GSInterpreter.java:1368](file:///E:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java#L1368)
