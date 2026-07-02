#!/usr/bin/env python3
"""
Java 宿主与 gscript 解释器交互 API 端到端测试。

验证 6 个新增 API：
1. evalScript(String)  —— 执行 gscript 源码
2. getVariable(String) —— 读取全局变量
3. evalExpression(String) —— 求值表达式并返回结果
4. setVariable(String, Object) —— 注入 Java 值（自动包装为 GSValue）
5. toJavaObject() —— GSValue 递归转 Java 对象（含嵌套数组）
6. fromJavaObject(Object) —— Java Map/List 转 GSValue

运行：python tests/test_host_interaction.py
前提：mvn clean compile 已编译 target/classes
"""

import os
import subprocess

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.join(BASE_DIR, "..")
CLASSES_DIR = os.path.join(PROJECT_DIR, "target", "classes")
JAVA = (
    os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-9.0.4")
    + r"\bin\java.exe"
)

passed = 0
failed = 0


def run_hosttest():
    """运行 TestScript hosttest，返回 stdout 文本。"""
    cmd = [JAVA, "-cp", CLASSES_DIR, "org.gscript.TestScript", "hosttest", "hosttest"]
    result = subprocess.run(
        cmd, capture_output=True, text=True, timeout=30,
        encoding="utf-8", errors="replace",
    )
    return result.stdout, result.stderr


def parse_kv(stdout):
    """从 stdout 解析 key=value 行为 dict。"""
    kv = {}
    for line in stdout.splitlines():
        line = line.strip()
        if "=" in line and not line.startswith("Uncaught"):
            key, _, value = line.partition("=")
            kv[key.strip()] = value.strip()
    return kv


def assert_eq(label, actual, expected):
    global passed, failed
    if actual == expected:
        print(f"  [PASS] {label}")
        passed += 1
    else:
        print(f"  [FAIL] {label}")
        print(f"    expected: {expected!r}")
        print(f"    actual:   {actual!r}")
        failed += 1


def assert_true(label, cond, detail=""):
    global passed, failed
    if cond:
        print(f"  [PASS] {label}")
        passed += 1
    else:
        print(f"  [FAIL] {label} {detail}")
        failed += 1


print("=" * 60)
print("Java 宿主 ↔ gscript 交互 API 测试")
print("=" * 60)

stdout, stderr = run_hosttest()
kv = parse_kv(stdout)

print("\n--- stdout ---")
print(stdout, end="")
if stderr.strip():
    print("\n--- stderr (excerpt) ---")
    print("\n".join(stderr.splitlines()[:5]))

print("\n--- 断言 ---")

# 1. evalScript + getVariable
assert_eq("evalScript+getVariable x", kv.get("x"), "10")
assert_eq("evalScript+getVariable y", kv.get("y"), "20")

# 2. evalExpression（调用 gscript 定义的 add 函数）
assert_eq("evalExpression add(x,y)", kv.get("sum"), "30")

# 3. setVariable 注入 Java 值 + gscript console.log 读取
assert_true(
    "setVariable + console.log(z/greeting)",
    "100" in stdout and "hello" in stdout,
    f"stdout={stdout!r}",
)

# 4. toJavaObject 递归转换
assert_eq("toJavaObject obj.name", kv.get("obj.name"), "Alice")
assert_eq("toJavaObject obj.age", kv.get("obj.age"), "30")
assert_eq("toJavaObject obj.scores (嵌套数组)", kv.get("obj.scores"), "[90, 85, 95]")

# 5. fromJavaObject：Map/List → GSValue
assert_eq("fromJavaObject config.timeout", kv.get("config.timeout"), "5000")
assert_eq("fromJavaObject config.retries", kv.get("config.retries"), "3")
assert_eq("fromJavaObject tags[0] (数组索引)", kv.get("tags[0]"), "1")

# 6. 表达式出错 → GSNull.NULL
assert_eq("evalExpression 错误返回 null", kv.get("err_is_null"), "true")

# 7. 错误信息打印到 stdout（验证异常未被静默吞掉）
assert_true(
    "错误表达式打印 Uncaught Error",
    "Uncaught Error" in stdout,
    f"stdout={stdout!r}",
)

print()
print("=" * 60)
print(f"结果: {passed} passed, {failed} failed")
print("=" * 60)

if failed > 0:
    raise SystemExit(1)
