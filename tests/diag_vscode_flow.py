# -*- coding: utf-8 -*-
"""
诊断脚本：严格模拟 VSCode 的 DAP 流程测试 gscript 调试适配器 jar。
逐步打印每条发送/接收的消息，定位 jar 是否正确响应。

用法：
    python diag_vscode_flow.py
"""
import subprocess
import threading
import time
import json
import sys
import os
import queue

JAR = r"e:\JProjects\gscript\target\gscript-1.0-SNAPSHOT.jar"
JAVA = r"C:\Program Files\Java\jdk-9.0.4\bin\java.exe"
SCRIPT = r"e:\JProjects\gscript\src\main\resources\debug_test.script"
BREAK_LINE = 52  # var fib10 = fibonacci(10);

# 启动 DAP 适配器进程（stdio 模式），用原始 stdio（不设编码、不加 UniversalNewlines）
proc = subprocess.Popen(
    [JAVA, "-jar", JAR, "--stdio"],
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    bufsize=0,
)

recv_q = queue.Queue()
recv_log = []
lock = threading.Lock()


def reader_thread():
    """持续读取 DAP 消息（Content-Length 分帧），放入队列。"""
    buf = b""
    while True:
        b = proc.stdout.read(1)
        if not b:
            print("[READER] stdout ended", flush=True)
            return
        buf += b
        # 检测帧头结束 \r\n\r\n
        idx = buf.find(b"\r\n\r\n")
        if idx == -1:
            continue
        header = buf[:idx].decode("ascii", errors="replace")
        body_start = idx + 4
        # 解析 Content-Length
        clen = 0
        for line in header.split("\r\n"):
            if line.lower().startswith("content-length:"):
                clen = int(line.split(":", 1)[1].strip())
        # 读取 body
        body = buf[body_start:]
        while len(body) < clen:
            more = proc.stdout.read(clen - len(body))
            if not more:
                break
            body += more
        buf = b""
        try:
            msg = json.loads(body.decode("utf-8"))
        except Exception as e:
            print("[READER] JSON parse error:", e, body[:200], flush=True)
            continue
        with lock:
            recv_log.append(msg)
        recv_q.put(msg)


def stderr_thread():
    """读取 stderr（jar 异常输出）。"""
    while True:
        b = proc.stderr.read(1)
        if not b:
            return
        sys.stderr.write("[STDERR] " + b.decode("utf-8", errors="replace"))
        sys.stderr.flush()


threading.Thread(target=reader_thread, daemon=True).start()
threading.Thread(target=stderr_thread, daemon=True).start()

seq = [0]


def send(command, args=None):
    seq[0] += 1
    msg = {"seq": seq[0], "type": "request", "command": command}
    if args is not None:
        msg["arguments"] = args
    data = json.dumps(msg).encode("utf-8")
    frame = ("Content-Length: %d\r\n\r\n" % len(data)).encode("ascii") + data
    print("\n>>> SEND %s seq=%d" % (command, seq[0]), flush=True)
    print("    args:", json.dumps(args, ensure_ascii=False)[:300], flush=True)
    proc.stdin.write(frame)
    proc.stdin.flush()


def wait_for(predicate, timeout=8.0, desc=""):
    """等待满足 predicate(msg) 的消息，返回该 msg。"""
    deadline = time.time() + timeout
    checked = []
    while time.time() < deadline:
        try:
            msg = recv_q.get(timeout=0.2)
        except queue.Empty:
            continue
        checked.append(msg)
        if predicate(msg):
            return msg
    print("[TIMEOUT] waiting for %s, checked %d msgs:" % (desc, len(checked)), flush=True)
    for m in checked:
        print("   ", json.dumps(m, ensure_ascii=False)[:200], flush=True)
    return None


def wait_response(command, request_seq, timeout=8.0):
    return wait_for(
        lambda m: m.get("type") == "response" and m.get("command") == command
        and m.get("request_seq") == request_seq,
        timeout=timeout,
        desc="response %s (req_seq=%d)" % (command, request_seq),
    )


def wait_event(event, timeout=8.0):
    return wait_for(
        lambda m: m.get("type") == "event" and m.get("event") == event,
        timeout=timeout,
        desc="event %s" % event,
    )


def dump_msgs_since():
    with lock:
        print("[ALL MSGS SO FAR: %d]" % len(recv_log), flush=True)
        for m in recv_log:
            print("  ", json.dumps(m, ensure_ascii=False)[:250], flush=True)


print("=" * 70)
print("诊断 gscript DAP 适配器")
print("JAR:", JAR)
print("JAVA:", JAVA)
print("SCRIPT:", SCRIPT)
print("BREAK_LINE:", BREAK_LINE)
print("=" * 70)

# 步骤 1：initialize
send("initialize", {
    "clientID": "vscode",
    "clientName": "Visual Studio Code",
    "adapterID": "gscript",
    "linesStartAt1": True,
    "columnsStartAt1": True,
    "pathFormat": "path",
    "supportsRunInTerminalRequest": False,
    "locale": "zh-cn",
})
init_resp = wait_response("initialize", 1)
if init_resp:
    print("<<< RECV initialize response success=%s" % init_resp.get("success"), flush=True)
    body = init_resp.get("body", {})
    print("    capabilities:", json.dumps(body, ensure_ascii=False)[:300], flush=True)
else:
    print("[FAIL] initialize 无响应！", flush=True)
    dump_msgs_since()
    proc.kill()
    sys.exit(1)

# 步骤 2：等待 initialized 事件（VSCode 严格依赖此事件）
init_evt = wait_event("initialized", timeout=5.0)
if init_evt:
    print("<<< RECV initialized event ✓", flush=True)
else:
    print("[FAIL] 未收到 initialized 事件！VSCode 会卡在这里。", flush=True)
    dump_msgs_since()
    proc.kill()
    sys.exit(1)

# 步骤 3：launch
send("launch", {
    "program": SCRIPT,
    "stopOnEntry": False,
})
launch_resp = wait_response("launch", 2)
if launch_resp:
    print("<<< RECV launch response success=%s" % launch_resp.get("success"), flush=True)
else:
    print("[FAIL] launch 无响应！", flush=True)
    dump_msgs_since()
    proc.kill()
    sys.exit(1)

# 步骤 4：setBreakpoints
send("setBreakpoints", {
    "source": {"path": SCRIPT},
    "breakpoints": [{"line": BREAK_LINE}],
    "linesStartAt1": True,
    "sourceModified": False,
})
bp_resp = wait_response("setBreakpoints", 3)
if bp_resp:
    print("<<< RECV setBreakpoints response:", json.dumps(bp_resp.get("body"), ensure_ascii=False)[:200], flush=True)
else:
    print("[FAIL] setBreakpoints 无响应！", flush=True)

# 步骤 5：configurationDone
send("configurationDone", {})
cd_resp = wait_response("configurationDone", 4)
if cd_resp:
    print("<<< RECV configurationDone response success=%s" % cd_resp.get("success"), flush=True)
else:
    print("[FAIL] configurationDone 无响应！", flush=True)

# 步骤 6：等待 stopped 事件（断点命中）
print("\n--- 等待 stopped 事件（断点命中 line %d）---" % BREAK_LINE, flush=True)
stopped = wait_event("stopped", timeout=15.0)
if stopped:
    body = stopped.get("body", {})
    print("<<< RECV stopped event ✓ reason=%s threadId=%s" % (
        body.get("reason"), body.get("threadId")), flush=True)
    print("    full:", json.dumps(stopped, ensure_ascii=False)[:300], flush=True)
else:
    print("[FAIL] 未收到 stopped 事件！断点未命中或脚本未运行。", flush=True)
    dump_msgs_since()
    proc.kill()
    sys.exit(1)

# 步骤 7：threads
send("threads", {})
threads_resp = wait_response("threads", 5)
if threads_resp:
    print("<<< RECV threads response:", json.dumps(threads_resp.get("body"), ensure_ascii=False)[:200], flush=True)
else:
    print("[FAIL] threads 无响应！", flush=True)

# 步骤 8：stackTrace
send("stackTrace", {"threadId": 1})
st_resp = wait_response("stackTrace", 6)
if st_resp:
    body = st_resp.get("body", {})
    print("<<< RECV stackTrace response: totalFrames=%s" % body.get("totalFrames"), flush=True)
    for f in body.get("stackFrames", []):
        print("    frame: id=%s name=%s line=%s col=%s src=%s" % (
            f.get("id"), f.get("name"), f.get("line"), f.get("column"),
            f.get("source", {}).get("path", "")), flush=True)
else:
    print("[FAIL] stackTrace 无响应！", flush=True)

# 步骤 9：continue
send("continue", {"threadId": 1})
cont_resp = wait_response("continue", 7)
if cont_resp:
    print("<<< RECV continue response success=%s" % cont_resp.get("success"), flush=True)

# 步骤 10：等待 terminated 事件
print("\n--- 等待 terminated 事件（脚本执行结束）---", flush=True)
term = wait_event("terminated", timeout=15.0)
if term:
    print("<<< RECV terminated event ✓", flush=True)
else:
    print("[WARN] 未收到 terminated 事件", flush=True)
    dump_msgs_since()

print("\n" + "=" * 70)
print("诊断完成。所有接收的消息：")
dump_msgs_since()
print("=" * 70)

proc.kill()
