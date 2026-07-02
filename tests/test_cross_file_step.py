#!/usr/bin/env python3
"""
gscript DAP 跨文件 stepIn 测试。

场景：只在 multi_b.script 第 9 行设断点（不在 multi_a 设断点）。
断点命中后 stepIn，应跳转到 multi_a 的 shared() 函数内部，而非直接运行结束。

验证 Bug #17 修复：STEP_IN 只比行号不比文件路径，跨文件行号相同时（multi_b:9 → multi_a:9）
不挂起，导致 stepIn 直接运行结束。

用法：python test_cross_file_step.py
"""

import json, os, subprocess, sys, time, threading, queue

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
JAR = os.path.join(BASE_DIR, "..", "target", "gscript-1.0-SNAPSHOT.jar")
SCRIPT_A = os.path.join(BASE_DIR, "..", "src", "main", "resources", "multi_a.script")
SCRIPT_B = os.path.join(BASE_DIR, "..", "src", "main", "resources", "multi_b.script")
JAVA = os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-9.0.4") + r"\bin\java.exe"

class DapClient:
    def __init__(self, cmd):
        self.proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0)
        self._seq = 0; self._eq = queue.Queue(); self._rq = queue.Queue()
        threading.Thread(target=self._loop, daemon=True).start()
    def _lex(self, n):
        buf = b""
        while len(buf) < n:
            c = self.proc.stdout.read(n - len(buf))
            if not c: break
            buf += c
        return buf
    def _loop(self):
        try:
            while True:
                hs = {}
                while True:
                    ln = b""
                    while True:
                        b = self.proc.stdout.read(1)
                        if not b: break
                        ln += b
                        if b == b"\n": break
                    if not ln or ln == b"\r\n": break
                    t = ln.decode("utf-8").strip()
                    if ":" in t: k, v = t.split(":", 1); hs[k.strip().lower()] = v.strip()
                cl = int(hs.get("content-length", "0"))
                if cl == 0: continue
                body = self._lex(cl)
                msg = json.loads(body.decode("utf-8"))
                if msg.get("type") == "response": self._rq.put(msg)
                elif msg.get("type") == "event": self._eq.put(msg)
        except: pass
    def send(self, cmd, args=None):
        self._seq += 1
        m = {"seq": self._seq, "type": "request", "command": cmd}
        if args: m["arguments"] = args
        d = json.dumps(m).encode("utf-8")
        self.proc.stdin.write(f"Content-Length: {len(d)}\r\n\r\n".encode() + d)
        self.proc.stdin.flush()
        return self._rq.get(timeout=15)
    def wait(self, ev, t=15):
        dl = time.time() + t
        while time.time() < dl:
            try:
                e = self._eq.get(timeout=dl - time.time())
                if e.get("event") == ev: return e
            except: break
        raise TimeoutError(f"timeout: {ev}")
    def close(self):
        self.proc.stdin.close()
        try: self.proc.wait(timeout=3)
        except: self.proc.kill()

def main():
    jar = os.path.abspath(JAR)
    sa = os.path.abspath(SCRIPT_A)
    sb = os.path.abspath(SCRIPT_B)
    if not os.path.exists(jar): print(f"[FAIL] jar: {jar}"); sys.exit(1)

    print("[INFO] 测试场景：只在 multi_b:9 设断点，stepIn 应跳转到 multi_a 的 shared()")
    c = DapClient([JAVA, "-jar", jar, "--stdio"])
    p = f = 0
    def ck(n, cond, d=""):
        nonlocal p, f
        if cond: p += 1; print(f"  [PASS] {n}")
        else: f += 1; print(f"  [FAIL] {n} {d}")

    try:
        c.send("initialize", {"clientID": "test", "adapterID": "gscript", "linesStartAt1": True})
        c.send("launch", {"files": [sa, sb], "stopOnEntry": False})
        # 只在 multi_b:9 设断点，不在 multi_a 设断点
        c.send("setBreakpoints", {"source": {"path": sb}, "breakpoints": [{"line": 9}]})
        # 清空 multi_a 的断点（确保没有断点辅助）
        c.send("setBreakpoints", {"source": {"path": sa}, "breakpoints": []})
        c.send("configurationDone")

        # 1. 等待 multi_b:9 断点命中
        ev = c.wait("stopped")
        tid = ev.get("body", {}).get("threadId", 1)
        reason = ev.get("body", {}).get("reason")
        resp = c.send("stackTrace", {"threadId": tid})
        frames = resp.get("body", {}).get("stackFrames", [])
        bp_line = frames[0].get("line") if frames else None
        bp_path = frames[0].get("source", {}).get("path", "") if frames else ""
        ck("断点命中 multi_b line 9", bp_line == 9 and "multi_b" in bp_path,
           f"line={bp_line} path={bp_path}")
        ck("断点原因是 breakpoint", reason == "breakpoint", f"reason={reason}")

        # 2. stepIn —— 应跳转到 multi_a 的 shared() 函数
        c.send("stepIn", {"threadId": tid})
        ev2 = c.wait("stopped")
        reason2 = ev2.get("body", {}).get("reason")
        resp2 = c.send("stackTrace", {"threadId": tid})
        frames2 = resp2.get("body", {}).get("stackFrames", [])
        step_line = frames2[0].get("line") if frames2 else None
        step_path = frames2[0].get("source", {}).get("path", "") if frames2 else ""
        step_name = frames2[0].get("name", "") if frames2 else ""

        ck("stepIn 后挂起（未直接运行结束）", frames2 != [], "没有收到 stopped 事件")
        ck("stepIn 原因是 step", reason2 == "step", f"reason={reason2}")
        ck("stepIn 跳转到 multi_a 文件", "multi_a" in step_path,
           f"path={step_path}")
        ck("stepIn 帧名称是 shared", step_name == "shared",
           f"name={step_name}")

        # 3. continue 后应正常终止
        c.send("continue", {"threadId": tid})
        got = False
        dl = time.time() + 10
        while time.time() < dl:
            try:
                e = c._eq.get(timeout=dl - time.time())
                if e.get("event") == "terminated": got = True; break
            except: break
        ck("脚本正常终止", got)
    except Exception as e:
        print(f"[ERROR] {e}"); import traceback; traceback.print_exc(); f += 1
    finally:
        c.close()
    print(f"\n==== {p} passed, {f} failed ====")
    sys.exit(0 if f == 0 else 1)

if __name__ == "__main__":
    main()
