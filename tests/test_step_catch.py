#!/usr/bin/env python3
"""
gscript DAP 单步正确性测试（catch 行号映射）。

验证 Bug 修复：throw a; 后步进，光标应到 catch 行（line 7）而非 try body 末尾（line 6）。

流程：在 throw 行(line 5)设断点 → 命中断点 → stepIn → 应停在 catch 行(line 7)
     → stepIn → 应停在 catch body 首条语句(line 8)

用法：
    python test_step_catch.py

前提：mvn package 已生成 target/gscript-1.0-SNAPSHOT.jar
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
    "..", "src", "main", "resources", "test2.script",
)
JAVA_BIN = os.environ.get(
    "JAVA_HOME",
    r"C:\Program Files\Java\jdk-9.0.4",
) + r"\bin\java.exe"


# ---- DAP 客户端（复用 test_dap_e2e.py 的实现）----

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
                except Exception as e:
                    print(f"[parse error] {e}", file=sys.stderr)
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

    def drain_events(self):
        """排空事件队列（忽略 output 等无关事件）。"""
        while True:
            try:
                self._event_q.get_nowait()
            except queue.Empty:
                break

    def close(self):
        self.proc.stdin.close()
        try:
            self.proc.wait(timeout=3)
        except Exception:
            self.proc.kill()


def get_stopped_line(ev):
    """从 stopped 事件提取栈顶行号（需配合 stackTrace 请求）。"""
    return ev


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

    def get_top_frame_line(client, thread_id):
        """发送 stackTrace 请求，返回栈顶帧的行号。"""
        resp = client.send("stackTrace", {"threadId": thread_id})
        frames = resp.get("body", {}).get("stackFrames", [])
        if frames:
            return frames[0].get("line"), frames[0].get("name", "?")
        return None, None

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

        # 3. setBreakpoints — line 5 = throw a
        resp = client.send("setBreakpoints", {
            "source": {"path": script},
            "breakpoints": [{"line": 5}],
        })
        check("setBreakpoints 成功", resp.get("success") is True)

        # 4. configurationDone
        client.send("configurationDone")

        # 5. 等待断点命中 (line 5)
        ev = client.wait_event("stopped")
        reason = ev.get("body", {}).get("reason")
        tid = ev.get("body", {}).get("threadId", 1)
        line, fname = get_top_frame_line(client, tid)
        check("断点命中 line 5 (throw a)", line == 5, f"实际 line={line} reason={reason}")

        # 6. stepIn — 应跳到 catch 行 (line 7)
        # 注意：DAP 的 stepIn 命令是 "stepIn"，"next" 是 stepOver
        client.send("stepIn", {"threadId": tid})
        ev = client.wait_event("stopped")
        reason = ev.get("body", {}).get("reason")
        line, fname = get_top_frame_line(client, tid)
        check("stepIn 后停在 line 7 (catch 行)", line == 7,
              f"实际 line={line}（期望 7）reason={reason}")

        # 7. stepIn — 应到 catch body 首条语句 (line 8: console.log(e))
        client.send("stepIn", {"threadId": tid})
        ev = client.wait_event("stopped")
        reason = ev.get("body", {}).get("reason")
        line, fname = get_top_frame_line(client, tid)
        check("stepIn 后停在 line 8 (console.log(e))", line == 8,
              f"实际 line={line}（期望 8）reason={reason}")

        # 8. continue — 让脚本跑完
        client.send("continue", {"threadId": tid})
        try:
            client.wait_event("terminated", timeout=10)
            check("脚本正常终止", True)
        except TimeoutError:
            # 可能有其他 stopped，再 continue
            check("脚本正常终止", False, "等待 terminated 超时")

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
