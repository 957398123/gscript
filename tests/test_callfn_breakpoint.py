#!/usr/bin/env python3
"""
回归测试：attach 模式下 callFunction 触发函数体内断点（虚拟源路径）。

复现用户场景：
  1. 宿主加载 gscript 脚本（eval），函数仅定义不调用
  2. VSCode attach，通过 stackTrace 获取虚拟源（sourceReference > 0）
  3. VSCode 用虚拟源路径设断点（source.path = sourcePath，带 sourceReference）
  4. 宿主 callFunction 回调该函数
  5. 断点应命中（reason=breakpoint）

Bug 根因：DapServer.handleSetBreakpoints 无条件 canonicalize(source.path)，
把虚拟路径 "/com/scripts/x.script" 或相对路径 "callfn_test.script" 在 Windows 上
解析为 "E:\\com\\scripts\\x.script" 或 "E:\\JProjects\\gscript\\callfn_test.script"，
与 frame.function.sourcePath 不匹配，断点永不命中。

修复：source.sourceReference > 0 时跳过 canonicalize/mapToRemotePath，path 原样使用。

运行：python tests/test_callfn_breakpoint.py
前提：mvn clean package -DskipTests
"""

import subprocess
import sys

from dap_client import (
    check, ensure_gclass, start_java, SocketDapClient,
    HOST, PORT, RESOURCES_DIR,
)

state = {"passed": 0, "failed": 0}

# callfn_test.script 第 7 行：var sum = a + b;（addNumbers 函数体内）
BP_LINE = 7


def test_callfn_breakpoint():
    """测试 callFunction 触发函数体内断点（虚拟源 sourceReference>0）。"""
    print("=== 测试: callFunction 断点命中（虚拟源路径）===")
    ensure_gclass("callfn_test")
    proc = start_java("callfn_test", "debugagent-callfn")
    try:
        client = SocketDapClient(HOST, PORT)

        # 1. initialize + attach
        resp = client.send_request("initialize", {
            "clientID": "test", "adapterID": "gscript",
            "linesStartAt1": True, "columnsStartAt1": True, "pathFormat": "path",
        })
        check(state, "initialize 成功", resp.get("success", False))

        resp = client.send_request("attach", {
            "localRoot": RESOURCES_DIR, "remoteRoot": "", "stopOnEntry": False,
        })
        check(state, "attach 成功", resp.get("success", False))

        # 2. configurationDone → 唤醒主线程
        client.send_request("configurationDone", {})

        # 3. 等待首次 stopped（entryStopRequested 触发，eval 入口）
        stopped = client.wait_event("stopped", timeout=15)
        check(state, "收到首次 stopped（entry）", stopped is not None)
        if not stopped:
            return
        reason = stopped.get("body", {}).get("reason", "")
        check(state, "stopped 原因 = entry", reason == "entry")

        # 4. stackTrace 获取虚拟源的 sourceReference 和 path
        resp = client.send_request("stackTrace", {"threadId": 1})
        frames = resp.get("body", {}).get("stackFrames", [])
        check(state, "stackTrace 有帧", len(frames) > 0)
        if not frames:
            return

        src = frames[0].get("source", {})
        src_ref = src.get("sourceReference", 0)
        src_path = src.get("path", "")
        check(state, "sourceReference > 0（虚拟源）", src_ref > 0)
        check(state, "source.path 非空", bool(src_path))
        print(f"  [INFO] 虚拟源 path={src_path!r}, sourceReference={src_ref}")

        # 5. 关键：用虚拟源路径 + sourceReference 设断点（复现用户场景）
        #    修复前：canonicalize 把 "callfn_test.script" → "E:\\JProjects\\gscript\\callfn_test.script"
        #    导致 breakpoints Map key 与 frame.function.sourcePath 不匹配
        #    修复后：sourceReference>0 时 path 原样使用，断点命中
        resp = client.send_request("setBreakpoints", {
            "source": {
                "path": src_path,
                "sourceReference": src_ref,
                "name": src.get("name", ""),
            },
            "breakpoints": [{"line": BP_LINE}],
        })
        check(state, "setBreakpoints 成功", resp.get("success", False))

        # 6. continue → eval 完成（函数体内断点不应在 eval 时触发，因为函数未被调用）
        client.send_request("continue", {"threadId": 1})

        # 7. 等待 callFunction 触发的断点 stopped
        stopped2 = client.wait_event("stopped", timeout=15)
        check(state, "收到第二次 stopped（callFunction 断点）", stopped2 is not None)
        if stopped2:
            reason2 = stopped2.get("body", {}).get("reason", "")
            check(state, f"stopped 原因 = breakpoint (实际={reason2!r})",
                  reason2 == "breakpoint")

            # 8. stackTrace 验证断点行号与函数名
            resp = client.send_request("stackTrace", {"threadId": 1})
            frames2 = resp.get("body", {}).get("stackFrames", [])
            if frames2:
                line = frames2[0].get("line", 0)
                check(state, f"断点行号 = {BP_LINE} (实际={line})",
                      line == BP_LINE)
                fn_name = frames2[0].get("name", "")
                check(state, f"栈顶函数名 = addNumbers (实际={fn_name!r})",
                      fn_name == "addNumbers")

            # 9. continue → callFunction 返回 → terminated
            client.send_request("continue", {"threadId": 1})

        # 10. 等待 terminated
        terminated = client.wait_event("terminated", timeout=15)
        check(state, "收到 terminated 事件", terminated is not None)

        client.close()

        # 11. 验证 Java 进程输出
        try:
            out, err = proc.communicate(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            out, err = proc.communicate(timeout=5)
        check(state, "Java 进程正常退出", proc.returncode == 0)
        check(state, "stdout 含 'script loaded'", "script loaded" in out)
        check(state, "stderr 含 callFunction 调用日志", "callFunction(addNumbers" in err)
        check(state, "stderr 含返回值 30", "30" in err)

    finally:
        if proc.poll() is None:
            proc.kill()
            try:
                proc.wait(timeout=5)
            except Exception:
                pass


if __name__ == "__main__":
    test_callfn_breakpoint()
    print(f"\n=== 结果: {state['passed']} passed, {state['failed']} failed ===")
    sys.exit(0 if state["failed"] == 0 else 1)
