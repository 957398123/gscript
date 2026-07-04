#!/usr/bin/env python3
"""
gscript 调试模式 evalExpression 测试。

验证 evalExpression 在 debug 模式下的行为：
  1. evalExpression 入口断第一行（reason=entry）
  2. source 请求返回 "return (expr);" 形式的源码
  3. 源码内容含原始表达式文本

debugAgentEval 第三次 eval 是 evalExpression("1 + 2")，
sourcePath=eval-2.script，源码应为 "return (1 + 2);"。

运行：python tests/test_debug_mode_eval_expression.py
前提：mvn clean package -DskipTests
"""

import sys
import subprocess

from dap_client import (
    check, ensure_gclass, start_java, SocketDapClient,
    HOST, PORT, RESOURCES_DIR,
)

state = {"passed": 0, "failed": 0}


def test_debug_mode_eval_expression():
    """测试 evalExpression 在 debug 模式下断第一行并返回 return 源码。"""
    print("=== 测试: evalExpression 在 debug 模式（source 含 return）===")
    ensure_gclass("debug_attach_test")
    proc = start_java("debug_attach_test", "debugagent-eval")
    try:
        client = SocketDapClient(HOST, PORT)

        # initialize + attach + configurationDone
        client.send_request("initialize", {
            "clientID": "test", "adapterID": "gscript",
            "linesStartAt1": True, "columnsStartAt1": True, "pathFormat": "path",
        })
        client.send_request("attach", {
            "localRoot": RESOURCES_DIR, "remoteRoot": "", "stopOnEntry": False,
        })
        client.send_request("configurationDone", {})

        # 循环 continue，寻找 eval-2.script（evalExpression）的 stopped
        found_eval_expr = False
        max_iterations = 15
        while max_iterations > 0:
            stopped = client.wait_event("stopped", timeout=10)
            if stopped is None:
                break
            reason = stopped.get("body", {}).get("reason", "")
            check(state, f"stopped reason=entry（{reason}）", reason == "entry")

            # 获取 stackTrace 提取 sourcePath
            resp = client.send_request("stackTrace", {"threadId": 1})
            frames = resp.get("body", {}).get("stackFrames", [])
            if frames:
                src = frames[0].get("source", {})
                src_name = src.get("name", "")
                src_ref = src.get("sourceReference", 0)

                # 如果是 eval-2.script（evalExpression），验证源码含 return
                if src_name == "eval-2.script" and src_ref > 0:
                    found_eval_expr = True
                    resp = client.send_request("source", {"sourceReference": src_ref, "source": src})
                    content = resp.get("body", {}).get("content", "")
                    check(state, "evalExpression source 内容非空", len(content) > 0)
                    check(state, f"evalExpression source 含 'return'（内容: {content.strip()!r}）", "return" in content)
                    check(state, f"evalExpression source 含表达式 '1 + 2'", "1 + 2" in content)

            client.send_request("continue", {"threadId": 1})
            max_iterations -= 1

        check(state, "找到 evalExpression 的 stopped（eval-2.script）", found_eval_expr)

        # 等待 terminated
        terminated = client.wait_event("terminated", timeout=10)
        check(state, "收到 terminated 事件", terminated is not None)

        client.close()

        try:
            out, err = proc.communicate(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            out, err = proc.communicate(timeout=5)
        check(state, "Java 进程正常退出", proc.returncode == 0)

    finally:
        if proc.poll() is None:
            proc.kill()
            try:
                proc.wait(timeout=5)
            except Exception:
                pass


if __name__ == "__main__":
    test_debug_mode_eval_expression()
    print(f"\n=== 结果: {state['passed']} passed, {state['failed']} failed ===")
    sys.exit(0 if state["failed"] == 0 else 1)
