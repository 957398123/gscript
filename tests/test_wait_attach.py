#!/usr/bin/env python3
"""
gscript waitForDebuggerAndAttach 模式测试（模式 3：主线程驱动 attach）。

验证 DebugAgent.waitForDebuggerAndAttach() 的完整流程：
  1. Java 主线程阻塞等待 VSCode 连接
  2. Python DAP 客户端 attach + configurationDone
  3. Java 主线程被唤醒，开始 eval 脚本
  4. 首次 eval 命中 pauseRequested → stopped(reason=pause)
  5. stackTrace / source 请求验证（源码来自 gclass 的 sourceContent）
  6. continue → 脚本执行完毕 → terminated 事件
  7. 验证 stdout 输出正确 + stderr 含"调试器已就绪"/"主线程开始执行脚本"

这是宿主 static 块加载脚本场景的核心测试：主线程是脚本执行驱动者，
调试器连接后拦截主线程的脚本调用。

运行：python tests/test_wait_attach.py
前提：mvn clean package -DskipTests
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
# Java 1.4 迁移：自研 JSON 库编译进 target/classes，无 Gson 依赖
DEBUG_CP = CLASSES_DIR

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
    """通过 TCP socket 与 DAP 适配器通信。"""

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
            resp = self._response_q.get(timeout=10)
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
    """启动 TestScript（debug 模式用 DEBUG_CP = target/classes，自研 JSON 库）。"""
    # -Dfile.encoding=UTF-8：强制 Java stdout/stderr 用 UTF-8（Windows 默认 GBK 会导致中文匹配失败）
    cmd = [JAVA, "-Dfile.encoding=UTF-8", "-cp", DEBUG_CP, "org.gscript.TestScript", name, mode]
    return subprocess.Popen(
        cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        encoding="utf-8", errors="replace",
    )


def test_wait_attach():
    """测试 waitForDebuggerAndAttach：主线程阻塞等连接 → 唤醒后首次 eval 挂起 → continue 完成。"""
    print("=== 测试: waitForDebuggerAndAttach（主线程驱动 attach）===")
    ensure_gclass("debug_attach_test")
    proc = start_java("debug_attach_test", "debugagent-waitattach")
    try:
        client = SocketDapClient(HOST, PORT)

        # 1. initialize
        resp = client.send_request("initialize", {
            "clientID": "test", "adapterID": "gscript",
            "linesStartAt1": True, "columnsStartAt1": True, "pathFormat": "path",
        })
        check("initialize 成功", resp.get("success", False))

        # 2. attach（路径映射：本地 resources → 远程 sourcePath=文件名）
        resp = client.send_request("attach", {
            "localRoot": RESOURCES_DIR, "remoteRoot": "", "stopOnEntry": False,
        })
        check("attach 成功", resp.get("success", False))

        # 3. configurationDone → 唤醒 Java 主线程
        client.send_request("configurationDone", {})

        # 4. 等待 stopped（entryStopRequested 触发的 entry 断点，首次 eval 即挂起）
        stopped = client.wait_event("stopped", timeout=10)
        check("收到 stopped（主线程首次 eval 挂起）", stopped is not None)
        if stopped:
            reason = stopped.get("body", {}).get("reason", "")
            check("stopped 原因 = entry（entryStopRequested）", reason == "entry")

            # 5. stackTrace 验证有帧
            resp = client.send_request("stackTrace", {"threadId": 1})
            frames = resp.get("body", {}).get("stackFrames", [])
            check("stackTrace 有帧", len(frames) > 0)
            if frames:
                check("栈顶帧行号 > 0", frames[0].get("line", 0) > 0)
                # 6. source.sourceReference > 0（attach 源码来自 gclass 的 sourceContent）
                src = frames[0].get("source", {})
                src_ref = src.get("sourceReference", 0)
                check("source.sourceReference > 0（源码来自 gclass）", src_ref > 0)
                if src_ref > 0:
                    resp = client.send_request("source", {"sourceReference": src_ref, "source": src})
                    content = resp.get("body", {}).get("content", "")
                    check("source 请求返回源码内容", len(content) > 0)
                    check("源码含脚本文本", "calculateFactorial" in content or "attach" in content)

                # 7. scopes + variables（验证可读变量）
                fid = frames[0].get("id")
                resp = client.send_request("scopes", {"frameId": fid})
                scopes = resp.get("body", {}).get("scopes", [])
                check("scopes 返回", len(scopes) > 0)

        # 8. continue → 脚本执行完毕
        client.send_request("continue", {"threadId": 1})

        # 9. 等待 terminated 事件（notifyScriptCompleted 触发）
        terminated = client.wait_event("terminated", timeout=10)
        check("收到 terminated 事件（脚本执行完毕）", terminated is not None)

        client.close()

        # 10. communicate 同时读 stdout+stderr 并等进程退出（避免 pipe 死锁）
        try:
            out, err = proc.communicate(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            out, err = proc.communicate(timeout=5)
        check("Java 进程正常退出", proc.returncode == 0)

        # 11. 验证 stdout 输出
        check("stdout 含 '5! = 120'", "5! = 120" in out)
        check("stdout 含 'sum = 150'", "sum = 150" in out)
        check("stdout 含 'counter = 270'", "counter = 270" in out)
        check("stdout 含 'attach 调试测试结束'", "attach 调试测试结束" in out)

        # 12. 验证 stderr 含主线程唤醒标志
        check("stderr 含 '调试器已就绪'（主线程被唤醒）", "调试器已就绪" in err)
        check("stderr 含 '主线程开始执行脚本'", "主线程开始执行脚本" in err)

    finally:
        if proc.poll() is None:
            proc.kill()
            try:
                proc.wait(timeout=5)
            except Exception:
                pass


if __name__ == "__main__":
    test_wait_attach()
    print(f"\n=== 结果: {passed} passed, {failed} failed ===")
    sys.exit(0 if failed == 0 else 1)
