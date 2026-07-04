#!/usr/bin/env python3
"""
gscript 调试模式多次 eval 测试。

验证 enableDebugMode 后，多次 eval 每次都在第一行断下：
  1. 第一次 eval：从 gclass 执行（sourcePath=debug_attach_test.script）
  2. 第二次 eval：evalScript（sourcePath=eval-1.script）
  3. 第三次 eval：evalExpression（sourcePath=eval-2.script）

每次 eval 入口都触发 entryStopRequested，VSCode 收到 stopped(reason=entry)。
验证每次 source 请求返回不同的源码内容（evalPathCounter 自增）。

运行：python tests/test_debug_mode_multi_eval.py
前提：mvn clean package -DskipTests
"""

import sys
import subprocess

from dap_client import (
    check, ensure_gclass, start_java, SocketDapClient,
    HOST, PORT, RESOURCES_DIR,
)

state = {"passed": 0, "failed": 0}


def test_debug_mode_multi_eval():
    """测试多次 eval 每次都断第一行，sourcePath 各不相同。"""
    print("=== 测试: 多次 eval 每次断第一行（sourcePath 自增）===")
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

        # 收集所有 stopped 事件（最多 3 次 eval + 可能的 terminated）
        stop_count = 0
        source_paths = []
        max_iterations = 15
        while max_iterations > 0 and stop_count < 3:
            stopped = client.wait_event("stopped", timeout=10)
            if stopped is None:
                break
            reason = stopped.get("body", {}).get("reason", "")
            check(state, f"第 {stop_count + 1} 次 stopped reason=entry", reason == "entry")

            # 获取 stackTrace 提取 sourcePath
            resp = client.send_request("stackTrace", {"threadId": 1})
            frames = resp.get("body", {}).get("stackFrames", [])
            if frames:
                src = frames[0].get("source", {})
                src_name = src.get("name", "")
                src_ref = src.get("sourceReference", 0)
                source_paths.append(src_name)
                check(state, f"第 {stop_count + 1} 次 sourceReference > 0", src_ref > 0)

                # source 请求验证内容
                if src_ref > 0:
                    resp = client.send_request("source", {"sourceReference": src_ref, "source": src})
                    content = resp.get("body", {}).get("content", "")
                    check(state, f"第 {stop_count + 1} 次 source 内容非空", len(content) > 0)

            client.send_request("continue", {"threadId": 1})
            stop_count += 1
            max_iterations -= 1

        check(state, f"收到多次 stopped（实际 {stop_count} 次，预期 >=2）", stop_count >= 2)

        # 验证 sourcePath 各不相同（eval-N.script 自增）
        if stop_count >= 2:
            unique_paths = len(set(source_paths))
            check(state, f"sourcePath 各不相同（{source_paths}）", unique_paths == stop_count)

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
    test_debug_mode_multi_eval()
    print(f"\n=== 结果: {state['passed']} passed, {state['failed']} failed ===")
    sys.exit(0 if state["failed"] == 0 else 1)
