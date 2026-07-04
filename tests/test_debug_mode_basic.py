#!/usr/bin/env python3
"""
gscript 调试模式基础测试（enableDebugMode + startAttachListener）。

验证 enableDebugMode(DebugAgent) + startAttachListener() 的完整流程：
  1. Java 进程启动后，enableDebugMode 启用调试模式，startAttachListener 后台监听
  2. Python DAP 客户端 attach + configurationDone
  3. 后续 eval 入口断（reason="entry"，由 entryStopRequested 触发）
  4. stackTrace / source 请求验证（源码来自 registerSource 注册的内容）
  5. continue → 脚本执行完毕 → terminated 事件
  6. 验证 stdout 输出正确（脚本正常执行）

这是"VSCode 随时附加"场景的核心测试：宿主启用调试模式后，
VSCode 连接前的 eval 正常执行，连接后的 eval 在第一行断下。

运行：python tests/test_debug_mode_basic.py
前提：mvn clean package -DskipTests
"""

import sys
import subprocess

from dap_client import (
    check, ensure_gclass, start_java, SocketDapClient,
    HOST, PORT, RESOURCES_DIR,
)

state = {"passed": 0, "failed": 0}


def test_debug_mode_basic():
    """测试 enableDebugMode + startAttachListener：VSCode 连接后下次 eval 入口断。"""
    print("=== 测试: enableDebugMode + startAttachListener（VSCode 连接后 entry 断）===")
    ensure_gclass("debug_attach_test")
    proc = start_java("debug_attach_test", "debugagent-eval")
    try:
        client = SocketDapClient(HOST, PORT)

        # 1. initialize
        resp = client.send_request("initialize", {
            "clientID": "test", "adapterID": "gscript",
            "linesStartAt1": True, "columnsStartAt1": True, "pathFormat": "path",
        })
        check(state, "initialize 成功", resp.get("success", False))

        # 2. attach（stopOnEntry=False，依赖 entryStopRequested 触发）
        resp = client.send_request("attach", {
            "localRoot": RESOURCES_DIR, "remoteRoot": "", "stopOnEntry": False,
        })
        check(state, "attach 成功", resp.get("success", False))

        # 3. configurationDone
        client.send_request("configurationDone", {})

        # 4. 等待 stopped（entryStopRequested 触发的 entry 断点）
        stopped = client.wait_event("stopped", timeout=15)
        check(state, "收到 stopped（eval 入口断）", stopped is not None)
        if stopped:
            reason = stopped.get("body", {}).get("reason", "")
            check(state, "stopped 原因 = entry", reason == "entry")

            # 5. stackTrace 验证有帧
            resp = client.send_request("stackTrace", {"threadId": 1})
            frames = resp.get("body", {}).get("stackFrames", [])
            check(state, "stackTrace 有帧", len(frames) > 0)
            if frames:
                check(state, "栈顶帧行号 > 0", frames[0].get("line", 0) > 0)
                # 6. source.sourceReference > 0（attach 源码来自 registerSource）
                src = frames[0].get("source", {})
                src_ref = src.get("sourceReference", 0)
                check(state, "source.sourceReference > 0", src_ref > 0)
                if src_ref > 0:
                    resp = client.send_request("source", {"sourceReference": src_ref, "source": src})
                    content = resp.get("body", {}).get("content", "")
                    check(state, "source 请求返回源码内容", len(content) > 0)
                    check(state, "源码含脚本文本", "calculateFactorial" in content or "attach" in content or "second eval" in content)

                # 7. scopes + variables（验证可读变量）
                fid = frames[0].get("id")
                resp = client.send_request("scopes", {"frameId": fid})
                scopes = resp.get("body", {}).get("scopes", [])
                check(state, "scopes 返回", len(scopes) > 0)

        # 8. continue → 可能还有后续 eval 的 stopped，循环 continue 直到 terminated
        max_stops = 10  # 防止无限循环
        while max_stops > 0:
            client.send_request("continue", {"threadId": 1})
            evt = client.wait_event("stopped", timeout=5)
            if evt is None:
                break  # 没有 stopped，可能是 terminated
            max_stops -= 1

        # 9. 等待 terminated 事件
        terminated = client.wait_event("terminated", timeout=10)
        check(state, "收到 terminated 事件", terminated is not None)

        client.close()

        # 10. communicate 同时读 stdout+stderr 并等进程退出
        try:
            out, err = proc.communicate(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            out, err = proc.communicate(timeout=5)
        check(state, "Java 进程正常退出", proc.returncode == 0)

        # 11. 验证 stdout 输出（脚本正常执行）
        check(state, "stdout 含 '5! = 120'", "5! = 120" in out)
        check(state, "stdout 含 'second eval'", "second eval" in out)

    finally:
        if proc.poll() is None:
            proc.kill()
            try:
                proc.wait(timeout=5)
            except Exception:
                pass


if __name__ == "__main__":
    test_debug_mode_basic()
    print(f"\n=== 结果: {state['passed']} passed, {state['failed']} failed ===")
    sys.exit(0 if state["failed"] == 0 else 1)
