#!/usr/bin/env python3
"""
gscript DAP 多文件调试测试。

验证多文件调试支持：
1. launch 用 files 数组指定多个脚本文件
2. 不同文件分别设断点，各自命中
3. stackTrace 每帧的 source.path 正确反映该帧所属文件
4. 跨文件函数调用（B 调用 A 定义的 shared）时调用栈含两个文件
5. 后加载文件覆盖前文件同名函数（B 的 greet 覆盖 A 的 greet）

测试脚本：
  multi_a.script — 定义 greet（A 版）+ shared，顶层调用 greet("fromA")
  multi_b.script — 定义 greet（B 版，覆盖 A），调用 greet("fromB") + shared()

预期执行顺序：
  1. 文件 A 顶层执行：定义函数 → console.log(greet("fromA")) → 命中 A:5
  2. 文件 B 顶层执行：定义 greet（覆盖）→ console.log(greet("fromB")) → 命中 B:5
  3. 文件 B: console.log(shared()) → 命中 B:9 → continue → shared() 调用 → 命中 A:9
  4. 终止

用法：
    python test_multi_file.py

前提：mvn package 已生成 target/gscript-1.0-SNAPSHOT.jar
"""

import json
import os
import subprocess
import sys
import time
import threading
import queue

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
JAR_PATH = os.path.join(BASE_DIR, "..", "target", "gscript-1.0-SNAPSHOT.jar")
SCRIPT_A = os.path.join(BASE_DIR, "..", "src", "main", "resources", "multi_a.script")
SCRIPT_B = os.path.join(BASE_DIR, "..", "src", "main", "resources", "multi_b.script")
JAVA_BIN = os.environ.get(
    "JAVA_HOME",
    r"C:\Program Files\Java\jdk-9.0.4",
) + r"\bin\java.exe"

# 断点行号
BP_A_GREET = 5    # multi_a: return "A says: " + name;
BP_A_SHARED = 9   # multi_a: return "shared from A";
BP_B_GREET = 5    # multi_b: return "B says: " + name;
BP_B_CALL_SHARED = 9  # multi_b: console.log(shared());


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

    def drain_output(self):
        """排空事件队列中的 output 事件，返回累积输出文本"""
        outputs = []
        while True:
            try:
                ev = self._event_q.get_nowait()
                if ev.get("event") == "output":
                    outputs.append(ev.get("body", {}).get("output", ""))
            except queue.Empty:
                break
        return "".join(outputs)

    def close(self):
        self.proc.stdin.close()
        try:
            self.proc.wait(timeout=3)
        except Exception:
            self.proc.kill()


def get_stack_frames(client, thread_id):
    resp = client.send("stackTrace", {"threadId": thread_id})
    return resp.get("body", {}).get("stackFrames", [])


def main():
    jar = os.path.abspath(JAR_PATH)
    script_a = os.path.abspath(SCRIPT_A)
    script_b = os.path.abspath(SCRIPT_B)
    for p, label in [(jar, "jar"), (script_a, "multi_a.script"), (script_b, "multi_b.script")]:
        if not os.path.exists(p):
            print(f"[FAIL] {label} 不存在: {p}", file=sys.stderr)
            sys.exit(1)

    cmd = [JAVA_BIN, "-jar", jar, "--stdio"]
    print(f"[INFO] 启动调试适配器（多文件模式）")
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

        # 2. launch — 用 files 数组指定两个文件
        resp = client.send("launch", {
            "files": [script_a, script_b],
            "stopOnEntry": False,
        })
        check("launch (files 数组) 成功", resp.get("success") is True)

        # 3. setBreakpoints — 文件 A：line 5 (greet) + line 9 (shared)
        resp = client.send("setBreakpoints", {
            "source": {"path": script_a},
            "breakpoints": [{"line": BP_A_GREET}, {"line": BP_A_SHARED}],
        })
        check("setBreakpoints 文件 A 成功", resp.get("success") is True)

        # 3b. setBreakpoints — 文件 B：line 5 (greet) + line 9 (call shared)
        resp = client.send("setBreakpoints", {
            "source": {"path": script_b},
            "breakpoints": [{"line": BP_B_GREET}, {"line": BP_B_CALL_SHARED}],
        })
        check("setBreakpoints 文件 B 成功", resp.get("success") is True)

        # 4. configurationDone — 触发编译与执行
        client.send("configurationDone")

        # ============================================================
        # 5. 文件 A 顶层执行：console.log(greet("fromA")) → greet 调用 → 命中 A:5
        # ============================================================
        ev = client.wait_event("stopped")
        tid = ev.get("body", {}).get("threadId", 1)
        reason = ev.get("body", {}).get("reason")
        frames = get_stack_frames(client, tid)
        top = frames[0] if frames else {}
        check("断点1 命中文件 A line 5 (A.greet)",
              top.get("line") == BP_A_GREET,
              f"line={top.get('line')} reason={reason}")
        check("断点1 顶部帧 source.path = multi_a.script",
              top.get("source", {}).get("path") == script_a,
              f"path={top.get('source', {}).get('path')}")
        check("断点1 顶部帧名称 = greet",
              top.get("name") == "greet",
              f"name={top.get('name')}")
        # 第二帧应为文件 A 的匿名顶层（调用 greet 的位置，line 12）
        if len(frames) >= 2:
            caller = frames[1]
            check("断点1 调用帧 source.path = multi_a.script",
                  caller.get("source", {}).get("path") == script_a,
                  f"path={caller.get('source', {}).get('path')}")
        else:
            check("断点1 调用帧存在", False, f"frames={len(frames)}")
        client.send("continue", {"threadId": tid})

        # ============================================================
        # 6. 文件 B 顶层执行：console.log(greet("fromB")) → greet 调用 → 命中 B:5
        #    此时 B 的 greet 已覆盖 A 的 greet
        # ============================================================
        ev = client.wait_event("stopped")
        tid = ev.get("body", {}).get("threadId", 1)
        frames = get_stack_frames(client, tid)
        top = frames[0] if frames else {}
        check("断点2 命中文件 B line 5 (B.greet 覆盖 A.greet)",
              top.get("line") == BP_B_GREET,
              f"line={top.get('line')}")
        check("断点2 顶部帧 source.path = multi_b.script",
              top.get("source", {}).get("path") == script_b,
              f"path={top.get('source', {}).get('path')}")
        check("断点2 顶部帧名称 = greet（覆盖后）",
              top.get("name") == "greet",
              f"name={top.get('name')}")
        client.send("continue", {"threadId": tid})

        # ============================================================
        # 7. 文件 B: console.log(shared()) → 命中 B:9（调用点）
        # ============================================================
        ev = client.wait_event("stopped")
        tid = ev.get("body", {}).get("threadId", 1)
        frames = get_stack_frames(client, tid)
        top = frames[0] if frames else {}
        check("断点3 命中文件 B line 9 (调用 shared)",
              top.get("line") == BP_B_CALL_SHARED,
              f"line={top.get('line')}")
        check("断点3 顶部帧 source.path = multi_b.script",
              top.get("source", {}).get("path") == script_b,
              f"path={top.get('source', {}).get('path')}")
        client.send("continue", {"threadId": tid})

        # ============================================================
        # 8. shared() 被调用 → 命中 A:9（跨文件调用：B 顶层 → A.shared）
        # ============================================================
        ev = client.wait_event("stopped")
        tid = ev.get("body", {}).get("threadId", 1)
        frames = get_stack_frames(client, tid)
        top = frames[0] if frames else {}
        check("断点4 命中文件 A line 9 (shared，跨文件调用)",
              top.get("line") == BP_A_SHARED,
              f"line={top.get('line')}")
        check("断点4 顶部帧 source.path = multi_a.script",
              top.get("source", {}).get("path") == script_a,
              f"path={top.get('source', {}).get('path')}")
        check("断点4 顶部帧名称 = shared",
              top.get("name") == "shared",
              f"name={top.get('name')}")
        # 第二帧应为文件 B 的匿名顶层（调用 shared 的位置，line 9）—— 跨文件调用栈核心验证
        if len(frames) >= 2:
            caller = frames[1]
            check("断点4 调用帧 source.path = multi_b.script（跨文件）",
                  caller.get("source", {}).get("path") == script_b,
                  f"path={caller.get('source', {}).get('path')}")
            check("断点4 调用帧 line = 9 (B 的 console.log(shared()) 行)",
                  caller.get("line") == BP_B_CALL_SHARED,
                  f"line={caller.get('line')}")
        else:
            check("断点4 调用帧存在（跨文件栈）", False, f"frames={len(frames)}")
        client.send("continue", {"threadId": tid})

        # ============================================================
        # 9. 等待 terminated
        # ============================================================
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
                    frames = get_stack_frames(client, tid)
                    top = frames[0] if frames else {}
                    check("不应再命中断点", False,
                          f"又停在 line={top.get('line')} path={top.get('source',{}).get('path')}")
                    client.send("continue", {"threadId": tid})
            except queue.Empty:
                break
        check("脚本正常终止", got_terminated)

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
