#!/usr/bin/env python3
"""
gscript DAP while 循环断点命中测试。

验证 Bug 修复：while 语句节点未设置 line（默认 0），导致 visit(WhileStatement)
开头的 line(node) 不生效，while 条件指令的 sourceLine 继承前一条语句行号，
在 while 行设断点不会命中。修复后（Parser 设置 whileStatement.line）应正常命中。

流程：在 while 行(line 2)设断点 → 循环每次回条件都命中 line 2。
while(i<3)：i=0,1,2 时条件 true（命中3次），i=3 时 false 跳出。

用法：
    python test_while_breakpoint.py

前提：mvn package 已生成 target/gscript-1.0-SNAPSHOT.jar
"""

import json
import os
import subprocess
import sys
import time
import threading
import queue

JAR_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "target", "gscript-1.0-SNAPSHOT.jar",
)
SCRIPT_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "src", "main", "resources", "while_breakpoint.script",
)
JAVA_BIN = os.environ.get(
    "JAVA_HOME",
    r"C:\Program Files\Java\jdk-9.0.4",
) + r"\bin\java.exe"

BREAKPOINT_LINE = 2  # while(i < 3) { 所在行


class DapClient:
    def __init__(self, cmd):
        self.proc = subprocess.Popen(
            cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, bufsize=0,
        )
        self._seq = 0
        self._event_q = queue.Queue()
        self._response_q = queue.Queue()
        self._reader = threading.Thread(target=self._read_loop, daemon=True)
        self._reader.start()

    def _read_exact(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.proc.stdout.read(n - len(buf))
            if not chunk:
                break
            buf += chunk
        return buf

    def _read_line(self):
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
        try:
            while True:
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
                except Exception:
                    continue
                t = msg.get("type")
                if t == "response":
                    self._response_q.put(msg)
                elif t == "event":
                    self._event_q.put(msg)
        except Exception:
            pass

    def send(self, command, args=None):
        self._seq += 1
        msg = {"seq": self._seq, "type": "request", "command": command}
        if args is not None:
            msg["arguments"] = args
        data = json.dumps(msg).encode("utf-8")
        frame = f"Content-Length: {len(data)}\r\n\r\n".encode("utf-8") + data
        self.proc.stdin.write(frame)
        self.proc.stdin.flush()
        try:
            return self._response_q.get(timeout=15)
        except queue.Empty:
            raise TimeoutError(f"等待 '{command}' 响应超时")

    def wait_event(self, event_name, timeout=15):
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                ev = self._event_q.get(timeout=deadline - time.time())
                if ev.get("event") == event_name:
                    return ev
            except queue.Empty:
                break
        raise TimeoutError(f"等待事件 '{event_name}' 超时")

    def close(self):
        self.proc.stdin.close()
        try:
            self.proc.wait(timeout=3)
        except Exception:
            self.proc.kill()


def get_top_frame_line(client, thread_id):
    resp = client.send("stackTrace", {"threadId": thread_id})
    frames = resp.get("body", {}).get("stackFrames", [])
    if frames:
        return frames[0].get("line")
    return None


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
    print(f"[INFO] 启动调试适配器")
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
        resp = client.send("initialize", {
            "clientID": "test", "adapterID": "gscript",
            "linesStartAt1": True, "columnsStartAt1": True,
        })
        check("initialize 成功", resp.get("success") is True)

        # 2. launch
        resp = client.send("launch", {"program": script, "stopOnEntry": False})
        check("launch 成功", resp.get("success") is True)

        # 3. setBreakpoints — line 2 = while 行
        resp = client.send("setBreakpoints", {
            "source": {"path": script},
            "breakpoints": [{"line": BREAKPOINT_LINE}],
        })
        check("setBreakpoints 成功", resp.get("success") is True)
        bps = resp.get("body", {}).get("breakpoints", [])
        check("断点已验证", len(bps) > 0 and bps[0].get("verified") is True, f"bps={bps}")

        # 4. configurationDone
        client.send("configurationDone")

        # 5. while(i<3) 循环：条件求值 4 次（i=0,1,2 时 true 进 body，i=3 时 false 跳出）
        # 断点在 while 条件行，每次求值都命中，共 4 次
        hit_count = 0
        for expect_i in range(4):
            ev = client.wait_event("stopped")
            reason = ev.get("body", {}).get("reason")
            tid = ev.get("body", {}).get("threadId", 1)
            line = get_top_frame_line(client, tid)
            hit_count += 1
            check(f"第 {hit_count} 次断点命中 line {BREAKPOINT_LINE} (while 条件行)",
                  line == BREAKPOINT_LINE,
                  f"实际 line={line} reason={reason}")
            client.send("continue", {"threadId": tid})

        # 6. 第 4 次条件 i=3 false 跳出循环，应收到 terminated
        got_terminated = False
        deadline = time.time() + 10
        while time.time() < deadline:
            try:
                ev = client._event_q.get(timeout=deadline - time.time())
                if ev.get("event") == "terminated":
                    got_terminated = True
                    break
                elif ev.get("event") == "stopped":
                    tid = ev.get("body", {}).get("threadId", 1)
                    line = get_top_frame_line(client, tid)
                    check(f"不应再命中断点（循环应已结束）", False,
                          f"又停在 line={line}")
                    client.send("continue", {"threadId": tid})
            except queue.Empty:
                break
        check("脚本正常终止（while 循环正确退出）", got_terminated)

    except Exception as e:
        print(f"[ERROR] {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        failed += 1
    finally:
        client.close()

    print(f"\n==== 结果: {passed} passed, {failed} failed ====")
    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()
