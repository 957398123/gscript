#!/usr/bin/env python3
"""
gscript attach 调试模式冒烟测试（socket 通信）。

验证方案 B 的两种模式：
  模式 1（waitForDebugger）：agent 先监听，VSCode 连接后启动解释器。
  模式 2（attachReady）：解释器先运行，VSCode 后附加。

模拟 DAP 客户端通过 TCP socket 连接端口 4711，验证完整流程：
  initialize → attach → setBreakpoints → configurationDone
  → stopped(断点/pause) → stackTrace → scopes → variables
  → continue → terminated（模式1）/ disconnect（模式2）

用法：
  模式 1:  先在另一终端运行 java -cp target/classes org.gscript.TestScript debug_attach_test debugagent
           然后 python tests/test_attach_smoke.py 1
  模式 2:  先在另一终端运行 java -cp target/classes org.gscript.TestScript debug_attach_runtime debugagent-attach
           然后 python tests/test_attach_smoke.py 2
"""

import json
import os
import socket
import sys
import time
import threading
import queue

PORT = 4711
HOST = "localhost"
# 项目根目录与脚本目录（用于 attach 路径映射）
PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESOURCES_DIR = os.path.join(PROJECT_ROOT, "src", "main", "resources")


class SocketDapClient:
    """通过 TCP socket 与 DAP 适配器通信。"""

    def __init__(self, host, port, retry_timeout=15):
        # 重试连接：Java 进程可能尚未开始监听
        deadline = time.time() + retry_timeout
        last_err = None
        while time.time() < deadline:
            try:
                self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                self.sock.settimeout(15)
                self.sock.connect((host, port))
                break
            except ConnectionRefusedError as e:
                last_err = e
                time.sleep(0.3)
        else:
            raise last_err
        self._seq = 0
        self._buf = b""
        self._event_q = queue.Queue()
        self._response_q = queue.Queue()
        self._reader = threading.Thread(target=self._read_loop, daemon=True)
        self._reader.start()

    def _read_loop(self):
        while True:
            try:
                data = self.sock.recv(4096)
                if not data:
                    break
                self._buf += data
                while True:
                    msg, self._buf = self._extract_message(self._buf)
                    if msg is None:
                        break
                    self._dispatch(msg)
            except Exception:
                break

    @staticmethod
    def _extract_message(buf):
        """从缓冲区提取一条 DAP 消息（Content-Length 分帧）。"""
        header_end = buf.find(b"\r\n\r\n")
        if header_end == -1:
            return None, buf
        headers = buf[:header_end].decode("utf-8")
        body_start = header_end + 4
        content_length = 0
        for line in headers.split("\r\n"):
            if line.lower().startswith("content-length:"):
                content_length = int(line.split(":", 1)[1].strip())
        if len(buf) - body_start < content_length:
            return None, buf
        body = buf[body_start:body_start + content_length]
        rest = buf[body_start + content_length:]
        return json.loads(body.decode("utf-8")), rest

    def _dispatch(self, msg):
        if msg.get("type") == "response":
            self._response_q.put(msg)
        elif msg.get("type") == "event":
            self._event_q.put(msg)

    def send_request(self, command, args=None):
        self._seq += 1
        msg = {
            "seq": self._seq,
            "type": "request",
            "command": command,
        }
        if args:
            msg["arguments"] = args
        data = json.dumps(msg).encode("utf-8")
        frame = f"Content-Length: {len(data)}\r\n\r\n".encode("utf-8") + data
        self.sock.sendall(frame)
        # 等待响应
        while True:
            resp = self._response_q.get(timeout=10)
            if resp.get("request_seq") == self._seq:
                return resp

    def wait_event(self, event_name, timeout=15):
        """等待指定事件，跳过其他事件。"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                evt = self._event_q.get(timeout=deadline - time.time())
                if evt.get("event") == event_name:
                    return evt
            except queue.Empty:
                break
        return None

    def close(self):
        try:
            self.sock.close()
        except Exception:
            pass


def test_mode1():
    """模式 1：waitForDebugger。验证断点命中 + continue + terminated。"""
    print("=== 模式 1 冒烟测试（waitForDebugger）===")
    passed = 0
    failed = 0

    def check(desc, cond):
        nonlocal passed, failed
        if cond:
            print(f"  [PASS] {desc}")
            passed += 1
        else:
            print(f"  [FAIL] {desc}")
            failed += 1

    client = SocketDapClient(HOST, PORT)

    # 1. initialize
    resp = client.send_request("initialize", {
        "clientID": "test", "adapterID": "gscript",
        "linesStartAt1": True, "columnsStartAt1": True,
        "pathFormat": "path",
    })
    check("initialize 成功", resp.get("success", False))

    # 2. attach（attach 模式，带路径映射：本地 resources 目录 → 远程相对路径）
    resp = client.send_request("attach", {
        "localRoot": RESOURCES_DIR,
        "remoteRoot": "",
        "stopOnEntry": False,
    })
    check("attach 成功", resp.get("success", False))

    # 3. setBreakpoints（debug_attach_test.script 第 30 行：var fact5 = calculateFactorial(5);
    #    选顶层代码避免递归函数内断点反复触发）
    script_path = os.path.join(RESOURCES_DIR, "debug_attach_test.script")
    resp = client.send_request("setBreakpoints", {
        "source": {"path": script_path},
        "breakpoints": [{"line": 30}],
    })
    check("setBreakpoints 成功", resp.get("success", False))

    # 4. configurationDone → 启动解释器
    resp = client.send_request("configurationDone", {})
    check("configurationDone 成功", resp.get("success", False))

    # 5. 等待 stopped 事件（断点命中）
    stopped = client.wait_event("stopped", timeout=10)
    check("收到 stopped 事件", stopped is not None)
    if stopped:
        reason = stopped.get("body", {}).get("reason", "")
        check("stopped 原因是 breakpoint", reason == "breakpoint")

        # 6. stackTrace
        resp = client.send_request("stackTrace", {"threadId": 1})
        frames = resp.get("body", {}).get("stackFrames", [])
        check("stackTrace 返回帧", len(frames) > 0)
        if frames:
            check("栈顶帧行号=30", frames[0].get("line") == 30)

        # 7. continue
        resp = client.send_request("continue", {"threadId": 1})
        check("continue 成功", resp.get("success", False))

    # 8. 等待 terminated
    terminated = client.wait_event("terminated", timeout=10)
    check("收到 terminated 事件", terminated is not None)

    client.close()
    print(f"\n=== 模式 1 结果: {passed} passed, {failed} failed ===")
    return failed == 0


def test_mode2():
    """模式 2：attachReady。验证附加后暂停 + 断点 + disconnect。"""
    print("=== 模式 2 冒烟测试（attachReady）===")
    passed = 0
    failed = 0

    def check(desc, cond):
        nonlocal passed, failed
        if cond:
            print(f"  [PASS] {desc}")
            passed += 1
        else:
            print(f"  [FAIL] {desc}")
            failed += 1

    client = SocketDapClient(HOST, PORT)

    # 1. initialize
    resp = client.send_request("initialize", {
        "clientID": "test", "adapterID": "gscript",
        "linesStartAt1": True, "columnsStartAt1": True,
        "pathFormat": "path",
    })
    check("initialize 成功", resp.get("success", False))

    # 2. attach（attachReady 模式，pauseOnAttach=true，解释器应自动暂停）
    resp = client.send_request("attach", {
        "localRoot": RESOURCES_DIR,
        "remoteRoot": "",
        "stopOnEntry": False,
    })
    check("attach 成功", resp.get("success", False))

    # 3. setBreakpoints（debug_attach_runtime.script 第 34 行：console.log in runLongTask）
    script_path = os.path.join(RESOURCES_DIR, "debug_attach_runtime.script")
    resp = client.send_request("setBreakpoints", {
        "source": {"path": script_path},
        "breakpoints": [{"line": 34}],
    })
    check("setBreakpoints 成功", resp.get("success", False))

    # 4. configurationDone
    resp = client.send_request("configurationDone", {})
    check("configurationDone 成功", resp.get("success", False))

    # 5. 等待 stopped 事件（pauseOnAttach 触发的 pause）
    stopped = client.wait_event("stopped", timeout=10)
    check("收到 stopped 事件（attach 暂停）", stopped is not None)
    if stopped:
        reason = stopped.get("body", {}).get("reason", "")
        check(f"stopped 原因={reason}（breakpoint 或 pause）",
              reason in ("breakpoint", "pause"))

        # 6. stackTrace
        resp = client.send_request("stackTrace", {"threadId": 1})
        frames = resp.get("body", {}).get("stackFrames", [])
        check("stackTrace 返回帧", len(frames) > 0)

        # 7. continue → 应命中断点
        resp = client.send_request("continue", {"threadId": 1})
        check("continue 成功", resp.get("success", False))

        # 8. 等待断点命中
        stopped2 = client.wait_event("stopped", timeout=10)
        check("continue 后收到 stopped（断点命中）", stopped2 is not None)
        if stopped2:
            reason2 = stopped2.get("body", {}).get("reason", "")
            check("断点原意是 breakpoint", reason2 == "breakpoint")

            # 9. scopes + variables
            resp = client.send_request("stackTrace", {"threadId": 1})
            frames = resp.get("body", {}).get("stackFrames", [])
            if frames:
                frame_id = frames[0].get("id")
                resp = client.send_request("scopes", {"frameId": frame_id})
                scopes = resp.get("body", {}).get("scopes", [])
                check("scopes 返回", len(scopes) > 0)
                if scopes:
                    var_ref = scopes[0].get("variablesReference", 0)
                    resp = client.send_request("variables", {"variablesReference": var_ref})
                    variables = resp.get("body", {}).get("variables", [])
                    check("variables 返回", len(variables) > 0)

    # 10. disconnect（attach 模式：分离不终止）
    resp = client.send_request("disconnect", {})
    check("disconnect 成功", resp.get("success", False))

    client.close()
    print(f"\n=== 模式 2 结果: {passed} passed, {failed} failed ===")
    return failed == 0


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "1"
    # SocketDapClient 内部重试连接，无需预先探测端口
    # （探测会消耗 Java 的唯一 accept()，导致测试连接无法被接受）
    try:
        if mode == "1":
            ok = test_mode1()
        else:
            ok = test_mode2()
    except ConnectionRefusedError:
        print(f"错误: 端口 {PORT} 无监听，请先启动 debugagent/debugagent-attach 模式")
        sys.exit(1)
    sys.exit(0 if ok else 1)
