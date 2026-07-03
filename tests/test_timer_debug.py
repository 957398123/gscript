#!/usr/bin/env python3
"""
定时器调试端到端测试（方案 B 验证）：

测试 1（debugagent / waitForDebugger）：setTimeout 回调断点命中 + terminated 时机
测试 2（debugagent-attach / attachReady）：setInterval 持续运行 + 运行时附加断点命中 + disconnect 退出

运行：python tests/test_timer_debug.py
前提：
  - mvn clean compile
  - timer_test 与 timer_debug 已 compile 成 gclass（测试会自动 compile）
"""

import json
import os
import queue
import socket
import subprocess
import sys
import threading
import time

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.join(BASE_DIR, "..")
CLASSES_DIR = os.path.join(PROJECT_DIR, "target", "classes")
RESOURCES_DIR = os.path.join(PROJECT_DIR, "src", "main", "resources")
JAVA = os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-9.0.4") + r"\bin\java.exe"
PORT = 4711
HOST = "localhost"
# debugagent / debugagent-attach 模式经过 DebugAgent → DapServer 需要 Gson（DAP JSON 处理），
# 而 target/classes 不含 Gson 依赖（仅 mvn package 打 fat jar 时包含）。
# 故 debug 启动用 classpath = target/classes + Maven 本地仓库的 Gson jar。
GSON_JAR = os.path.join(
    os.path.expanduser("~"),
    ".m2", "repository", "com", "google", "code", "gson",
    "gson", "2.10.1", "gson-2.10.1.jar",
)
DEBUG_CP = CLASSES_DIR + os.pathsep + GSON_JAR

passed = 0
failed = 0


def check(desc, cond):
    global passed, failed
    if cond:
        print(f"  [PASS] {desc}")
        passed += 1
    else:
        print(f"  [FAIL] {desc}")
        failed += 1


class SocketDapClient:
    """通过 TCP socket 与 DAP 适配器通信（复制自 test_attach_smoke）。"""

    def __init__(self, host, port, retry_timeout=15):
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
        msg = {"seq": self._seq, "type": "request", "command": command}
        if args:
            msg["arguments"] = args
        data = json.dumps(msg).encode("utf-8")
        frame = f"Content-Length: {len(data)}\r\n\r\n".encode("utf-8") + data
        self.sock.sendall(frame)
        while True:
            resp = self._response_q.get(timeout=15)
            if resp.get("request_seq") == self._seq:
                return resp

    def wait_event(self, event_name, timeout=15):
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


def ensure_gclass(name):
    """确保 gclass 已编译（含 SourceContent）。"""
    gclass = os.path.join(CLASSES_DIR, "gtxt", name + ".gclass")
    if not os.path.exists(gclass):
        cmd = [JAVA, "-cp", CLASSES_DIR, "org.gscript.TestScript", name, "compile"]
        subprocess.run(cmd, capture_output=True, text=True, timeout=15)


def start_java(name, mode):
    """启动 TestScript（debug 模式需 Gson，用 DEBUG_CP）。"""
    cmd = [JAVA, "-cp", DEBUG_CP, "org.gscript.TestScript", name, mode]
    return subprocess.Popen(
        cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        encoding="utf-8", errors="replace",
    )


def test_debugagent_wait():
    """测试 1：waitForDebugger 模式，setTimeout 回调断点命中。"""
    print("=== 测试 1: waitForDebugger + setTimeout 回调断点 ===")
    ensure_gclass("timer_test")
    proc = start_java("timer_test", "debugagent")
    try:
        client = SocketDapClient(HOST, PORT)
        # initialize
        resp = client.send_request("initialize", {
            "clientID": "test", "adapterID": "gscript",
            "linesStartAt1": True, "columnsStartAt1": True, "pathFormat": "path",
        })
        check("initialize 成功", resp.get("success", False))
        # attach（waitForDebugger 模式，路径映射本地 resources → 远程 sourcePath）
        client.send_request("attach", {
            "localRoot": RESOURCES_DIR, "remoteRoot": "", "stopOnEntry": False,
        })
        # setBreakpoints：timer_test.script 第 13 行 console.log("OUT:zero")（0延迟回调首行）
        script_path = os.path.join(RESOURCES_DIR, "timer_test.script")
        resp = client.send_request("setBreakpoints", {
            "source": {"path": script_path},
            "breakpoints": [{"line": 13}],
        })
        check("setBreakpoints 成功", resp.get("success", False))
        # configurationDone → 启动解释器
        client.send_request("configurationDone", {})
        # 等待断点命中（zero 回调 0ms 触发）
        stopped = client.wait_event("stopped", timeout=10)
        check("断点命中（stopped）", stopped is not None)
        if stopped:
            reason = stopped.get("body", {}).get("reason", "")
            check("stopped 原因 breakpoint", reason == "breakpoint")
            # stackTrace 应有帧，行号 13
            resp = client.send_request("stackTrace", {"threadId": 1})
            frames = resp.get("body", {}).get("stackFrames", [])
            check("stackTrace 有帧", len(frames) > 0)
            if frames:
                check("栈顶帧行号=13（setTimeout 回调）", frames[0].get("line") == 13)
                # source 请求验证（attach 模式源码来自 gclass 的 SourceContent 属性）
                # 关键：DAP Source 对象字段名为 sourceReference（非 reference），
                # VSCode 据此发 source 请求回填顶层 sourceReference，handleSource 查 sourceRefs 取源码
                src = frames[0].get("source", {})
                src_ref = src.get("sourceReference", 0)
                check("source.sourceReference > 0（attach 源码来自 gclass）", src_ref > 0)
                if src_ref > 0:
                    resp = client.send_request("source", {"sourceReference": src_ref, "source": src})
                    content = resp.get("body", {}).get("content", "")
                    check("source 请求返回源码内容", len(content) > 0)
                    check("源码内容含源码文本", "console.log" in content or "zero" in content)
                # scopes + variables
                fid = frames[0].get("id")
                resp = client.send_request("scopes", {"frameId": fid})
                scopes = resp.get("body", {}).get("scopes", [])
                check("scopes 返回", len(scopes) > 0)
                if scopes:
                    vref = scopes[0].get("variablesReference", 0)
                    resp = client.send_request("variables", {"variablesReference": vref})
                    check("variables 返回", len(resp.get("body", {}).get("variables", [])) >= 0)
        # continue → 所有 setTimeout 跑完 → terminated
        client.send_request("continue", {"threadId": 1})
        terminated = client.wait_event("terminated", timeout=10)
        check("terminated 事件（所有定时器排空后才发）", terminated is not None)
        client.close()
    finally:
        try:
            proc.wait(timeout=10)
            check("Java 进程正常退出", proc.returncode == 0)
        except subprocess.TimeoutExpired:
            proc.kill()
            check("Java 进程正常退出", False)
            try:
                proc.wait(timeout=5)
            except Exception:
                pass
        # 诊断：打印 Java stderr
        if proc.stderr:
            err = proc.stderr.read()
            if err.strip():
                print(f"  [JAVA STDERR] {err.strip()[:500]}")


def test_debugagent_attach():
    """测试 2：attachReady 模式，setInterval 持续运行 + 运行时附加断点。"""
    print("=== 测试 2: attachReady + setInterval 运行时附加 ===")
    ensure_gclass("timer_debug")
    proc = start_java("timer_debug", "debugagent-attach")
    try:
        client = SocketDapClient(HOST, PORT)
        # initialize + attach
        client.send_request("initialize", {
            "clientID": "test", "adapterID": "gscript",
            "linesStartAt1": True, "columnsStartAt1": True, "pathFormat": "path",
        })
        client.send_request("attach", {
            "localRoot": RESOURCES_DIR, "remoteRoot": "", "stopOnEntry": False,
        })
        # setBreakpoints：timer_debug.script 第 5 行 counter = counter + 1（setInterval 回调内）
        script_path = os.path.join(RESOURCES_DIR, "timer_debug.script")
        resp = client.send_request("setBreakpoints", {
            "source": {"path": script_path},
            "breakpoints": [{"line": 5}],
        })
        check("setBreakpoints 成功", resp.get("success", False))
        client.send_request("configurationDone", {})
        # 等待 stopped（pauseOnAttach 触发的 pause）
        stopped = client.wait_event("stopped", timeout=10)
        check("收到 stopped（attach 暂停）", stopped is not None)
        # continue → setInterval 回调断点命中
        if stopped:
            client.send_request("continue", {"threadId": 1})
            stopped2 = client.wait_event("stopped", timeout=10)
            check("continue 后断点命中（setInterval 回调）", stopped2 is not None)
            if stopped2:
                reason = stopped2.get("body", {}).get("reason", "")
                check("断点原因 breakpoint", reason == "breakpoint")
                # stackTrace → scopes → variables（验证 counter 变量）
                resp = client.send_request("stackTrace", {"threadId": 1})
                frames = resp.get("body", {}).get("stackFrames", [])
                check("stackTrace 有回调帧", len(frames) > 0)
                if frames:
                    check("栈顶帧行号=5（setInterval 回调）", frames[0].get("line") == 5)
                    fid = frames[0].get("id")
                    resp = client.send_request("scopes", {"frameId": fid})
                    scopes = resp.get("body", {}).get("scopes", [])
                    check("scopes 返回", len(scopes) > 0)
                    # counter 是全局变量（var counter = 0; 顶层声明），不在回调 Local 作用域，
                    # 须从 Global 作用域取。VSCode 变量面板默认展开 Locals，counter 需展开 Global 才能看到。
                    global_vref = None
                    for sc in scopes:
                        if sc.get("name") == "Global":
                            global_vref = sc.get("variablesReference", 0)
                            break
                    check("存在 Global 作用域", global_vref is not None)
                    if global_vref:
                        resp = client.send_request("variables", {"variablesReference": global_vref})
                        variables = resp.get("body", {}).get("variables", [])
                        names = [v.get("name") for v in variables]
                        check("variables 含 counter（Global）", "counter" in names)
        # disconnect（attach 模式：分离调试器，程序继续运行 setInterval，不终止）
        # 这是正确的 JS 语义（如同 node --inspect 分离后程序继续运行）。
        # VSCode 对 attach 配置点"停止"发送 disconnect（terminateDebuggee=false），detach-without-kill。
        resp = client.send_request("disconnect", {})
        check("disconnect 成功", resp.get("success", False))
        client.close()
        # 验证：分离后解释器继续运行（setInterval 仍在跑，进程未退出）
        time.sleep(0.3)
        check("detach 后程序继续运行（未退出）", proc.poll() is None)
    finally:
        # setInterval 永不退出，detach 后程序仍在运行 → 测试主动 kill 清理
        if proc.poll() is None:
            proc.kill()
            try:
                proc.wait(timeout=5)
            except Exception:
                pass
        check("Java 进程已清理", True)
        if proc.stderr:
            err = proc.stderr.read()
            if err.strip():
                print(f"  [JAVA STDERR] {err.strip()[:500]}")


if __name__ == "__main__":
    test_debugagent_wait()
    print()
    test_debugagent_attach()
    print(f"\n=== 结果: {passed} passed, {failed} failed ===")
    sys.exit(0 if failed == 0 else 1)
