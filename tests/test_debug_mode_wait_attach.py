#!/usr/bin/env python3
"""
gscript 调试模式 waitForDebuggerAndAttach 测试（补充 test_wait_attach.py）。

专注验证 enableDebugMode + waitForDebuggerAndAttach 的核心语义：
  1. enableDebugMode(agent) 启用调试模式（主入口）
  2. waitForDebuggerAndAttach 阻塞主线程等 VSCode 连接
  3. 连接后主线程继续执行，首次 eval 命中 entryStopRequested
  4. stopped(reason=entry)，source 请求返回 gclass 源码
  5. continue → 脚本执行完毕 → terminated

与 test_wait_attach.py 的区别：本测试更简洁，专注验证 enableDebugMode API
和 entry 断点机制（而非 pauseOnAttach）。test_wait_attach.py 已全面覆盖
waitForDebuggerAndAttach 的完整流程（含 stdout/stderr 验证）。

运行：python tests/test_debug_mode_wait_attach.py
前提：mvn clean package -DskipTests
"""

import sys
import subprocess

from dap_client import (
    check, ensure_gclass, start_java, SocketDapClient,
    HOST, PORT, RESOURCES_DIR,
)

state = {"passed": 0, "failed": 0}


def test_debug_mode_wait_attach():
    """测试 enableDebugMode + waitForDebuggerAndAttach：首次 eval reason=entry。"""
    print("=== 测试: enableDebugMode + waitForDebuggerAndAttach（entry 断点）===")
    ensure_gclass("debug_attach_test")
    proc = start_java("debug_attach_test", "debugagent-waitattach")
    try:
        client = SocketDapClient(HOST, PORT)

        # initialize + attach + configurationDone
        resp = client.send_request("initialize", {
            "clientID": "test", "adapterID": "gscript",
            "linesStartAt1": True, "columnsStartAt1": True, "pathFormat": "path",
        })
        check(state, "initialize 成功", resp.get("success", False))

        resp = client.send_request("attach", {
            "localRoot": RESOURCES_DIR, "remoteRoot": "", "stopOnEntry": False,
        })
        check(state, "attach 成功", resp.get("success", False))

        # configurationDone 唤醒主线程
        client.send_request("configurationDone", {})

        # 等待 stopped（entryStopRequested 触发）
        stopped = client.wait_event("stopped", timeout=15)
        check(state, "收到 stopped", stopped is not None)
        if stopped:
            reason = stopped.get("body", {}).get("reason", "")
            check(state, "stopped 原因 = entry（enableDebugMode 触发）", reason == "entry")

            # stackTrace + source 验证
            resp = client.send_request("stackTrace", {"threadId": 1})
            frames = resp.get("body", {}).get("stackFrames", [])
            check(state, "stackTrace 有帧", len(frames) > 0)
            if frames:
                src = frames[0].get("source", {})
                src_ref = src.get("sourceReference", 0)
                check(state, "source.sourceReference > 0", src_ref > 0)
                if src_ref > 0:
                    resp = client.send_request("source", {"sourceReference": src_ref, "source": src})
                    content = resp.get("body", {}).get("content", "")
                    check(state, "source 返回 gclass 源码", "calculateFactorial" in content or "attach" in content)

        # continue → terminated
        client.send_request("continue", {"threadId": 1})
        terminated = client.wait_event("terminated", timeout=15)
        check(state, "收到 terminated 事件", terminated is not None)

        client.close()

        try:
            out, err = proc.communicate(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            out, err = proc.communicate(timeout=5)
        check(state, "Java 进程正常退出", proc.returncode == 0)
        check(state, "stdout 含 '5! = 120'", "5! = 120" in out)

    finally:
        if proc.poll() is None:
            proc.kill()
            try:
                proc.wait(timeout=5)
            except Exception:
                pass


if __name__ == "__main__":
    test_debug_mode_wait_attach()
    print(f"\n=== 结果: {state['passed']} passed, {state['failed']} failed ===")
    sys.exit(0 if state["failed"] == 0 else 1)
