#!/usr/bin/env python3
"""
gscript 调试模式未连接测试。

验证 debugMode=true 但 VSCode 未连接时，eval 正常执行（JS "DevTools 未连接" 语义）：
  1. enableDebugMode 启用调试模式
  2. startAttachListener 后台监听（但无 VSCode 连接）
  3. eval 正常执行，不阻塞（debugController 为 null，prepareDebugEntry 跳过 requestEntryStop）
  4. stdout 输出正确，Java 进程正常退出

这验证了 debugMode 与 debugController 解耦：debugMode=true 但 controller=null 时
eval 正常运行，不会因等待调试器而阻塞。

运行：python tests/test_debug_mode_unconnected.py
前提：mvn clean package -DskipTests
"""

import os
import sys
import subprocess

from dap_client import ensure_gclass, start_java, CLASSES_DIR, JAVA

state = {"passed": 0, "failed": 0}


def check(desc, cond):
    if cond:
        print(f"  [PASS] {desc}")
        state["passed"] += 1
    else:
        print(f"  [FAIL] {desc}")
        state["failed"] += 1


def test_debug_mode_unconnected():
    """测试 debugMode=true 但 VSCode 未连接：eval 正常执行不阻塞。"""
    print("=== 测试: debugMode=true 但 VSCode 未连（eval 正常执行）===")
    ensure_gclass("debug_attach_test")
    proc = start_java("debug_attach_test", "debugagent-eval")
    try:
        # 不连接 VSCode，等待 Java 进程自行完成（3 次 eval + 2 次 sleep ≈ 2-3 秒）
        try:
            out, err = proc.communicate(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()
            out, err = proc.communicate(timeout=5)
            check("Java 进程未超时退出（可能因等待 VSCode 阻塞）", False)

        # 1. Java 进程正常退出（未因等待 VSCode 阻塞）
        check("Java 进程正常退出（未阻塞）", proc.returncode == 0)

        # 2. stderr 含 enableDebugMode 标志
        check("stderr 含 'enableDebugMode 已启用'", "enableDebugMode 已启用" in err)

        # 3. stderr 含 notifyScriptCompleted 调用（脚本执行完毕）
        check("stderr 含 '所有 eval 执行结束'", "所有 eval 执行结束" in err)

        # 4. stdout 含脚本输出（eval 正常执行）
        check("stdout 含 '5! = 120'（gclass eval 正常）", "5! = 120" in out)
        check("stdout 含 'second eval'（evalScript 正常）", "second eval" in out)

        # 5. stdout 含 attach 测试结束标志
        check("stdout 含 'attach 调试测试结束'", "attach 调试测试结束" in out)

    finally:
        if proc.poll() is None:
            proc.kill()
            try:
                proc.wait(timeout=5)
            except Exception:
                pass


if __name__ == "__main__":
    test_debug_mode_unconnected()
    print(f"\n=== 结果: {state['passed']} passed, {state['failed']} failed ===")
    sys.exit(0 if state["failed"] == 0 else 1)
