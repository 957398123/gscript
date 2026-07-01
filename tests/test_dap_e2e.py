#!/usr/bin/env python3
"""
gscript DAP 调试器端到端测试。

模拟 DAP 客户端，通过 stdio 与 gscript 调试适配器通信，
验证完整调试流程：initialize → launch → setBreakpoints → configurationDone
→ stopped(断点) → stackTrace → scopes → variables → continue → terminated。

用法：
    python test_dap_e2e.py

前提：
    1. mvn package 已生成 target/gscript-1.0-SNAPSHOT.jar
    2. Java 9+ 在 PATH 或通过 JAVA_HOME 指定
"""

import json
import os
import subprocess
import sys
import time
import threading
import queue

# ---- 配置 ----
JAR_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "target", "gscript-1.0-SNAPSHOT.jar",
)
SCRIPT_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "src", "main", "resources", "debug_test.script",
)
JAVA_BIN = os.environ.get(
    "JAVA_HOME",
    r"C:\Program Files\Java\jdk-9.0.4",
) + r"\bin\java.exe"
BREAKPOINT_LINE = 52  # debug_test.script 第 52 行：var fib10 = fibonacci(10);

# ---- DAP 消息收发 ----

class DapClient:
    """DAP 客户端：通过子进程 stdin/stdout 与调试适配器通信。"""

    def __init__(self, cmd):
        self.proc = subprocess.Popen(
            cmd,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=0,
        )
        self._seq = 0
        self._event_q = queue.Queue()
        self._response_q = queue.Queue()
        # 后台线程持续读取 DAP 消息并分发到队列
        self._reader = threading.Thread(target=self._read_loop, daemon=True)
        self._reader.start()

    def _read_exact(self, n):
        """从 stdout 精确读取 n 字节。"""
        buf = b""
        while len(buf) < n:
            chunk = self.proc.stdout.read(n - len(buf))
            if not chunk:
                break
            buf += chunk
        return buf

    def _read_line(self):
        """读取一行（直到 \\n）。"""
        buf = b""
        while True:
            b = self.proc.stdout.read(1)
            if not b:
                break
            buf += b
            if b == b"\n":
                break
        return buf

    def _read_loop(self):
        """持续读取 DAP 消息，按 type 分发到响应/事件队列。"""
        try:
            while True:
                # 读 Headers
                headers = {}
                while True:
                    line = self._read_line()
                    if not line or line == b"\r\n":
                        break
                    try:
                        text = line.decode("utf-8").strip()
                        if ":" in text:
                            k, v = text.split(":", 1)
                            headers[k.strip().lower()] = v.strip()
                    except Exception:
                        pass
                content_length = int(headers.get("content-length", "0"))
                if content_length == 0:
                    continue
                body = self._read_exact(content_length)
                try:
                    msg = json.loads(body.decode("utf-8"))
                except Exception as e:
                    print(f"[解析错误] {e}", file=sys.stderr)
                    continue
                msg_type = msg.get("type")
                if msg_type == "response":
                    self._response_q.put(msg)
                elif msg_type == "event":
                    self._event_q.put(msg)
                else:
                    print(f"[未知消息类型] {msg}", file=sys.stderr)
        except Exception:
            pass

    def send(self, command, args=None):
        """发送 DAP 请求，返回响应（阻塞）。"""
        self._seq += 1
        msg = {
            "seq": self._seq,
            "type": "request",
            "command": command,
        }
        if args is not None:
            msg["arguments"] = args
        data = json.dumps(msg).encode("utf-8")
        frame = f"Content-Length: {len(data)}\r\n\r\n".encode("utf-8") + data
        self.proc.stdin.write(frame)
        self.proc.stdin.flush()
        # 等待响应（带超时）
        try:
            return self._response_q.get(timeout=15)
        except queue.Empty:
            raise TimeoutError(f"等待 '{command}' 响应超时")

    def wait_event(self, event_name, timeout=15):
        """等待指定事件（阻塞）。"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                ev = self._event_q.get(timeout=deadline - time.time())
                if ev.get("event") == event_name:
                    return ev
                # 其他事件放回（简单处理：忽略其他事件）
            except queue.Empty:
                break
        raise TimeoutError(f"等待事件 '{event_name}' 超时")

    def close(self):
        self.proc.stdin.close()
        try:
            self.proc.wait(timeout=3)
        except Exception:
            self.proc.kill()


# ---- 测试流程 ----

def main():
    jar = os.path.abspath(JAR_PATH)
    script = os.path.abspath(SCRIPT_PATH)
    if not os.path.exists(jar):
        print(f"[FAIL] jar 不存在: {jar}", file=sys.stderr)
        sys.exit(1)
    if not os.path.exists(script):
        print(f"[FAIL] 脚本不存在: {script}", file=sys.stderr)
        sys.exit(1)

    cmd = [JAVA_BIN, "-jar", jar, "--stdio"]
    print(f"[INFO] 启动调试适配器: {' '.join(cmd)}")
    client = DapClient(cmd)

    passed = 0
    failed = 0

    def check(name, cond, detail=""):
        nonlocal passed, failed
        if cond:
            passed += 1
            print(f"  [PASS] {name}")
        else:
            failed += 1
            print(f"  [FAIL] {name} {detail}")

    try:
        # 1. initialize
        print("\n[1] initialize")
        resp = client.send("initialize", {
            "clientID": "test",
            "adapterID": "gscript",
            "linesStartAt1": True,
            "columnsStartAt1": True,
        })
        check("initialize 响应成功", resp.get("success") is True)
        check("initialize 返回 supportsConfigurationDoneRequest",
              resp.get("body", {}).get("supportsConfigurationDoneRequest") is True)

        # 2. launch
        print("\n[2] launch")
        resp = client.send("launch", {
            "program": script,
            "stopOnEntry": False,
        })
        check("launch 响应成功", resp.get("success") is True)

        # 3. setBreakpoints
        print(f"\n[3] setBreakpoints (行 {BREAKPOINT_LINE})")
        resp = client.send("setBreakpoints", {
            "source": {"path": script},
            "breakpoints": [{"line": BREAKPOINT_LINE}],
        })
        check("setBreakpoints 响应成功", resp.get("success") is True)
        bps = resp.get("body", {}).get("breakpoints", [])
        check("断点已验证", len(bps) > 0 and bps[0].get("verified") is True,
              f"bps={bps}")

        # 4. configurationDone（触发脚本执行）
        print("\n[4] configurationDone")
        resp = client.send("configurationDone", {})
        check("configurationDone 响应成功", resp.get("success") is True)

        # 5. 等待 stopped 事件（断点命中）
        print("\n[5] 等待 stopped 事件（断点命中）")
        ev = client.wait_event("stopped", timeout=15)
        reason = ev.get("body", {}).get("reason")
        check("stopped 原因是 breakpoint", reason == "breakpoint",
              f"reason={reason}")
        thread_id = ev.get("body", {}).get("threadId")
        check("stopped 返回 threadId=1", thread_id == 1, f"threadId={thread_id}")

        # 6. threads
        print("\n[6] threads")
        resp = client.send("threads", {})
        threads = resp.get("body", {}).get("threads", [])
        check("threads 返回 1 个线程", len(threads) == 1 and threads[0].get("id") == 1,
              f"threads={threads}")

        # 7. stackTrace
        print("\n[7] stackTrace")
        resp = client.send("stackTrace", {"threadId": 1})
        frames = resp.get("body", {}).get("stackFrames", [])
        check("stackTrace 返回帧列表", len(frames) > 0, f"frames={frames}")
        if frames:
            top_frame = frames[0]
            check("栈顶帧行号是断点行", top_frame.get("line") == BREAKPOINT_LINE,
                  f"line={top_frame.get('line')}")
            frame_id = top_frame.get("id")
            print(f"       栈顶帧: name={top_frame.get('name')}, line={top_frame.get('line')}")
        else:
            frame_id = 0

        # 8. scopes
        print("\n[8] scopes")
        resp = client.send("scopes", {"frameId": frame_id})
        scopes = resp.get("body", {}).get("scopes", [])
        check("scopes 返回 Local + Global", len(scopes) >= 2,
              f"scopes={scopes}")
        local_ref = None
        for s in scopes:
            if s.get("name") == "Local":
                local_ref = s.get("variablesReference")
        check("Local 作用域存在", local_ref is not None, f"scopes={scopes}")

        # 9. variables
        print("\n[9] variables (Local)")
        if local_ref is not None:
            resp = client.send("variables", {"variablesReference": local_ref})
            variables = resp.get("body", {}).get("variables", [])
            check("variables 返回非空", len(variables) > 0,
                  f"variables={variables}")
            var_names = [v.get("name") for v in variables]
            print(f"       局部变量: {var_names}")
            # debug_test.script 第 52 行在主流程，此时 globalCount 已定义
            check("能找到 globalCount 变量", "globalCount" in var_names,
                  f"names={var_names}")

        # 10. continue（恢复执行，脚本可能完成或再次命中断点）
        print("\n[10] continue")
        resp = client.send("continue", {"threadId": 1})
        check("continue 响应成功", resp.get("success") is True)

        # 11. 等待 terminated 事件（脚本执行完成）
        # continue 后可能收到 output 事件（console.log）或再次 stopped（断点重触发）
        print("\n[11] 等待 terminated 事件")
        got_terminated = False
        deadline = time.time() + 20
        extra_stops = 0
        while time.time() < deadline:
            try:
                ev = client._event_q.get(timeout=deadline - time.time())
                ev_name = ev.get("event")
                if ev_name == "terminated":
                    got_terminated = True
                    check("收到 terminated 事件", True)
                    break
                elif ev_name == "stopped":
                    extra_stops += 1
                    if extra_stops <= 3:
                        print(f"       (再次 stopped: {ev.get('body', {}).get('reason')}), 继续...")
                        client.send("continue", {"threadId": 1})
                elif ev_name == "output":
                    out_body = ev.get("body", {})
                    print(f"       [output:{out_body.get('category')}] {out_body.get('output', '').rstrip()}")
            except queue.Empty:
                break
        if not got_terminated:
            check("收到 terminated 事件", False, f"超时，期间收到 {extra_stops} 次额外 stopped")

    finally:
        client.close()

    print(f"\n{'='*50}")
    print(f"结果: {passed} 通过, {failed} 失败")
    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()
