#!/usr/bin/env python3
"""
gscript DAP 路径格式不匹配测试（模拟 VSCode 的 ${workspaceFolder} 行为）。

VSCode 的 ${workspaceFolder} 解析后可能是混合斜杠（如 e:\JProjects\gscript/src/...），
而 setBreakpoints 的 source.path 是规范化的 Windows 路径（反斜杠）。
此测试验证 canonicalize 修复：launch 用正斜杠/小写盘符，setBreakpoints 用反斜杠/大写盘符，
断点仍应命中。

用法：python test_path_mismatch.py
"""

import json, os, subprocess, sys, time, threading, queue

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
JAR = os.path.join(BASE_DIR, "..", "target", "gscript-1.0-SNAPSHOT.jar")
SCRIPT = os.path.join(BASE_DIR, "..", "src", "main", "resources", "multi_a.script")
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
    script = os.path.abspath(SCRIPT)
    if not os.path.exists(jar): print(f"[FAIL] jar: {jar}"); sys.exit(1)

    # 模拟 VSCode 的路径格式：
    # launch 用正斜杠 + 小写盘符（模拟 ${workspaceFolder}/src/...）
    script_launch = script.replace("\\", "/").replace("E:", "e:")
    # setBreakpoints 用规范化的反斜杠 + 大写盘符（VSCode 规范化后的路径）
    script_bp = script

    print(f"[INFO] launch path (mixed slash):  {script_launch}")
    print(f"[INFO] setBreakpoints path (norm): {script_bp}")

    c = DapClient([JAVA, "-jar", jar, "--stdio"])
    p = f = 0
    def ck(n, cond, d=""):
        nonlocal p, f
        if cond: p += 1; print(f"  [PASS] {n}")
        else: f += 1; print(f"  [FAIL] {n} {d}")

    try:
        c.send("initialize", {"clientID": "test", "adapterID": "gscript", "linesStartAt1": True})
        # launch 用混合斜杠路径
        c.send("launch", {"files": [script_launch], "stopOnEntry": False})
        # setBreakpoints 用规范化路径（与 launch 不同格式）
        c.send("setBreakpoints", {"source": {"path": script_bp}, "breakpoints": [{"line": 5}]})
        c.send("configurationDone")

        ev = c.wait("stopped")
        tid = ev.get("body", {}).get("threadId", 1)
        reason = ev.get("body", {}).get("reason")
        resp = c.send("stackTrace", {"threadId": tid})
        frames = resp.get("body", {}).get("stackFrames", [])
        line = frames[0].get("line") if frames else None

        ck("断点命中 line 5（路径格式不匹配场景）", line == 5, f"line={line} reason={reason}")
        ck("断点原因是 breakpoint", reason == "breakpoint", f"reason={reason}")
        c.send("continue", {"threadId": tid})

        # 等待 terminated
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
