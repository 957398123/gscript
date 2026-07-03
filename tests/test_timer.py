#!/usr/bin/env python3
"""
定时器 API（setTimeout/setInterval/clearTimeout/clearInterval）端到端测试。

验证：
- setTimeout 基本触发、传参、延迟顺序、0 延迟、闭包、嵌套
- setInterval 多次触发、clearInterval 终止
- clearTimeout 取消未触发的回调
- 事件循环退出条件（所有 setTimeout 完成 + setInterval cancel）

运行：python tests/test_timer.py
前提：mvn clean compile 已编译 target/classes
"""

import os
import subprocess
import sys

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.join(BASE_DIR, "..")
CLASSES_DIR = os.path.join(PROJECT_DIR, "target", "classes")
JAVA = (
    os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-9.0.4")
    + r"\bin\java.exe"
)

passed = 0
failed = 0


def run_timer_test():
    """运行 TestScript timer_test run，返回 (stdout, stderr, returncode)。"""
    cmd = [JAVA, "-cp", CLASSES_DIR, "org.gscript.TestScript", "timer_test", "run"]
    result = subprocess.run(
        cmd, capture_output=True, text=True, timeout=30,
        encoding="utf-8", errors="replace",
    )
    return result.stdout, result.stderr, result.returncode


def extract_out_lines(stdout):
    """提取所有 OUT: 开头的行内容（去掉 OUT: 前缀）。"""
    lines = []
    for line in stdout.splitlines():
        line = line.strip()
        if line.startswith("OUT:"):
            lines.append(line[4:])
    return lines


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
        print(f"  [FAIL] {label}  {detail}")
        failed += 1


def main():
    global passed, failed

    print("=== timer_test ===")
    stdout, stderr, rc = run_timer_test()

    # 进程退出码
    assert_eq("exit code 0", rc, 0)

    # 无未捕获异常
    assert_true("no uncaught error", "Uncaught Error" not in stderr,
                f"stderr: {stderr[:200]}")

    out = extract_out_lines(stdout)

    # 完整预期顺序（同步代码 + 事件循环回调）
    expected = [
        "cancel_ok", "main_end",
        "zero", "short", "args=11,22", "tick=1", "closure=modified",
        "outer", "tick=2", "inner", "tick=3", "cleared", "long",
    ]
    assert_eq("output line count", len(out), len(expected))
    assert_eq("full output order", out, expected)

    # SHOULD_NOT_PRINT 不应出现（clearTimeout 生效）
    assert_true("cancelled callback not fired", "SHOULD_NOT_PRINT" not in stdout)

    # 同步代码：cancel_ok 在 main_end 之前
    assert_true("sync: cancel_ok before main_end",
                out.index("cancel_ok") < out.index("main_end"))

    # 主脚本结束在所有回调之前
    assert_eq("main_end is 2nd line", out[1], "main_end")

    # 0 延迟是第一个回调
    assert_eq("zero delay is first callback", out[2], "zero")

    # 短延迟先于长延迟
    assert_true("short before long",
                out.index("short") < out.index("long"))

    # 传参正确
    assert_eq("setTimeout args passed", out[4], "args=11,22")

    # 闭包变量：回调看到修改后的值
    assert_eq("closure sees modified value", out[6], "closure=modified")

    # 嵌套：outer 先于 inner
    assert_true("outer before inner",
                out.index("outer") < out.index("inner"))

    # setInterval tick 递增
    tick_lines = [l for l in out if l.startswith("tick=")]
    assert_eq("setInterval tick count", tick_lines, ["tick=1", "tick=2", "tick=3"])

    # cleared 在 tick=3 之后
    assert_true("cleared after tick=3",
                out.index("tick=3") < out.index("cleared"))

    # long 是最后一个
    assert_eq("long is last", out[-1], "long")

    print(f"\n=== 结果: {passed} passed, {failed} failed ===")
    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()
