#!/usr/bin/env python3
"""Static file server for the §4.3 boot experiment.

`python3 -m http.server` is threaded (since 3.7) but speaks HTTP/1.0, so it closes
the TCP connection after every response. Alabaster Dawn's boot pulls 1100+ assets,
i.e. 1100+ connect/teardown cycles. This server is the same handler with
HTTP/1.1 keep-alive; nothing else differs, so it is a fair A/B against the CLI.

Usage: python3 tools/serve-fast.py [port] [directory]
"""
import functools
import http.server
import sys
import threading

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8099
DIRECTORY = sys.argv[2] if len(sys.argv) > 2 else "."


class Handler(http.server.SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass


class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


if __name__ == "__main__":
    handler = functools.partial(Handler, directory=DIRECTORY)
    with Server(("127.0.0.1", PORT), handler) as httpd:
        print(f"serving {DIRECTORY} on http://127.0.0.1:{PORT} (HTTP/1.1 keep-alive)", flush=True)
        httpd.serve_forever()
