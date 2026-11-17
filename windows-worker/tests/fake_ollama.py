"""Minimal fake Ollama HTTP server for end-to-end tests (no GPU / model needed).

Usage: python tests/fake_ollama.py 11500
"""
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODELS = ["qwen2.5:3b"]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # keep test output quiet
        pass

    def _json(self, obj, code=200):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/api/tags":
            self._json({"models": [{"name": m, "size": 1, "details": {}} for m in MODELS]})
        else:
            self._json({}, 404)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
        user = body["messages"][-1]["content"]
        reply = f"Hello from fake Ollama. You said: {user}"
        if not body.get("stream"):
            self._json({"message": {"role": "assistant", "content": reply}, "done": True, "eval_count": 7})
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/x-ndjson")
        self.end_headers()
        for word in reply.split(" "):
            self.wfile.write((json.dumps({"message": {"content": word + " "}, "done": False}) + "\n").encode())
            self.wfile.flush()
            time.sleep(0.01)
        self.wfile.write((json.dumps({"message": {"content": ""}, "done": True, "eval_count": 7}) + "\n").encode())


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
