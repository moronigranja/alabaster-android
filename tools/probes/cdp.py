#!/usr/bin/env python3
"""Minimal CDP client with awaitPromise: evaluate one expression in the game page.

usage: cdp.py '<expression>'          (expression may be async; awaits its promise)
       cdp.py --file /tmp/x.js
Full result is written to /tmp/cdp-out.json and printed."""
import base64, json, os, socket, struct, subprocess, sys, time

PORT = os.environ.get("CDP_PORT", "9222")


def sh(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True).stdout.strip()


def page_ws(pred="index.html"):
    pages = json.loads(sh("curl -s http://127.0.0.1:%s/json/list" % PORT))
    for p in pages:
        if pred in p.get("url", ""):
            return p["webSocketDebuggerUrl"]
    raise SystemExit("no page matching %s in %s" % (pred, [p.get("url") for p in pages]))


def connect(ws_url):
    hostport, path = ws_url[5:].split("/", 1)
    host, port = hostport.split(":")
    s = socket.create_connection((host, int(port)))
    key = base64.b64encode(os.urandom(16)).decode()
    s.sendall(("GET /%s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
               "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n" % (path, hostport, key)).encode())
    buf = b""
    while b"\r\n\r\n" not in buf:
        buf += s.recv(4096)
    assert b"101" in buf.split(b"\r\n")[0], buf[:200]
    return s


def send(s, text):
    data = text.encode()
    head = bytearray([0x81])
    n = len(data)
    if n < 126:
        head.append(0x80 | n)
    elif n < 65536:
        head.append(0x80 | 126); head += struct.pack(">H", n)
    else:
        head.append(0x80 | 127); head += struct.pack(">Q", n)
    mask = os.urandom(4)
    s.sendall(bytes(head) + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))


def recv(s):
    def rd(n):
        out = b""
        while len(out) < n:
            chunk = s.recv(n - len(out))
            if not chunk:
                raise EOFError
            out += chunk
        return out
    head = rd(2)
    length = head[1] & 0x7F
    if length == 126:
        length = struct.unpack(">H", rd(2))[0]
    elif length == 127:
        length = struct.unpack(">Q", rd(8))[0]
    return rd(length).decode("utf-8", "replace")


def evaluate(expr, timeout=60.0, await_promise=True):
    s = connect(page_ws())
    s.settimeout(timeout)
    send(s, json.dumps({"id": 1, "method": "Runtime.evaluate",
                        "params": {"expression": expr, "returnByValue": True,
                                   "awaitPromise": await_promise}}))
    while True:
        msg = json.loads(recv(s))
        if msg.get("id") == 1:
            return msg


if __name__ == "__main__":
    args = sys.argv[1:]
    if args and args[0] == "--file":
        expr = open(args[1]).read()
    else:
        expr = " ".join(args)
    out = evaluate(expr)
    with open("/tmp/cdp-out.json", "w") as f:
        json.dump(out, f, indent=1)
    res = out.get("result", {})
    if "exceptionDetails" in res:
        print("EXCEPTION:", json.dumps(res["exceptionDetails"])[:2000])
    val = res.get("result", {}).get("value")
    print(val if isinstance(val, str) else json.dumps(res)[:4000])
