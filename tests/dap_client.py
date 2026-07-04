"""DAP 测试公共模块。

提供 SocketDapClient（模拟 VSCode DAP 客户端）、ensure_gclass、start_java 等公共组件，
供 test_debug_mode_*.py 系列测试复用，避免重复代码。

设计参考 test_wait_attach.py 的 SocketDapClient 实现。
"""

import json
import os
import queue
import socket
import subprocess
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


def check(state, desc, cond):
    """断言检查，累加 passed/failed 计数到 state dict。"""
    if cond:
        print(f"  [PASS] {desc}")
        state["passed"] += 1
    else:
        print(f"  [FAIL] {desc}")
        state["failed"] += 1


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
        """等待指定事件。不匹配的事件会放回队列（避免丢失 terminated 等关键事件）。"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                evt = self._event_q.get(timeout=min(0.5, deadline - time.time()))
                if evt.get("event") == event_name:
                    return evt
                else:
                    # 不匹配，放回队列末尾，继续等待
                    self._event_q.put(evt)
            except queue.Empty:
                continue
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
    """启动 TestScript（debug 模式用 DEBUG_CP = target/classes，自研 JSON 库）。

    -Dfile.encoding=UTF-8：强制 Java stdout/stderr 用 UTF-8（Windows 默认 GBK 会导致中文匹配失败）
    """
    cmd = [JAVA, "-Dfile.encoding=UTF-8", "-cp", DEBUG_CP, "org.gscript.TestScript", name, mode]
    return subprocess.Popen(
        cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        encoding="utf-8", errors="replace",
    )


def wait_stderr_ready(proc, marker, timeout=10):
    """等待 Java 进程 stderr 出现指定标记（如 'enableDebugMode 已启用'）。

    非阻塞读取：用线程收集 stderr 到 buffer，主线程轮询检查。
    """
    buf = []
    done = threading.Event()

    def reader():
        while not done.is_set():
            line = proc.stderr.readline()
            if not line:
                break
            buf.append(line)
            if marker in line:
                done.set()
                return
    t = threading.Thread(target=reader, daemon=True)
    t.start()
    deadline = time.time() + timeout
    while time.time() < deadline:
        if done.is_set():
            return "".join(buf)
        time.sleep(0.1)
    return "".join(buf)
