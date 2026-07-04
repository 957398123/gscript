#!/usr/bin/env python3
"""
验证 pause on exceptions 机制（DebugController.checkException）。

流程：
  1. 编译 debug_exception_test gclass（含会抛 "function not exist" 的脚本）
  2. 启动 debugagent-waitattach 模式（阻塞等 VSCode 连接，确保 controller 就位后再 eval）
  3. attach + setExceptionBreakpoints(filters=["uncaught"])
  4. configurationDone 唤醒主线程 → eval 入口断（reason=entry）
  5. continue → 脚本抛异常 → stopped(reason=exception) → 验证 stackTrace 显示调用栈
  6. continue → 异常传播到顶层（只挂起一次，不再二次挂起）→ 打印增强后的错误信息
  7. terminated

关键：用 waitattach 模式而非 eval 模式——waitattach 阻塞主线程直到 VSCode 连接 +
configurationDone，保证 pauseOnException=true 在 eval 前就位。eval 模式（startAttachListener
非阻塞）有时序竞态：异常可能在 controller 设置前抛出，checkException 不被调用。

前提：mvn clean package -DskipTests
"""

import sys
import subprocess

from dap_client import (
    check, ensure_gclass, start_java, SocketDapClient,
    HOST, PORT,
)

state = {"passed": 0, "failed": 0}


def test_pause_on_exception():
    print("=== 测试: pause on exceptions（异常处挂起 + 调用栈）===")
    ensure_gclass("debug_exception_test")
    proc = start_java("debug_exception_test", "debugagent-waitattach")
    try:
        client = SocketDapClient(HOST, PORT)

        # 1. initialize
        resp = client.send_request("initialize", {
            "clientID": "test", "adapterID": "gscript",
            "linesStartAt1": True, "columnsStartAt1": True, "pathFormat": "path",
        })
        check(state, "initialize 成功", resp.get("success", False))

        # 2. attach（创建 controller 并共享给解释器）
        resp = client.send_request("attach", {
            "localRoot": "", "remoteRoot": "", "stopOnEntry": False,
        })
        check(state, "attach 成功", resp.get("success", False))

        # 3. setExceptionBreakpoints（勾选 uncaught exceptions → pauseOnException=true）
        resp = client.send_request("setExceptionBreakpoints", {
            "filters": ["uncaught"],
        })
        check(state, "setExceptionBreakpoints 成功", resp.get("success", False))

        # 4. configurationDone（唤醒 waitattach 主线程，开始 eval）
        client.send_request("configurationDone", {})

        # 5. 等待第一次 stopped（entry，eval 入口断）
        stopped = client.wait_event("stopped", timeout=15)
        check(state, "收到第一次 stopped（entry）", stopped is not None)
        if stopped:
            reason = stopped.get("body", {}).get("reason", "")
            check(state, "第一次 stopped 原因 = entry", reason == "entry")

        # 6. continue → 脚本执行抛异常 → 应收到 exception stopped
        client.send_request("continue", {"threadId": 1})
        stopped = client.wait_event("stopped", timeout=15)
        check(state, "收到第二次 stopped（exception）", stopped is not None)
        if stopped:
            reason = stopped.get("body", {}).get("reason", "")
            check(state, "第二次 stopped 原因 = exception", reason == "exception")

            # 7. stackTrace 验证调用栈（应在抛异常点挂起，callStack 完整）
            resp = client.send_request("stackTrace", {"threadId": 1})
            frames = resp.get("body", {}).get("stackFrames", [])
            check(state, "exception stackTrace 有帧", len(frames) > 0)
            if frames:
                # 栈顶帧应在抛异常的行（badCall 函数里的 f()，第 3 行）
                top = frames[0]
                top_line = top.get("line", 0)
                top_name = top.get("name", "")
                check(state, "栈顶帧行号 > 0", top_line > 0)
                check(state, "栈顶帧函数名 = badCall", top_name == "badCall")
                # 应有 caller 帧（badCall 的调用者 = <anonymous>，第 6 行）
                check(state, "调用栈深度 >= 2（badCall + 顶层）", len(frames) >= 2)

        # 8. continue → 异常传播到顶层（只挂起一次，不再二次挂起）→ terminated
        #    waitattach 模式只做 1 次 eval，异常打印后 eval 返回，notifyScriptCompleted
        max_stops = 10
        while max_stops > 0:
            client.send_request("continue", {"threadId": 1})
            evt = client.wait_event("stopped", timeout=5)
            if evt is None:
                break
            max_stops -= 1

        # 9. 等待 terminated
        terminated = client.wait_event("terminated", timeout=10)
        check(state, "收到 terminated 事件", terminated is not None)

        client.close()

        # 10. 读取 stdout，验证增强后的错误信息
        try:
            out, err = proc.communicate(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            out, err = proc.communicate(timeout=5)

        check(state, "stdout 含 'Uncaught Error'", "Uncaught Error" in out)
        check(state, "stdout 含 'function not exist'", "function not exist" in out)
        check(state, "stdout 含调用栈 'in badCall'", "in badCall" in out)
        check(state, "stdout 含文件名 'debug_exception_test.script'",
              "debug_exception_test.script" in out)

    finally:
        if proc.poll() is None:
            proc.kill()
            try:
                proc.wait(timeout=5)
            except Exception:
                pass


if __name__ == "__main__":
    test_pause_on_exception()
    print(f"\n=== 结果: {state['passed']} passed, {state['failed']} failed ===")
    sys.exit(0 if state["failed"] == 0 else 1)
