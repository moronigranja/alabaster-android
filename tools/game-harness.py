#!/usr/bin/env python3
"""Boot a game in headless Chromium with a shim injected at document start.

This is the same shape the Android host uses: the page runs unmodified and the profile's shim
(`android/app/src/main/assets/port-*.js`) is injected before the page's own scripts - via CDP
`Page.addScriptToEvaluateOnNewDocument`, the desktop equivalent of `addDocumentStartJavaScript`.
The game tree is served with `python3 -m http.server`, like the original probes (FINDINGS.md §4.3).

The device bridge is absent here on purpose: the shims fall back to their "no bridge" paths, which is
enough to prove the *game side* - that the platform switch, the Node/NW.js surface and the entry
handshake are complete. A game that boots here and fails on a phone is a device problem, not a shim
problem, and the port's diagnostics panel is where that is read.

usage: cc-harness.py <shim.js> [seconds] [screenshot.png]
       GAME_DIR=<install dir> URL_PATH=<entry under that dir> ./cc-harness.py ...

defaults (CrossCode on a Steam install):
       GAME_DIR=~/.local/share/Steam/steamapps/common/CrossCode
       URL_PATH=assets/node-webkit.html

Deps: a `chromium` on PATH and tools/probes/cdp.py next to this file.
"""
import base64
import json
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "probes"))
import cdp  # noqa: E402

cdp.PORT = PORT = os.environ.get("CDP_PORT", "9333")

SHIM = sys.argv[1]
WAIT = float(sys.argv[2]) if len(sys.argv) > 2 else 25.0
SHOT = sys.argv[3] if len(sys.argv) > 3 else None

GAME_DIR = os.path.expanduser(
    os.environ.get("GAME_DIR", "~/.local/share/Steam/steamapps/common/CrossCode"))
URL_PATH = os.environ.get("URL_PATH", "assets/node-webkit.html")
PROFILE = os.environ.get("CDP_PROFILE", "/tmp/port-harness-chrome")

EVENTS = []


def record(msg):
    method = msg.get("method")
    if method == "Runtime.consoleAPICalled":
        args = " ".join(str(a.get("value", a.get("description", ""))) for a in msg["params"]["args"])
        EVENTS.append(("[console.%s]" % msg["params"]["type"], args[:300]))
    elif method == "Runtime.exceptionThrown":
        d = msg["params"]["exceptionDetails"]
        EVENTS.append(("[exception]", (d.get("exception", {}).get("description") or d.get("text"))[:400]))
    elif method == "Log.entryAdded":
        e = msg["params"]["entry"]
        EVENTS.append(("[log.%s]" % e.get("level"), "%s %s" % (e.get("text", "")[:200], e.get("url", ""))))


def cmd(sock, mid, method, params=None):
    cdp.send(sock, json.dumps({"id": mid, "method": method, "params": params or {}}))
    while True:
        msg = json.loads(cdp.recv(sock))
        if msg.get("id") == mid:
            return msg
        record(msg)


def main():
    server = subprocess.Popen(
        ["python3", "-m", "http.server", "8099", "--bind", "127.0.0.1"], cwd=GAME_DIR,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    chrome = subprocess.Popen(
        ["chromium", "--headless=new", "--no-sandbox", "--disable-dev-shm-usage",
         "--enable-unsafe-swiftshader", "--disable-extensions",
         "--remote-debugging-port=" + PORT, "--user-data-dir=" + PROFILE,
         "--window-size=1136,640", "about:blank"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        time.sleep(3)
        sock = cdp.connect(cdp.page_ws("about:blank"))
        cmd(sock, 1, "Runtime.enable")
        cmd(sock, 2, "Log.enable")
        cmd(sock, 3, "Page.enable")
        with open(SHIM) as f:
            source = f.read()
        r = cmd(sock, 4, "Page.addScriptToEvaluateOnNewDocument", {"source": source})
        assert "error" not in r, r
        cmd(sock, 5, "Page.navigate", {"url": "http://127.0.0.1:8099/" + URL_PATH})

        deadline = time.time() + WAIT
        while time.time() < deadline:
            try:
                sock.settimeout(deadline - time.time())
                record(json.loads(cdp.recv(sock)))
            except Exception:
                break

        sock2 = cdp.connect(cdp.page_ws(URL_PATH))
        state = cmd(sock2, 6, "Runtime.evaluate", {
            "expression":
                "({platform: (window.ig && ig.getPlatformName && ig.getPlatformName()) || null,"
                " engine: (window.ig && ig.engineName) || null,"
                " running: !!(window.ig && ig.system && ig.system.running),"
                " canvas: (function(){var c=document.getElementById('canvas');"
                "return c ? c.width+'x'+c.height : null;})(),"
                " title: document.title})",
            "returnByValue": True, "awaitPromise": True})
        print(json.dumps(state.get("result", {}).get("result", state), indent=2))

        counts = {}
        for kind, _ in EVENTS:
            counts[kind] = counts.get(kind, 0) + 1
        print("--- received:", json.dumps(counts))
        for kind, text in EVENTS:
            if kind not in ("[console.log]", "[log.warning]") and "favicon" not in text:
                print(kind, text)

        if SHOT:
            shot = cmd(sock2, 7, "Page.captureScreenshot", {"format": "png"})
            data = shot.get("result", {}).get("data")
            if data:
                with open(SHOT, "wb") as f:
                    f.write(base64.b64decode(data))
                print("screenshot:", SHOT)
    finally:
        chrome.terminate()
        server.terminate()


main()
